package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.gst.InvoiceDecision
import com.bohemian.odoo.pos.core.PosLog
import com.bohemian.odoo.pos.data.LoyaltyConfirmDao
import com.bohemian.odoo.pos.data.LoyaltyConfirmEntity
import com.bohemian.odoo.pos.data.OutboxDao
import com.bohemian.odoo.pos.data.OutboxEntity
import com.bohemian.odoo.pos.pine.PayMode
import com.bohemian.odoo.pos.pine.TxnDetails
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
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
    /** ZZ Cash this sale earns (LoyaltyPoints.earned at payment start); booked after sync. */
    val loyaltyEarned: Paise = Paise.ZERO,
    /** The customer's ZZ Cash card, or null when they have none yet (Odoo creates it). */
    val loyaltyCardId: Int? = null,
    /** loyalty.program the points belong to (ZzCashService.program at payment). Null = none to book. */
    val loyaltyProgramId: Int? = null,
    /** pos.preset of the order (the config default, e.g. Pickup); null when presets are off. */
    val presetId: Int? = null,
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
    private val loyalty: LoyaltyConfirmDao? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPayloadGenerated: ((uuid: String, payload: JsonObject) -> Unit)? = null,
) {
    /** Persist first, then try once. Returns true if Odoo confirmed now. */
    suspend fun submit(sale: PaidSale): Boolean =
        submitPayload(sale.orderUuid, buildPayload(sale), sale.totals.totalIncl.value, couponData(sale))

    /**
     * Any ready sync_from_ui payload (sale or refund): persist first, then try once.
     * [couponData] is the ZZ Cash change to book with `confirm_coupon_programs` once the order exists.
     */
    suspend fun submitPayload(uuid: String, payload: JsonObject, amountPaise: Long, couponData: JsonObject? = null): Boolean {
        onPayloadGenerated?.invoke(uuid, payload)
        if (couponData != null) {
            checkNotNull(loyalty) { "loyalty journal missing" }
                .upsert(LoyaltyConfirmEntity(uuid, couponData.toString(), 0, null, clock(), 0))
        }
        outbox.upsert(OutboxEntity(uuid, payload.toString(), amountPaise, 0, null, clock(), 0))
        return trySend(uuid, payload)
    }

    /**
     * Odoo 19 never moves loyalty points inside `sync_from_ui`. The web POS books them right after
     * with `pos.order.confirm_coupon_programs` (pos_store.js `postProcessLoyalty`): it adds the
     * points earned, subtracts the reward's `points_cost` and writes `loyalty.history`. Without this
     * call a redeemed reward is never deducted and nothing is earned.
     *
     * Safe to retry: Odoo drops coupon data for an order that already has a loyalty.history line
     * (`_remove_duplicate_coupon_data`). The row stays until Odoo answers, and blocks session close (I9).
     */
    private suspend fun confirmLoyalty(row: LoyaltyConfirmEntity): Boolean = try {
        val orderId = odoo.call("pos.order", "search_read", jsonOf(
            "domain" to listOf(listOf("uuid", "=", row.orderUuid)),
            "fields" to listOf("id"),
            "limit" to 1,
        )).objects().firstOrNull()?.int("id")
        if (orderId == null) {
            loyalty!!.markFailed(row.orderUuid, "order not in Odoo yet", clock() + backoff(row.attempts))
            false
        } else {
            val data = odoo.json.parseToJsonElement(row.couponDataJson).jsonObject
            odoo.call("pos.order", "confirm_coupon_programs", jsonOf("ids" to listOf(orderId), "coupon_data" to data))
            loyalty!!.delete(row.orderUuid)
            PosLog.i("LOYALTY_CONFIRMED", "uuid" to row.orderUuid, "order" to orderId)
            true
        }
    } catch (e: OdooException) {
        loyalty!!.markFailed(row.orderUuid, e.message ?: "error", clock() + backoff(row.attempts))
        PosLog.w("LOYALTY_CONFIRM_FAILED", "uuid" to row.orderUuid, "attempt" to (row.attempts + 1), "err" to e.message)
        false
    }

    /** Loyalty rows whose order is already in Odoo (its outbox row is gone), and not being sent now. */
    private suspend fun flushLoyalty(now: Long) {
        val dao = loyalty ?: return
        val waitingOrders = outbox.due(Long.MAX_VALUE).map { it.orderUuid }.toSet()
        for (row in dao.due(now)) if (row.orderUuid !in waitingOrders && row.orderUuid !in sending) confirmLoyalty(row)
    }

    /** Called by the background loop (every 30 s) and after each sale. */
    suspend fun flushDue(): Int {
        var sent = 0
        for (e in outbox.due(clock())) {
            val payload = odoo.json.parseToJsonElement(e.payloadJson).jsonObject
            if (trySend(e.orderUuid, payload, e.attempts)) sent++
        }
        flushLoyalty(clock())
        return sent
    }

    /** Session close: try every outbox order now, ignoring backoff (the web POS syncs before closing too). */
    suspend fun flushAll(): Int {
        var sent = 0
        for (e in outbox.due(Long.MAX_VALUE)) {
            val payload = odoo.json.parseToJsonElement(e.payloadJson).jsonObject
            if (trySend(e.orderUuid, payload, e.attempts)) sent++
        }
        flushLoyalty(Long.MAX_VALUE)
        return sent
    }

    private fun backoff(attempts: Int) = minOf(15 * 60_000L, 30_000L shl minOf(attempts, 5)) // 30s → 16 min cap

    /**
     * Orders being sent right now. The 30 s background loop and the live sale can reach the same
     * order at once; only one of them sends it (and books its ZZ Cash), the other skips it.
     */
    private val sending: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private suspend fun trySend(uuid: String, payload: JsonObject, attempts: Int = 0): Boolean {
        if (!sending.add(uuid)) return false
        return try {
            odoo.call("pos.order", "sync_from_ui", payload)
            outbox.delete(uuid)
            PosLog.i("ORDER_SYNCED", "uuid" to uuid)
            loyalty?.get(uuid)?.let { confirmLoyalty(it) }
            true
        } catch (e: OdooException) {
            outbox.markFailed(uuid, e.message ?: e::class.simpleName ?: "error", clock() + backoff(attempts))
            PosLog.w("ORDER_SYNC_FAILED", "uuid" to uuid, "attempt" to (attempts + 1), "err" to e.message)
            false
        } finally {
            sending.remove(uuid)
        }
    }

    companion object {
        private fun odooUtc(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

        /** Pure: the ZZ Cash change of this sale, or null when there is none (or no program to book it on). */
        fun couponData(s: PaidSale): JsonObject? {
            val programId = s.loyaltyProgramId ?: return null
            return LoyaltyPoints.couponData(
                programId = programId,
                partnerId = s.customer?.id,
                cardId = s.redemption?.cardId ?: s.loyaltyCardId,
                earned = s.loyaltyEarned,
                spent = s.redemption?.reward?.points ?: Paise.ZERO,
                rewardCodes = listOfNotNull(s.redemption?.rewardCode),
            )
        }

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
                    "note" to LineNotes.toOdoo(l.note),
                ))
            } + (s.redemption?.let { rewardLines(it) } ?: emptyList())
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
                "preset_id" to (s.presetId ?: false),
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
         * The ZZ Cash reward as Odoo's POS sends it: one qty-1 line at a negative price per tax group,
         * flagged as a reward, all with the same `reward_identifier_code`; only the first carries the
         * `points_cost` (pos_order.js `_getRewardLineValuesDiscount`). The points themselves move in
         * `confirm_coupon_programs` after sync.
         * Line shape taken from a captured web-POS redemption (tools/payload-ref, 2026-10-23).
         */
        private fun rewardLines(r: ZzCashRedemption): List<List<Any>> {
            require(r.isPriced) { "ZZ Cash reward must be priced before sync" }
            return r.parts.mapIndexed { i, p ->
                listOf(0, 0, mapOf(
                    "uuid" to UUID.randomUUID().toString(),
                    "product_id" to r.reward.discountProductId,
                    "full_product_name" to r.reward.description,
                    "qty" to 1,
                    "price_unit" to Paise(-p.amount.value).toRupees(),
                    "discount" to 0,
                    "tax_ids" to listOf(listOf(6, 0, p.taxIds)),
                    "price_subtotal" to p.totalExcl!!.toRupees(),
                    "price_subtotal_incl" to p.totalIncl!!.toRupees(),
                    "is_reward_line" to true,
                    "reward_id" to r.reward.rewardId,
                    "coupon_id" to r.cardId,
                    "reward_identifier_code" to r.rewardCode,
                    "points_cost" to (if (i == 0) r.reward.points else Paise.ZERO).toRupees(),
                ))
            }
        }
    }
}
