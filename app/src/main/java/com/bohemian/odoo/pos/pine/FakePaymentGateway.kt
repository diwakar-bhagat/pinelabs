package com.bohemian.odoo.pos.pine

import com.bohemian.odoo.pos.core.Paise
import kotlinx.coroutines.delay
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Deterministic development gateway (debug builds only, Settings → Payment gateway: Fake).
 * Outcome is chosen by the last two digits of the amount in paise, like Pine's simulator:
 *
 *   ..00 → approved                ..51 → declined
 *   ..91 → ambiguous timeout; GetStatus later says APPROVED
 *   ..92 → ambiguous timeout; GetStatus later says DECLINED
 *   ..93 → ambiguous timeout; GetStatus stays ambiguous (→ UNKNOWN)
 *   anything else → approved
 */
class FakePaymentGateway(private val latencyMs: Long = 1200) : PaymentGateway {
    override val name = "FAKE"
    private val finalOutcome = ConcurrentHashMap<String, GatewayResult>()

    private fun approved(amount: Paise, mode: PayMode) = GatewayResult.Approved(
        TxnDetails(
            transactionId = UUID.randomUUID().toString().take(12),
            rrn = (100000000000L + (System.nanoTime() % 899999999999L)).toString(),
            authCode = "A" + (100000 + (System.nanoTime() % 899999)).toString(),
            last4 = if (mode == PayMode.CARD) "4242" else null,
            cardType = if (mode == PayMode.CARD) "VISA" else mode.name,
            responseCode = "00", responseMessage = "APPROVED",
            amountPaise = amount.value, simulated = true,
        )
    )

    override suspend fun sale(request: SaleRequest, onStarted: (String) -> Unit): GatewayResult {
        onStarted("fake-" + UUID.randomUUID())
        delay(latencyMs)
        val tail = (request.amount.value % 100).toInt()
        return when (tail) {
            51 -> GatewayResult.Declined("51", "INSUFFICIENT FUNDS")
            91 -> { finalOutcome[request.billingRef] = approved(request.amount, request.mode); GatewayResult.Ambiguous("Timeout", "fake timeout") }
            92 -> { finalOutcome[request.billingRef] = GatewayResult.Declined("U30", "UPI EXPIRED"); GatewayResult.Ambiguous("Timeout", "fake timeout") }
            93 -> GatewayResult.Ambiguous("Timeout", "fake timeout (status stays unknown)")
            else -> approved(request.amount, request.mode)
        }
    }

    override suspend fun getStatus(billingRef: String, amount: Paise, mode: PayMode, onStarted: (String) -> Unit): GatewayResult {
        onStarted("fake-status-" + UUID.randomUUID())
        delay(latencyMs / 2)
        return finalOutcome[billingRef] ?: GatewayResult.Ambiguous("Timeout", "fake: status unavailable")
    }

    override suspend fun void(request: VoidRequest, onStarted: (String) -> Unit): GatewayResult {
        onStarted("fake-void-" + UUID.randomUUID())
        delay(latencyMs)
        return GatewayResult.Approved(TxnDetails(responseCode = "00", responseMessage = "VOIDED", amountPaise = request.amount.value, simulated = true))
    }

    override suspend fun print(lines: List<PrintLine>, printRef: String): PrintResult {
        delay(300)
        lines.forEach { android.util.Log.d("FakePrinter", it.text) }
        return PrintResult.Printed
    }
}
