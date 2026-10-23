package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.core.sum
import com.bohemian.odoo.pos.payment.VoidAttempt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** A pos.order.line of the sale being refunded, as Odoo stored it. */
data class OdooOrderLine(
    val id: Int,
    val productId: Int,
    val name: String,
    val qty: Int,
    val priceUnit: Paise,
    val discount: Int,
    val taxIds: List<Int>,
    val subtotalExcl: Paise,
    val subtotalIncl: Paise,
    val refundedQty: Int,
    val isRewardLine: Boolean,
)

data class OdooOrder(
    val id: Int,
    val uuid: String,
    val partnerId: Int?,
    val fiscalPositionId: Int?,
    val toInvoice: Boolean,
    val isRefund: Boolean,
    val amountTotal: Paise,
    val lines: List<OdooOrderLine>,
)

sealed interface RefundResult {
    /** Refund order is in Odoo now, or durably queued in the outbox (HARD RULE 7). */
    data class Recorded(val refundUuid: String, val syncedNow: Boolean) : RefundResult
    /** The till must not build this refund; a supervisor does it in Odoo. */
    data class NotEligible(val reason: String) : RefundResult
}

/**
 * Phase 4c: the Odoo side of a terminal Void — a full refund order sent through
 * pos.order.sync_from_ui, the way the Odoo 19 web POS ticket screen builds one
 * (point_of_sale/static/src/app/screens/ticket_screen/ticket_screen.js):
 * negative qty per line, `refunded_orderline_id` = original line id, `is_refund`,
 * same taxes / fiscal position / partner, negative payment on the terminal method.
 *
 * Lines come from Odoo's own record of the sale, never from the app's cart (I2).
 * The refund uuid is derived from the void's billing ref, so a retry is deduplicated
 * by Odoo (`_get_open_order` searches by uuid) instead of creating a second refund.
 */
class RefundService(
    private val odoo: OdooJson2Client,
    private val orderSync: OrderSyncService,
    private val terminalPaymentMethodId: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun refundVoidedSale(void: VoidAttempt, sessionId: Int?, cardLast4: String?): RefundResult {
        if (sessionId == null) return RefundResult.NotEligible("No open session — open one, then retry the refund")
        val order = loadOrder(void.orderUuid)
            ?: return RefundResult.NotEligible("Sale ${void.orderUuid.take(8)} is not in Odoo yet — wait for it to sync, then retry")
        eligibility(order, void.amount)?.let { return RefundResult.NotEligible(it) }
        val payload = buildPayload(order, void, sessionId, terminalPaymentMethodId(), cardLast4, clock())
        val uuid = refundUuid(void.billingRef)
        val synced = orderSync.submitPayload(uuid, payload, -void.amount.value)
        return RefundResult.Recorded(uuid, synced)
    }

    private suspend fun loadOrder(uuid: String): OdooOrder? {
        val o = odoo.call("pos.order", "search_read", jsonOf(
            "domain" to listOf(listOf("uuid", "=", uuid)),
            "fields" to listOf("id", "uuid", "partner_id", "fiscal_position_id", "to_invoice", "is_refund", "amount_total", "lines"),
            "limit" to 1,
        )).objects().firstOrNull() ?: return null
        val lines = odoo.call("pos.order.line", "read", jsonOf(
            "ids" to o.ids("lines"),
            "fields" to LINE_FIELDS,
        )).objects()
        return parseOrder(o, lines)
    }

    companion object {
        internal val LINE_FIELDS = listOf(
            "id", "product_id", "full_product_name", "qty", "price_unit", "discount", "tax_ids",
            "price_subtotal", "price_subtotal_incl", "refunded_qty", "is_reward_line",
        )

        /** Same void → same refund order uuid, forever. */
        fun refundUuid(voidBillingRef: String): String =
            UUID.nameUUIDFromBytes("bohemian-pos:void-refund:$voidBillingRef".toByteArray()).toString()

        private fun childUuid(parent: String, key: String): String =
            UUID.nameUUIDFromBytes("$parent:$key".toByteArray()).toString()

        private fun JsonObject.wholeNumber(key: String): Int? =
            (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it == Math.floor(it) && !it.isInfinite() }?.toInt()

        internal fun parseOrder(o: JsonObject, lines: List<JsonObject>) = OdooOrder(
            id = o.int("id")!!,
            uuid = o.str("uuid") ?: "",
            partnerId = o.m2oId("partner_id"),
            fiscalPositionId = o.m2oId("fiscal_position_id"),
            toInvoice = o.bool("to_invoice"),
            isRefund = o.bool("is_refund"),
            amountTotal = o.money("amount_total"),
            lines = lines.map { l ->
                OdooOrderLine(
                    id = l.int("id")!!,
                    productId = l.m2oId("product_id") ?: 0,
                    name = l.str("full_product_name") ?: "",
                    qty = l.wholeNumber("qty") ?: -1,           // -1 = not a whole number → refused
                    priceUnit = l.money("price_unit"),
                    discount = l.wholeNumber("discount") ?: -1,
                    taxIds = l.ids("tax_ids"),
                    subtotalExcl = l.money("price_subtotal"),
                    subtotalIncl = l.money("price_subtotal_incl"),
                    refundedQty = l.wholeNumber("refunded_qty") ?: -1,
                    isRewardLine = l.bool("is_reward_line"),
                )
            },
        )

        /** null = the till may refund this sale in full; otherwise why a supervisor must. */
        internal fun eligibility(o: OdooOrder, voidAmount: Paise): String? = when {
            o.isRefund -> "This Odoo order is itself a refund"
            o.lines.isEmpty() -> "The Odoo order has no lines"
            o.lines.any { it.isRewardLine } -> "ZZ Cash was redeemed on this bill — refund it in Odoo so the points are restored"
            o.lines.any { it.refundedQty != 0 } -> "Part of this bill was already refunded in Odoo — finish the refund there"
            o.lines.any { it.qty <= 0 || it.discount < 0 || it.productId == 0 } -> "This bill has lines the till cannot refund — refund it in Odoo"
            o.lines.map { it.subtotalIncl }.sum() != o.amountTotal -> "Odoo line totals do not add up to the bill — refund it in Odoo"
            // I6 for refunds: the card was voided for exactly this bill, or it was a split bill.
            o.amountTotal != voidAmount ->
                "Bill total ${o.amountTotal.format()} ≠ voided card amount ${voidAmount.format()} (split payment?) — refund it in Odoo"
            else -> null
        }

        private fun odooUtc(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

        /** Pure: unit-tested in RefundPayloadTest. Call only after [eligibility] returned null. */
        fun buildPayload(o: OdooOrder, void: VoidAttempt, sessionId: Int, paymentMethodId: Int, cardLast4: String?, nowMillis: Long): JsonObject {
            val uuid = refundUuid(void.billingRef)
            val date = odooUtc(nowMillis)
            val incl = o.lines.map { it.subtotalIncl }.sum()
            val excl = o.lines.map { it.subtotalExcl }.sum()
            val lines = o.lines.map { l ->
                listOf(0, 0, mapOf(
                    "uuid" to childUuid(uuid, "line:${l.id}"),
                    "product_id" to l.productId,
                    "full_product_name" to l.name,
                    "qty" to -l.qty,
                    "price_unit" to l.priceUnit.toRupees(),
                    "discount" to l.discount,
                    "tax_ids" to listOf(listOf(6, 0, l.taxIds)),
                    "price_subtotal" to Paise(-l.subtotalExcl.value).toRupees(),
                    "price_subtotal_incl" to Paise(-l.subtotalIncl.value).toRupees(),
                    "refunded_orderline_id" to l.id,
                ))
            }
            val order = mapOf(
                "uuid" to uuid,
                "session_id" to sessionId,
                "state" to "paid",
                "date_order" to date,
                "is_refund" to true,
                "partner_id" to (o.partnerId ?: false),
                "to_invoice" to o.toInvoice,
                "fiscal_position_id" to (o.fiscalPositionId ?: false),
                "amount_total" to Paise(-incl.value).toRupees(),
                "amount_tax" to Paise(-(incl - excl).value).toRupees(),
                "amount_paid" to Paise(-incl.value).toRupees(),
                "amount_return" to 0,
                "lines" to lines,
                "payment_ids" to listOf(listOf(0, 0, buildMap<String, Any?> {
                    put("uuid", childUuid(uuid, "payment"))
                    put("payment_method_id", paymentMethodId)
                    put("amount", Paise(-incl.value).toRupees())
                    put("payment_date", date)
                    put("payment_ref_no", void.billingRef)
                    void.details?.transactionId?.let { put("transaction_id", it) }
                    cardLast4?.let { put("card_no", it) }
                    put("payment_method_payment_mode", "CARD")
                })),
            )
            return jsonOf("orders" to listOf(order))
        }
    }
}
