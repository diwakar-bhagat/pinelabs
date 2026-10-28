package com.bohemian.odoo.pos.odoo

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

/** What Odoo said to a coupon / gift card code typed with Enter Code. */
sealed interface CouponCodeResult {
    data class Accepted(val coupon: ScannedCoupon) : CouponCodeResult
    data class Refused(val message: String) : CouponCodeResult
}

/**
 * The store's offers, exactly as Odoo's own POS loader gives them to this till's session
 * (`pos.session.load_data`, what the web POS calls): Odoo picks the programs open to this
 * pos.config and today, so a program limited to other stores never reaches this one.
 */
class OffersService(private val odoo: OdooJson2Client) {

    suspend fun load(sessionId: Int): List<OfferProgram> {
        val data = odoo.call("pos.session", "load_data", jsonOf("ids" to listOf(sessionId), "models_to_load" to MODELS))
            as? JsonObject ?: return emptyList()
        // The web POS works out which products a cheapest / specific discount covers from the reward's
        // product domain itself (`computeDiscountProductIds`); the till asks Odoo to read the domain.
        val domains = discountDomains(data)
        val resolved = coroutineScope {
            domains.map { (rewardId, domain) ->
                async {
                    rewardId to if (isEveryProduct(domain)) null
                    else odoo.call("product.product", "search", jsonOf("domain" to domain)).jsonArray.mapNotNull { it.jsonPrimitive.intOrNull }.toSet()
                }
            }.awaitAll().toMap()
        }
        return parse(data, resolved)
    }

    /**
     * A code no program rule has: a coupon or card of this store's programs, checked by Odoo
     * (`pos.config.use_coupon_code`: exists, active, in its dates, enough points for a reward).
     */
    suspend fun useCouponCode(configId: Int, code: String, dateOrderUtc: String, partnerId: Int?): CouponCodeResult {
        val res = odoo.call("pos.config", "use_coupon_code", jsonOf(
            "ids" to listOf(configId),
            "code" to code,
            "creation_date" to dateOrderUtc,
            "partner_id" to (partnerId ?: false),
            "pricelist_id" to false,
        )) as? JsonObject ?: return CouponCodeResult.Refused("Odoo sent an answer the till cannot read")
        val payload = res["payload"] as? JsonObject
        if (!res.bool("successful")) return CouponCodeResult.Refused(payload?.str("error_message") ?: "This coupon is invalid ($code).")
        val cardId = payload?.int("coupon_id") ?: return CouponCodeResult.Refused("Odoo sent no coupon for $code")
        val programId = payload.int("program_id") ?: return CouponCodeResult.Refused("Odoo sent no program for $code")
        return CouponCodeResult.Accepted(ScannedCoupon(cardId, programId, payload.decimal("points"), code))
    }

    /**
     * Odoo's check before the charge (`pos.order.validate_coupon_programs`), for scanned coupons: each
     * still exists and covers [pointChanges] (card id → points, negative when spent). Odoo's message, or null.
     */
    suspend fun validate(pointChanges: Map<Int, BigDecimal>): String? {
        val res = odoo.call("pos.order", "validate_coupon_programs", jsonOf(
            "ids" to emptyList<Int>(),
            "point_changes" to pointChanges.mapKeys { it.key.toString() },
            "new_codes" to emptyList<String>(),
        )) as? JsonObject ?: return "unexpected answer from Odoo"
        if (res.bool("successful")) return null
        return (res["payload"] as? JsonObject)?.str("message") ?: "Odoo refused the coupon"
    }

    companion object {
        val MODELS = listOf("loyalty.program", "loyalty.rule", "loyalty.reward")

        /** The product domain of every cheapest / specific discount reward (`reward_product_domain`, a JSON string). */
        fun discountDomains(data: JsonObject): Map<Int, JsonArray> =
            ((data["loyalty.reward"] as? JsonArray).orEmpty()).mapNotNull { it as? JsonObject }
                .filter { it.str("reward_type") == "discount" && it.str("discount_applicability") in setOf("cheapest", "specific") }
                .mapNotNull { r ->
                    val domain = r.str("reward_product_domain")?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
                    (r.int("id") ?: return@mapNotNull null) to (domain ?: return@mapNotNull null)
                }.toMap()

        /** `[[1, "=", 1]]` (Odoo's "every product"), or an empty domain. */
        fun isEveryProduct(domain: JsonArray): Boolean = domain.all { leaf ->
            (leaf as? JsonArray)?.map { (it as? JsonPrimitive)?.content } == listOf("1", "=", "1")
        }

        /**
         * load_data answers per model with plain records: many2one as an id, x2many as id lists.
         * [discountProducts]: reward id → the products its domain covers, null for every product.
         */
        fun parse(data: JsonObject, discountProducts: Map<Int, Set<Int>?> = emptyMap()): List<OfferProgram> {
            fun rows(model: String) = (data[model] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            val rules = rows("loyalty.rule").mapNotNull { r ->
                OfferRule(
                    id = r.int("id") ?: return@mapNotNull null,
                    programId = r.int("program_id") ?: return@mapNotNull null,
                    mode = r.str("mode") ?: "auto",
                    code = r.str("code"),
                    pointMode = r.str("reward_point_mode") ?: "order",
                    pointAmount = r.decimal("reward_point_amount"),
                    minimumAmount = r.decimal("minimum_amount"),
                    minimumTaxIncluded = r.str("minimum_amount_tax_mode") != "excl",
                    minimumQty = r.int("minimum_qty") ?: 0,
                    anyProduct = r.bool("any_product"),
                    validProductIds = r.ids("valid_product_ids").toSet(),
                )
            }.groupBy { it.programId }
            val rewards = rows("loyalty.reward").mapNotNull { r ->
                val rewardId = r.int("id") ?: return@mapNotNull null
                val resolved = discountProducts.containsKey(rewardId)
                OfferReward(
                    id = rewardId,
                    programId = r.int("program_id") ?: return@mapNotNull null,
                    type = r.str("reward_type") ?: return@mapNotNull null,
                    description = r.str("description") ?: "Reward",
                    requiredPoints = r.decimal("required_points"),
                    discount = r.decimal("discount"),
                    discountMode = r.str("discount_mode") ?: "percent",
                    applicability = r.str("discount_applicability") ?: "order",
                    discountProductIds = r.ids("all_discount_product_ids").toSet() + (discountProducts[rewardId] ?: emptySet()),
                    discountMaxAmount = r.decimal("discount_max_amount"),
                    discountLineProductId = r.int("discount_line_product_id") ?: return@mapNotNull null,
                    rewardProductIds = r.ids("reward_product_ids").ifEmpty { listOfNotNull(r.int("reward_product_id")) },
                    rewardProductQty = (r.int("reward_product_qty") ?: 1).coerceAtLeast(1),
                    multiProduct = r.bool("multi_product"),
                    isGlobalDiscount = r.bool("is_global_discount"),
                    clearWallet = r.bool("clear_wallet"),
                    discountAllProducts = resolved && discountProducts[rewardId] == null,
                )
            }.groupBy { it.programId }
            return rows("loyalty.program").mapNotNull { p ->
                val id = p.int("id") ?: return@mapNotNull null
                OfferProgram(
                    id = id,
                    name = p.str("name") ?: "Offer",
                    type = p.str("program_type") ?: return@mapNotNull null,
                    trigger = p.str("trigger") ?: "auto",
                    appliesOn = p.str("applies_on") ?: "current",
                    nominative = p.bool("is_nominative"),
                    dateFrom = p.str("date_from")?.take(10),
                    dateTo = p.str("date_to")?.take(10),
                    limitUsage = p.bool("limit_usage"),
                    maxUsage = p.int("max_usage") ?: 0,
                    totalOrderCount = p.int("total_order_count") ?: 0,
                    pricelistIds = p.ids("pricelist_ids"),
                    // In Odoo's order (rule_ids / reward_ids), which is the order the web POS walks them.
                    rules = p.ids("rule_ids").mapNotNull { rid -> rules[id]?.firstOrNull { it.id == rid } },
                    rewards = p.ids("reward_ids").mapNotNull { rid -> rewards[id]?.firstOrNull { it.id == rid } },
                )
            }
        }

        private fun JsonObject.decimal(key: String): BigDecimal =
            (this[key] as? JsonPrimitive)?.doubleOrNull?.let { BigDecimal(it.toString()) } ?: BigDecimal.ZERO
    }
}
