package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.gst.InvoiceDecision
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
    /**
     * account.fiscal.position applied to this order. Odoo re-derives each line's
     * `tax_ids_after_fiscal_position` from it, so an inter-state B2B sale only becomes
     * IGST in the books when this is sent.
     */
    val fiscalPositionId: Int? = null,
    /** ZZ Cash reward on this sale, sent as a negative reward line. Its amount is inside [totals]. */
    val redemption: ZzCashRedemption? = null,
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
    private val onPayloadGenerated: ((uuid: String, payload: JsonObject) -> Unit)? = null,
) {
    /** Persist first, then try once. Returns true if Odoo confirmed now. */
    suspend fun submit(sale: PaidSale): Boolean = submitPayload(sale.orderUuid, buildPayload(sale), sale.totals.totalIncl.value)

    /** Any ready sync_from_ui payload (sale or refund): persist first, then try once. */
    suspend fun submitPayload(uuid: String, payload: JsonObject, amountPaise: Long): Boolean {
        onPayloadGenerated?.invoke(uuid, payload)
        outbox.upsert(OutboxEntity(uuid, payload.toString(), amountPaise, 0, null, clock(), 0))
        return trySend(uuid, payload)
    }

    /**
     * The pos.order.access_token Odoo generated for this sale, used for the e-bill QR.
     * Null when the order is not in Odoo yet (the outbox will deliver it later).
     */
    suspend fun accessTokenFor(uuid: String): String? {
        val rows = odoo.call("pos.order", "search_read", jsonOf(
            "domain" to listOf(listOf("uuid", "=", uuid)),
            "fields" to listOf("access_token"),
            "limit" to 1,
        )).objects()
        return rows.firstOrNull()?.str("access_token")?.takeIf { it.isNotBlank() }
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

    /** Session close: try every outbox order now, ignoring backoff (the web POS syncs before closing too). */
    suspend fun flushAll(): Int {
        var sent = 0
        for (e in outbox.due(Long.MAX_VALUE)) {
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
            val lines: List<Any> = s.lines.map { l ->
                listOf(0, 0, mapOf(
                    "uuid" to l.uuid,
                    "product_id" to l.product.id,
                    "full_product_name" to l.product.name,
                    "qty" to l.qty,
                    "price_unit" to l.product.price.toRupees(),
                    "discount" to 0,
                    // Unmapped product taxes on purpose: pos.order.line.tax_ids holds the
                    // original taxes and Odoo computes tax_ids_after_fiscal_position itself.
                    "tax_ids" to listOf(listOf(6, 0, l.product.taxIds)),
                    "price_subtotal" to l.totalExcl!!.toRupees(),
                    "price_subtotal_incl" to l.totalIncl!!.toRupees(),
                ))
            } + listOfNotNull(s.redemption?.let { rewardLine(it) })
            val order = mapOf(
                "uuid" to s.orderUuid,
                "session_id" to s.sessionId,
                "state" to "paid",
                "date_order" to date,
                "partner_id" to (s.customer?.id ?: false),
                // l10n_in_pos `setPartner`: a GSTIN-carrying registered business is invoiced,
                // a consumer / unregistered / overseas partner is not.
                "to_invoice" to InvoiceDecision.toInvoice(s.customer),
                "fiscal_position_id" to (s.fiscalPositionId ?: false),
                "amount_total" to s.totals.totalIncl.toRupees(),
                "amount_tax" to s.totals.tax.toRupees(),
                "amount_paid" to s.amountPaid.toRupees(),
                "amount_return" to 0,
                "lines" to lines,
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

        /**
         * The ZZ Cash reward as Odoo's POS sends it: a qty-1 line at a negative price, flagged
         * as a reward. Odoo checks the card's points against `points_cost` at sync.
         * Shape taken from a captured web-POS redemption (tools/payload-ref, 2026-10-23).
         */
        private fun rewardLine(r: ZzCashRedemption): List<Any> {
            val incl = requireNotNull(r.totalIncl) { "ZZ Cash reward must be priced before sync" }
            val excl = requireNotNull(r.totalExcl) { "ZZ Cash reward must be priced before sync" }
            return listOf(0, 0, mapOf(
                "uuid" to UUID.randomUUID().toString(),
                "product_id" to r.reward.discountProductId,
                "full_product_name" to r.reward.description,
                "qty" to 1,
                "price_unit" to Paise(-r.reward.amount.value).toRupees(),
                "discount" to 0,
                "tax_ids" to listOf(listOf(6, 0, r.taxIds)),
                "price_subtotal" to excl.toRupees(),
                "price_subtotal_incl" to incl.toRupees(),
                "is_reward_line" to true,
                "reward_id" to r.reward.rewardId,
                "coupon_id" to r.cardId,
                "reward_identifier_code" to r.rewardCode,
                "points_cost" to r.reward.points.toRupees(),
            ))
        }
    }
}
