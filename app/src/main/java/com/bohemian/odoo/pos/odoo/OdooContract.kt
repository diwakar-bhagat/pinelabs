package com.bohemian.odoo.pos.odoo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The fields of Odoo the till depends on (F2, 0.7.0). Odoo Online upgrades databases in place
 * (19.0 → saas~19.x); a field the till sends that an upgrade drops makes Odoo refuse the order
 * *after* the money is taken. So when Odoo's version changes the till checks these with `fields_get`
 * and, if one is gone, stops taking payments (fail closed) until someone looks.
 *
 * [WRITES] must hold every key the sync_from_ui payloads send (sale, rewards, offers, refund): the
 * payload tests assert it with [uncovered]. All present on the live database, 2026-10-30.
 */
object OdooContract {
    val WRITES: Map<String, Set<String>> = mapOf(
        "pos.order" to setOf(
            "uuid", "session_id", "state", "date_order", "partner_id", "to_invoice", "fiscal_position_id",
            "amount_total", "amount_tax", "amount_paid", "amount_return", "preset_id", "lines", "payment_ids", "is_refund",
        ),
        "pos.order.line" to setOf(
            "uuid", "product_id", "full_product_name", "qty", "price_unit", "discount", "tax_ids", "price_subtotal",
            "price_subtotal_incl", "note", "is_reward_line", "reward_id", "coupon_id", "reward_identifier_code", "points_cost",
            "refunded_orderline_id",
        ),
        "pos.payment" to setOf(
            "uuid", "payment_method_id", "amount", "payment_date", "payment_ref_no", "transaction_id", "card_no", "card_brand",
            "payment_method_authcode", "payment_method_issuer_bank", "payment_method_payment_mode",
        ),
    )

    /** What selling cannot do without: the session, the price and taxes, the customer, the ZZ Cash card. */
    val READS: Map<String, Set<String>> = mapOf(
        "pos.config" to setOf("current_session_id", "current_session_state", "payment_method_ids", "pricelist_id", "default_fiscal_position_id"),
        "product.product" to setOf("lst_price", "taxes_id", "available_in_pos", "sale_ok", "write_date"),
        "res.partner" to setOf("phone", "phone_sanitized", "vat", "l10n_in_gst_treatment", "state_id", "property_account_position_id"),
        "loyalty.card" to setOf("points", "partner_id", "program_id"),
    )

    val REQUIRED: Map<String, Set<String>> =
        (WRITES.keys + READS.keys).associateWith { WRITES[it].orEmpty() + READS[it].orEmpty() }

    /** "pos.payment.card_no" for every key a sync_from_ui [payload] sends that [WRITES] does not list. */
    fun uncovered(payload: JsonObject): List<String> {
        val out = mutableListOf<String>()
        fun check(model: String, o: JsonObject) = o.keys.filter { it !in WRITES.getValue(model) }.forEach { out += "$model.$it" }
        fun commands(a: Any?): List<JsonObject> = (a as? JsonArray).orEmpty().mapNotNull { (it as? JsonArray)?.getOrNull(2) as? JsonObject }
        (payload["orders"] as? JsonArray).orEmpty().forEach { e ->
            val order = e.jsonObject
            check("pos.order", order)
            commands(order["lines"]).forEach { check("pos.order.line", it) }
            commands(order["payment_ids"]).forEach { check("pos.payment", it) }
        }
        return out.distinct()
    }
}

/** Runs the contract against Odoo when its version changes (start, then daily). */
class OdooContractCheck(private val odoo: OdooJson2Client) {

    sealed interface Result {
        /** Every required field is there under this version. */
        data class Ok(val version: String) : Result
        /** These fields ("pos.payment.card_no") or whole models are gone: take no more payments. */
        data class Broken(val version: String, val missing: List<String>) : Result
        /** Odoo could not be asked (offline, busy, no rights): nothing changes. */
        data object Unknown : Result
    }

    /** [verified]: the last version the contract passed under; the fields are read again only when it changed. */
    suspend fun run(verified: String?): Result {
        val version = odoo.serverVersion() ?: return Result.Unknown
        if (version == verified) return Result.Ok(version)
        val missing = mutableListOf<String>()
        for ((model, fields) in OdooContract.REQUIRED) {
            val got = try {
                odoo.call(model, "fields_get", jsonOf("attributes" to listOf("type"))).jsonObject.keys
            } catch (e: OdooException.Server) {
                // A model that no longer exists is 404 NotFound; anything else (rights, a busy Odoo) proves nothing.
                if (e.errorName == "werkzeug.exceptions.NotFound") emptySet() else return Result.Unknown
            } catch (e: OdooException) {
                return Result.Unknown
            }
            missing += fields.filter { it !in got }.sorted().map { "$model.$it" }
        }
        return if (missing.isEmpty()) Result.Ok(version) else Result.Broken(version, missing)
    }
}
