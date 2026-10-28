package com.bohemian.odoo.pos.payment

import com.bohemian.odoo.pos.core.BillingRefGenerator
import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.pine.GatewayResult
import com.bohemian.odoo.pos.pine.PayMode
import com.bohemian.odoo.pos.pine.PaymentGateway
import com.bohemian.odoo.pos.pine.PrintLine
import com.bohemian.odoo.pos.pine.PrintResult
import com.bohemian.odoo.pos.pine.SaleRequest
import com.bohemian.odoo.pos.pine.TxnDetails
import com.bohemian.odoo.pos.pine.VoidRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scripted gateway: queue the results you want, count the calls you get. */
private class ScriptedGateway : PaymentGateway {
    override val name = "SCRIPTED"
    val saleResults = ArrayDeque<GatewayResult>()
    val statusResults = ArrayDeque<GatewayResult>()
    var saleCalls = 0
    var statusCalls = 0
    override suspend fun sale(request: SaleRequest, onStarted: (String) -> Unit): GatewayResult {
        saleCalls++; onStarted("evt-$saleCalls"); return saleResults.removeFirst()
    }
    override suspend fun getStatus(billingRef: String, amount: Paise, mode: PayMode, onStarted: (String) -> Unit): GatewayResult {
        statusCalls++; return statusResults.removeFirstOrNull() ?: GatewayResult.Ambiguous("Timeout", null)
    }
    val voidResults = ArrayDeque<GatewayResult>()
    val voidRequests = mutableListOf<VoidRequest>()
    override suspend fun void(request: VoidRequest, onStarted: (String) -> Unit): GatewayResult {
        voidRequests += request; onStarted("void-evt-${voidRequests.size}")
        return voidResults.removeFirstOrNull() ?: GatewayResult.Rejected("NotSupported", null)
    }
    override suspend fun print(lines: List<PrintLine>, printRef: String, widthChars: Int): PrintResult = PrintResult.Printed
}

class InMemoryJournal : PaymentJournal {
    val rows = LinkedHashMap<String, PaymentAttempt>()
    override suspend fun insert(attempt: PaymentAttempt) { check(attempt.billingRef !in rows); rows[attempt.billingRef] = attempt }
    override suspend fun get(billingRef: String) = rows[billingRef]
    override suspend fun forOrder(orderUuid: String) = rows.values.filter { it.orderUuid == orderUuid }
    override suspend fun unresolved() = rows.values.filter { !it.state.isFinal }
    override suspend fun setEventId(billingRef: String, eventId: String, now: Long) {
        rows[billingRef] = rows[billingRef]!!.let { it.copy(eventId = eventId, state = if (it.state == AttemptState.STARTING) AttemptState.IN_FLIGHT else it.state) }
    }
    override suspend fun transition(billingRef: String, state: AttemptState, now: Long, details: TxnDetails?, note: String?, incrementStatusChecks: Boolean) {
        rows[billingRef] = rows[billingRef]!!.let {
            it.copy(state = state, details = details ?: it.details, note = note ?: it.note,
                statusChecks = it.statusChecks + if (incrementStatusChecks) 1 else 0, updatedAt = now)
        }
    }
}

class InMemoryVoidJournal : VoidJournal {
    val rows = LinkedHashMap<String, VoidAttempt>()
    override suspend fun insert(attempt: VoidAttempt) { check(attempt.billingRef !in rows); rows[attempt.billingRef] = attempt }
    override suspend fun get(billingRef: String) = rows[billingRef]
    override suspend fun forSale(saleRef: String) = rows.values.filter { it.saleRef == saleRef }
    override suspend fun open() = rows.values.filter { it.state.isOpen }
    override suspend fun setEventId(billingRef: String, eventId: String, now: Long) {
        rows[billingRef] = rows[billingRef]!!.let { it.copy(eventId = eventId, state = if (it.state == VoidState.STARTING) VoidState.IN_FLIGHT else it.state) }
    }
    override suspend fun transition(billingRef: String, state: VoidState, now: Long, details: TxnDetails?, note: String?) {
        rows[billingRef] = rows[billingRef]!!.let { it.copy(state = state, details = details ?: it.details, note = note ?: it.note, updatedAt = now) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentCoordinatorTest {
    private val gw = ScriptedGateway()
    private val journal = InMemoryJournal()
    private val voids = InMemoryVoidJournal()
    private val gen = BillingRefGenerator("TEST")
    private fun TestScope.coordinator() = PaymentCoordinator(
        gateway = { gw }, journal = journal, voids = voids, refs = { gen },
        policy = StatusPolicy(maxChecks = 3, intervalMs = 5_000), backgroundScope = backgroundScope,
    )
    private val ok = GatewayResult.Approved(TxnDetails(rrn = "123", authCode = "A1"))
    private val amt = Paise(52800)

    @Test fun `card approved`() = runTest {
        gw.saleResults += ok
        val out = coordinator().pay("o1", amt, PayMode.CARD)
        assertTrue(out is PaymentOutcome.Paid)
        assertEquals(AttemptState.SUCCESS, journal.rows.values.single().state)
    }

    @Test fun `amount mismatch on approved payment transitions to NEEDS_REVIEW (Invariant I6)`() = runTest {
        val mismatched = GatewayResult.Approved(TxnDetails(rrn = "123", authCode = "A1", amountPaise = 1000L))
        gw.saleResults += mismatched
        val out = coordinator().pay("o1", amt, PayMode.CARD)
        assertTrue(out is PaymentOutcome.Unresolved)
        assertEquals(AttemptState.NEEDS_REVIEW, journal.rows.values.single().state)
    }

    @Test fun `card decline is final and allows a retry`() = runTest {
        gw.saleResults += GatewayResult.Declined("51", "INSUFFICIENT FUNDS")
        gw.saleResults += ok
        val c = coordinator()
        assertTrue(c.pay("o1", amt, PayMode.CARD) is PaymentOutcome.NotPaid)
        assertTrue(c.pay("o1", amt, PayMode.CARD) is PaymentOutcome.Paid)
        assertEquals(2, gw.saleCalls)
        assertEquals(0, gw.statusCalls) // card never polls
    }

    @Test fun `card timeout is NEVER replayed and blocks the order`() = runTest {
        gw.saleResults += GatewayResult.Ambiguous("Timeout", "no reply")
        val c = coordinator()
        assertTrue(c.pay("o1", amt, PayMode.CARD) is PaymentOutcome.Unresolved)
        assertEquals(AttemptState.NEEDS_REVIEW, journal.rows.values.single().state)
        // A second press must not reach the terminal.
        assertTrue(c.pay("o1", amt, PayMode.CARD) is PaymentOutcome.Blocked)
        assertEquals(1, gw.saleCalls)
    }

    @Test fun `upi timeout then GetStatus approved`() = runTest {
        gw.saleResults += GatewayResult.Ambiguous("Timeout", null)
        gw.statusResults += GatewayResult.Ambiguous("Timeout", null)
        gw.statusResults += ok
        val out = coordinator().pay("o1", amt, PayMode.UPI)
        assertTrue(out is PaymentOutcome.Paid)
        assertEquals(1, gw.saleCalls)
        assertEquals(2, gw.statusCalls)
        assertEquals(2, journal.rows.values.single().statusChecks)
    }

    @Test fun `upi failed response is confirmed with GetStatus (Pine rule)`() = runTest {
        gw.saleResults += GatewayResult.Declined("U30", "SESSION EXPIRED")
        gw.statusResults += ok // customer actually paid late
        assertTrue(coordinator().pay("o1", amt, PayMode.UPI) is PaymentOutcome.Paid)
    }

    @Test fun `bqr status exhausted becomes UNKNOWN and polling is bounded`() = runTest {
        gw.saleResults += GatewayResult.Ambiguous("TransportException", null)
        val c = coordinator()
        assertTrue(c.pay("o1", amt, PayMode.BHARAT_QR) is PaymentOutcome.Unresolved)
        assertEquals(3, gw.statusCalls)
        assertEquals(AttemptState.UNKNOWN, journal.rows.values.single().state)
        // check again later resolves it
        gw.statusResults += GatewayResult.Declined("U30", "EXPIRED")
        val ref = journal.rows.keys.single()
        assertTrue(c.checkAgain(ref) is PaymentOutcome.NotPaid)
        assertEquals(1, gw.saleCalls) // never replayed
    }

    @Test fun `rejected before start is safe to retry`() = runTest {
        gw.saleResults += GatewayResult.Rejected("OperationInProgress", "busy")
        gw.saleResults += ok
        val c = coordinator()
        assertTrue(c.pay("o1", amt, PayMode.CARD) is PaymentOutcome.NotSent)
        assertTrue(c.pay("o1", amt, PayMode.CARD) is PaymentOutcome.Paid)
    }

    @Test fun `never charge a paid order twice`() = runTest {
        gw.saleResults += ok
        val c = coordinator()
        c.pay("o1", amt, PayMode.CARD)
        assertTrue(c.pay("o1", amt, PayMode.UPI) is PaymentOutcome.AlreadyPaid)
        assertEquals(1, gw.saleCalls)
    }

    @Test fun `reconciler after crash - upi resolved by status, card goes to review`() = runTest {
        val c = coordinator()
        journal.insert(PaymentAttempt("R1", "o1", amt, PayMode.UPI, AttemptState.IN_FLIGHT, "evt", createdAt = 0, updatedAt = 0))
        journal.insert(PaymentAttempt("R2", "o2", amt, PayMode.CARD, AttemptState.STARTING, createdAt = 0, updatedAt = 0))
        gw.statusResults += ok
        val paid = mutableListOf<String>()
        val outcomes = Reconciler(journal, c) { paid += it.billingRef }.run()
        assertEquals(2, outcomes.size)
        assertEquals(AttemptState.SUCCESS, journal.rows["R1"]!!.state)
        assertEquals(AttemptState.NEEDS_REVIEW, journal.rows["R2"]!!.state)
        assertEquals(listOf("R1"), paid)
        assertEquals(0, gw.saleCalls)
    }

    @Test fun `supervisor resolution unblocks the order`() = runTest {
        gw.saleResults += GatewayResult.Ambiguous("Timeout", null)
        val c = coordinator()
        c.pay("o1", amt, PayMode.CARD)
        val ref = journal.rows.keys.single()
        assertTrue(c.resolveManually(ref, paid = false, note = "not on terminal report") is PaymentOutcome.NotPaid)
        gw.saleResults += ok
        assertTrue(c.pay("o1", amt, PayMode.CARD) is PaymentOutcome.Paid)
    }

    // ------------------------------------------------------------------ field report 2026-10-27:
    // restart → "Supervisor: NOT paid" did nothing, then "ZenZebra has stopped".

    @Test fun `supervisor decision on an attempt already settled returns its outcome and never throws`() = runTest {
        gw.saleResults += GatewayResult.Ambiguous("Timeout", null)
        gw.statusResults += GatewayResult.Declined("U30", "EXPIRED") // the status check settles it first
        val c = coordinator()
        assertTrue(c.pay("o1", amt, PayMode.UPI) is PaymentOutcome.NotPaid)
        val ref = journal.rows.keys.single()
        // The supervisor's tap was queued behind the check: it must not crash, and not overwrite.
        assertTrue(c.resolveManually(ref, paid = true, note = "late tap") is PaymentOutcome.NotPaid)
        assertTrue(c.resolveManually(ref, paid = false, note = "double tap") is PaymentOutcome.NotPaid)
        assertEquals(AttemptState.DECLINED, journal.rows[ref]!!.state)
    }

    @Test fun `unknown payment ref gives no outcome instead of an exception`() = runTest {
        val c = coordinator()
        assertEquals(null, c.resolveManually("NOPE", paid = false, note = ""))
        assertEquals(null, c.checkAgain("NOPE"))
    }

    @Test fun `supervisor decision during a running status check wins at once and stops the check`() = runTest {
        val c = coordinator()
        journal.insert(PaymentAttempt("R1", "o1", amt, PayMode.UPI, AttemptState.IN_FLIGHT, "evt", createdAt = 0, updatedAt = 0))
        val reconcile = async { c.reconcile(journal.rows["R1"]!!) }
        advanceTimeBy(5_001) // first GetStatus done (no answer); the loop now waits, without the lock
        assertEquals(1, gw.statusCalls)
        assertTrue(c.resolveManually("R1", paid = false, note = "not on terminal report") is PaymentOutcome.NotPaid)
        assertTrue(reconcile.await() is PaymentOutcome.NotPaid)
        assertEquals(1, gw.statusCalls) // the check stopped instead of asking the terminal again
        assertEquals(AttemptState.RESOLVED_NOT_PAID, journal.rows["R1"]!!.state)
    }

    @Test fun `a second Check status while one runs does not start another loop`() = runTest {
        val c = coordinator()
        journal.insert(PaymentAttempt("R1", "o1", amt, PayMode.UPI, AttemptState.UNKNOWN, "evt", createdAt = 0, updatedAt = 0))
        val first = async { c.checkAgain("R1") }
        advanceTimeBy(1_000)
        assertEquals(setOf("R1"), c.checking.value)
        assertTrue(c.checkAgain("R1") is PaymentOutcome.Unresolved) // returns at once
        gw.statusResults += ok
        assertTrue(first.await() is PaymentOutcome.Paid)
        assertEquals(1, gw.statusCalls)
        assertEquals(emptySet<String>(), c.checking.value)
    }

    // ------------------------------------------------------------------ split bills

    @Test fun `split bill - a second part is charged up to the total, never beyond`() = runTest {
        val total = Paise(50000)
        gw.saleResults += GatewayResult.Approved(TxnDetails(rrn = "1", amountPaise = 30000))
        gw.saleResults += GatewayResult.Approved(TxnDetails(rrn = "2", amountPaise = 20000))
        val c = coordinator()
        assertTrue(c.pay("o1", Paise(30000), PayMode.UPI, orderTotal = total) is PaymentOutcome.Paid)
        assertTrue(c.pay("o1", Paise(20000), PayMode.CARD, orderTotal = total) is PaymentOutcome.Paid)
        // Fully paid: a third charge is refused before the terminal is called.
        assertTrue(c.pay("o1", Paise(100), PayMode.CARD, orderTotal = total) is PaymentOutcome.AlreadyPaid)
        assertEquals(2, gw.saleCalls)
        assertEquals(2, journal.rows.size)
        assertEquals(2, journal.rows.keys.toSet().size) // a new billing ref per part (HARD RULE 8)
    }

    @Test fun `split bill - an unresolved part blocks the next one`() = runTest {
        val total = Paise(50000)
        gw.saleResults += GatewayResult.Ambiguous("Timeout", null) // card part outcome unknown
        val c = coordinator()
        assertTrue(c.pay("o1", Paise(30000), PayMode.CARD, orderTotal = total) is PaymentOutcome.Unresolved)
        assertTrue(c.pay("o1", Paise(20000), PayMode.UPI, orderTotal = total) is PaymentOutcome.Blocked)
        assertEquals(1, gw.saleCalls)
    }

    @Test fun `split bill - a part bigger than what is still due is refused`() = runTest {
        val total = Paise(50000)
        gw.saleResults += GatewayResult.Approved(TxnDetails(rrn = "1", amountPaise = 30000))
        val c = coordinator()
        c.pay("o1", Paise(30000), PayMode.UPI, orderTotal = total)
        assertTrue(c.pay("o1", Paise(30000), PayMode.CARD, orderTotal = total) is PaymentOutcome.AlreadyPaid)
        assertEquals(1, gw.saleCalls)
    }

    // ------------------------------------------------------------------ void (Phase 4b)

    private val cardOk = GatewayResult.Approved(TxnDetails(transactionId = "TXN77", rrn = "123", authCode = "A1", amountPaise = 52800))

    private suspend fun paidCardSale(c: PaymentCoordinator): String {
        gw.saleResults += cardOk
        return (c.pay("o1", amt, PayMode.CARD) as PaymentOutcome.Paid).attempt.billingRef
    }

    @Test fun `void sends a new ref, the sale amount and bank code, and no invoice number`() = runTest {
        val c = coordinator()
        val saleRef = paidCardSale(c)
        gw.voidResults += GatewayResult.Approved(TxnDetails(responseCode = "00", amountPaise = 52800))

        val out = c.void(saleRef)

        assertTrue(out is VoidOutcome.Voided)
        val req = gw.voidRequests.single()
        assertTrue(req.billingRef != saleRef)
        assertEquals(amt, req.amount)
        assertEquals(PINE_VOID_BANK_CODE, req.bankCode)
        assertEquals(null, req.invoiceNumber) // SDK 2.0: source of the sale's InvoiceNo on AppToApp not documented
        val v = voids.rows.values.single()
        assertEquals(VoidState.VOIDED, v.state) // open until the Odoo refund is recorded
        assertTrue(v.state.isOpen)
        assertEquals(AttemptState.SUCCESS, journal.rows[saleRef]!!.state) // the sale record is never rewritten
    }

    @Test fun `void is persisted before the terminal is called`() = runTest {
        val c = coordinator()
        val saleRef = paidCardSale(c)
        var seenBeforeCall: VoidState? = null
        val probe = object : PaymentGateway by gw {
            override suspend fun void(request: VoidRequest, onStarted: (String) -> Unit): GatewayResult {
                seenBeforeCall = voids.rows[request.billingRef]?.state
                return GatewayResult.Approved(TxnDetails())
            }
        }
        PaymentCoordinator({ probe }, journal, voids, { gen }, backgroundScope = backgroundScope).void(saleRef)
        assertEquals(VoidState.STARTING, seenBeforeCall)
    }

    @Test fun `ambiguous void goes to review and is never replayed`() = runTest {
        val c = coordinator()
        val saleRef = paidCardSale(c)
        gw.voidResults += GatewayResult.Ambiguous("Timeout", null)

        assertTrue(c.void(saleRef) is VoidOutcome.Unresolved)
        assertEquals(VoidState.NEEDS_REVIEW, voids.rows.values.single().state)
        assertTrue(c.void(saleRef) is VoidOutcome.Refused) // second tap: refused, nothing sent
        assertEquals(1, gw.voidRequests.size)
    }

    @Test fun `void amount mismatch goes to review (Invariant I6)`() = runTest {
        val c = coordinator()
        val saleRef = paidCardSale(c)
        gw.voidResults += GatewayResult.Approved(TxnDetails(amountPaise = 100))
        assertTrue(c.void(saleRef) is VoidOutcome.Unresolved)
        assertEquals(VoidState.NEEDS_REVIEW, voids.rows.values.single().state)
    }

    @Test fun `declined void leaves the sale standing and may be retried`() = runTest {
        val c = coordinator()
        val saleRef = paidCardSale(c)
        gw.voidResults += GatewayResult.Declined("12", "TXN ALREADY SETTLED")
        gw.voidResults += GatewayResult.Approved(TxnDetails())

        assertTrue(c.void(saleRef) is VoidOutcome.NotVoided)
        assertTrue(c.void(saleRef) is VoidOutcome.Voided)
        assertEquals(listOf(VoidState.FAILED, VoidState.VOIDED), voids.rows.values.map { it.state })
    }

    @Test fun `a voided sale cannot be voided twice`() = runTest {
        val c = coordinator()
        val saleRef = paidCardSale(c)
        gw.voidResults += GatewayResult.Approved(TxnDetails())
        c.void(saleRef)
        assertTrue(c.void(saleRef) is VoidOutcome.Refused)
        assertEquals(1, gw.voidRequests.size)
    }

    @Test fun `only paid card sales with a terminal txn id can be voided`() = runTest {
        val c = coordinator()
        journal.insert(PaymentAttempt("U1", "o2", amt, PayMode.UPI, AttemptState.SUCCESS, details = TxnDetails(transactionId = "T"), createdAt = 0, updatedAt = 0))
        journal.insert(PaymentAttempt("C1", "o3", amt, PayMode.CARD, AttemptState.DECLINED, createdAt = 0, updatedAt = 0))
        journal.insert(PaymentAttempt("C2", "o4", amt, PayMode.CARD, AttemptState.RESOLVED_PAID, createdAt = 0, updatedAt = 0))

        assertTrue(c.void("U1") is VoidOutcome.Refused)
        assertTrue(c.void("C1") is VoidOutcome.Refused)
        assertTrue(c.void("C2") is VoidOutcome.Refused) // no transaction id
        assertTrue(c.void("nope") is VoidOutcome.Refused)
        assertEquals(0, gw.voidRequests.size)
        assertTrue(voids.rows.isEmpty())
    }

    @Test fun `supervisor resolves a review, then confirms the Odoo refund`() = runTest {
        val c = coordinator()
        val saleRef = paidCardSale(c)
        gw.voidResults += GatewayResult.Ambiguous("Timeout", null)
        c.void(saleRef)
        val ref = voids.rows.keys.single()

        assertTrue(c.resolveVoid(ref, voided = true) is VoidOutcome.Voided)
        c.confirmOdooRefund(ref)
        assertEquals(VoidState.ODOO_REFUNDED, voids.rows[ref]!!.state)
        assertTrue(voids.open().isEmpty())
    }

    @Test fun `reconciler sends a void interrupted by a crash to review`() = runTest {
        val c = coordinator()
        voids.insert(VoidAttempt("V1", "S1", "o1", amt, VoidState.IN_FLIGHT, createdAt = 0, updatedAt = 0))
        voids.insert(VoidAttempt("V2", "S2", "o2", amt, VoidState.VOIDED, createdAt = 0, updatedAt = 0))
        Reconciler(journal, c) {}.run()
        assertEquals(VoidState.NEEDS_REVIEW, voids.rows["V1"]!!.state)
        assertEquals(VoidState.VOIDED, voids.rows["V2"]!!.state)
        assertEquals(0, gw.voidRequests.size)
    }

    @Test fun `not-sent reason shows the SDK cause for diagnosis`() = runTest {
        gw.saleResults += GatewayResult.Rejected("TransportUnavailable", "bindService returned false")
        val out = coordinator().pay("o1", amt, PayMode.CARD) as PaymentOutcome.NotSent
        assertEquals("Pine Labs payment app not reachable on this device.\n[TransportUnavailable: bindService returned false]", out.reason)
    }
}
