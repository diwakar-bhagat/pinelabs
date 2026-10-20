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
import kotlinx.coroutines.test.TestScope
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
    override suspend fun void(request: VoidRequest, onStarted: (String) -> Unit) = GatewayResult.Rejected("NotSupported", null)
    override suspend fun print(lines: List<PrintLine>, printRef: String): PrintResult = PrintResult.Printed
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

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentCoordinatorTest {
    private val gw = ScriptedGateway()
    private val journal = InMemoryJournal()
    private val gen = BillingRefGenerator("TEST")
    private fun TestScope.coordinator() = PaymentCoordinator(
        gateway = { gw }, journal = journal, refs = { gen },
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
}
