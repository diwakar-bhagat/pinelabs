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
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.10.0 (field video, 3 Nov 2026): a UPI cancel on Pine's QR screen kept the till "checking" for about
 * 30 s, and the cashier left for Pine's app. Now an explicit decline is confirmed with one check soon after,
 * and the cashier's Force done / Not paid ends a check at once, while the terminal's own answer still wins.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentForceDoneTest {
    /** A terminal whose GetStatus takes [statusMs], as the E700's does (Pine's app opens for it). */
    private class SlowStatusGateway(val statusMs: Long = 0) : PaymentGateway {
        override val name = "SLOW"
        val saleResults = ArrayDeque<GatewayResult>()
        val statusResults = ArrayDeque<GatewayResult>()
        var statusCalls = 0
        override suspend fun sale(request: SaleRequest, onStarted: (String) -> Unit): GatewayResult {
            onStarted("evt"); return saleResults.removeFirst()
        }
        override suspend fun getStatus(billingRef: String, amount: Paise, mode: PayMode, onStarted: (String) -> Unit): GatewayResult {
            statusCalls++; delay(statusMs); return statusResults.removeFirstOrNull() ?: GatewayResult.Ambiguous("Pending", null)
        }
        override suspend fun void(request: VoidRequest, onStarted: (String) -> Unit): GatewayResult = GatewayResult.Rejected("NotSupported", null)
        override suspend fun print(lines: List<PrintLine>, printRef: String, widthChars: Int): PrintResult = PrintResult.Printed
    }

    private val journal = InMemoryJournal()
    private val amt = Paise(500)
    private fun TestScope.coordinator(gw: PaymentGateway) = PaymentCoordinator(
        gateway = { gw }, journal = journal, voids = InMemoryVoidJournal(), refs = { BillingRefGenerator("TEST") },
        backgroundScope = backgroundScope,
    )

    @Test fun `a UPI cancel is confirmed with one GetStatus a second later, not the 15 s loop`() = runTest {
        val gw = SlowStatusGateway().apply {
            saleResults += GatewayResult.Declined("CANCELLED", "Cancelled on the terminal")
            statusResults += GatewayResult.Declined("FAILED", "Transaction cancelled")
        }
        assertTrue(coordinator(gw).pay("o1", amt, PayMode.UPI) is PaymentOutcome.NotPaid)
        assertEquals(1, gw.statusCalls)
        assertEquals(1_000L, currentTime)
        assertEquals(AttemptState.DECLINED, journal.rows.values.single().state)
    }

    @Test fun `a UPI cancel the check cannot confirm waits for the cashier, without more checks`() = runTest {
        val gw = SlowStatusGateway().apply { saleResults += GatewayResult.Declined("CANCELLED", null) }
        assertTrue(coordinator(gw).pay("o1", amt, PayMode.UPI) is PaymentOutcome.Unresolved)
        assertEquals(1, gw.statusCalls)
        assertEquals(AttemptState.UNKNOWN, journal.rows.values.single().state)
    }

    @Test fun `PENDING still gets three checks, at 1, 5 and 10 s`() = runTest {
        val gw = SlowStatusGateway().apply { saleResults += GatewayResult.Ambiguous("Pending", null) }
        assertTrue(coordinator(gw).pay("o1", amt, PayMode.UPI) is PaymentOutcome.Unresolved)
        assertEquals(3, gw.statusCalls)
        assertEquals(10_000L, currentTime)
    }

    @Test fun `Force done during the wait ends the check at once`() = runTest {
        val gw = SlowStatusGateway().apply { saleResults += GatewayResult.Ambiguous("Pending", null) }
        val c = coordinator(gw)
        val pay = async { c.pay("o1", amt, PayMode.UPI) }
        advanceTimeBy(1_001) // the first check answered nothing; the loop waits 4 s for the next
        assertEquals(setOf("o1"), c.checkingOrders.value)
        val ref = journal.rows.keys.single()
        assertTrue(c.resolveManually(ref, paid = true, note = "force done by cashier") is PaymentOutcome.Paid)
        runCurrent()
        val out = pay.await()
        assertTrue(out is PaymentOutcome.Paid)
        assertEquals(1, gw.statusCalls) // no further GetStatus
        assertTrue(currentTime < 2_000)
        assertEquals(AttemptState.RESOLVED_PAID, journal.rows[ref]!!.state)
        assertEquals(null, out.attempt?.details?.transactionId) // Odoo gets it with no terminal reference
        assertEquals(emptySet<String>(), c.checkingOrders.value)
    }

    @Test fun `the terminal's answer in flight wins over a Not paid tapped meanwhile`() = runTest {
        val gw = SlowStatusGateway(statusMs = 3_000).apply {
            saleResults += GatewayResult.Ambiguous("Pending", null)
            statusResults += GatewayResult.Approved(TxnDetails(rrn = "R1", amountPaise = 500))
        }
        val c = coordinator(gw)
        val pay = async { c.pay("o1", amt, PayMode.UPI) }
        advanceTimeBy(1_500) // the first GetStatus is on the terminal
        val ref = journal.rows.keys.single()
        val decision = async { c.resolveManually(ref, paid = false, note = "not paid, by cashier") }
        advanceTimeBy(3_000)
        assertTrue(pay.await() is PaymentOutcome.Paid)
        assertTrue(decision.await() is PaymentOutcome.Paid) // the cashier is shown the real outcome
        assertEquals(AttemptState.SUCCESS, journal.rows[ref]!!.state)
    }

    @Test fun `a decision is read back by outcomeNow after the loop gave up`() = runTest {
        val gw = SlowStatusGateway().apply { saleResults += GatewayResult.Ambiguous("Pending", null) }
        val c = coordinator(gw)
        val out = c.pay("o1", amt, PayMode.UPI)
        assertTrue(out is PaymentOutcome.Unresolved)
        val ref = out.attempt!!.billingRef
        c.resolveManually(ref, paid = false, note = "not paid, by cashier")
        assertTrue(c.outcomeNow(ref) is PaymentOutcome.NotPaid)
    }
}
