package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.core.PosLog
import com.bohemian.odoo.pos.data.OutboxDao
import com.bohemian.odoo.pos.data.OutboxEntity
import com.bohemian.odoo.pos.pine.PayMode
import com.bohemian.odoo.pos.pine.TxnDetails
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** One payment line on the Odoo order. mode == null means cash. */
data class PaymentLineData(
    val paymentMethodId: Int,
    val amount: Paise,
    val mode: PayMode?,
    val billingRef: String?,
    val details: TxnDetails?,
)

data class PaidSale(
    val orderUuid: String,
    val sessionId: Int,
    val lines: List<CartLine>,
    val totals: CartTotals,
    val customer: Customer?,
    val payments: List<PaymentLineData>,
    val paidAtMillis: Long,
) {
    val amountPaid: Paise get() = payments.fold(Paise.ZERO) { a, p -> a + p.amount }
}

/**
 * Builds the pos.order.sync_from_ui payload (same method the Odoo web POS uses) and
 * delivers it through a durable outbox (HARD RULE 7). Re-sending the same order uuid is
 * safe: Odoo ignores an already-paid order with that uuid.
 */
class OrderSyncService(
    private val odoo: OdooJson2Client,
    private val outbox: OutboxDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Persist first, then try once. Returns true if Odoo confirmed now. */
    suspend fun submit(sale: PaidSale): Boolean {
        val payload = buildPayload(sale)
        outbox.upsert(OutboxEntity(sale.orderUuid, payload.toString(), sale.totals.totalIncl.value, 0, null, clock(), 0))
        return trySend(sale.orderUuid, payload)
    }

    /** Called by the background loop (every 30 s) and after each sale. */
    suspend fun flushDue(): Int {
        var sent = 0
        for (e in outbox.due(clock())) {
            val payload = odoo.json.parseToJsonElement(e.payloadJson).jsonObject
            if (trySend(e.orderUuid, payload, e.attempts)) sent++
        }
        return sent
    }

    private suspend fun trySend(uuid: String, payload: JsonObject, attempts: Int = 0): Boolean = try {
        odoo.call("pos.order", "sync_from_ui", payload)
        outbox.delete(uuid)
        PosLog.i("ORDER_SYNCED", "uuid" to uuid)
        true
    } catch (e: OdooException) {
        val backoffMs = minOf(15 * 60_000L, 30_000L shl minOf(attempts, 5)) // 30s → 16 min cap
        outbox.markFailed(uuid, e.message ?: e::class.simpleName ?: "error", clock() + backoffMs)
        PosLog.w("ORDER_SYNC_FAILED", "uuid" to uuid, "attempt" to (attempts + 1), "err" to e.message)
        false
    }

    companion object {
        private fun odooUtc(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

        /** Pure: unit-tested in OrderPayloadTest. */
        fun buildPayload(s: PaidSale): JsonObject {
            require(s.lines.all { it.isPriced }) { "all lines must be priced by Odoo before sync" }
            require(s.amountPaid == s.totals.totalIncl) { "paid ${s.amountPaid} != total ${s.totals.totalIncl}" }
            val date = odooUtc(s.paidAtMillis)
            val order = mapOf(
                "uuid" to s.orderUuid,
                "session_id" to s.sessionId,
                "state" to "paid",
                "date_order" to date,
                "partner_id" to (s.customer?.id ?: false),
                "to_invoice" to false,
                "amount_total" to s.totals.totalIncl.toRupees(),
                "amount_tax" to s.totals.tax.toRupees(),
                "amount_paid" to s.amountPaid.toRupees(),
                "amount_return" to 0,
                "lines" to s.lines.map { l ->
                    listOf(0, 0, mapOf(
                        "uuid" to l.uuid,
                        "product_id" to l.product.id,
                        "full_product_name" to l.product.name,
                        "qty" to l.qty,
                        "price_unit" to l.product.price.toRupees(),
                        "discount" to 0,
                        "tax_ids" to listOf(listOf(6, 0, l.product.taxIds)),
                        "price_subtotal" to l.totalExcl!!.toRupees(),
                        "price_subtotal_incl" to l.totalIncl!!.toRupees(),
                    ))
                },
                "payment_ids" to s.payments.map { p ->
                    listOf(0, 0, buildMap<String, Any?> {
                        put("uuid", UUID.randomUUID().toString())
                        put("payment_method_id", p.paymentMethodId)
                        put("amount", p.amount.toRupees())
                        put("payment_date", date)
                        p.billingRef?.let { put("payment_ref_no", it) }
                        p.details?.let { d ->
                            d.transactionId?.let { put("transaction_id", it) }
                            d.last4?.let { put("card_no", it) }
                            (d.cardType ?: p.mode?.name)?.let { put("card_brand", it) }
                            d.authCode?.let { put("payment_method_authcode", it) }
                            d.acquirer?.let { put("payment_method_issuer_bank", it) }
                            p.mode?.let { put("payment_method_payment_mode", it.name) }
                        }
                    })
                },
            )
            return jsonOf("orders" to listOf(order))
        }
    }
}
