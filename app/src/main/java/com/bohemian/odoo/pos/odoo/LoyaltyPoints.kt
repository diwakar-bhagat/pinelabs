package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * One loyalty.rule of the ZZ Cash program. [validProductIds] is Odoo's own computed
 * `valid_product_ids` (category / tag / domain already resolved on the server).
 */
data class LoyaltyRule(
    val id: Int,
    val mode: String,               // "auto" | "with_code"
    val pointMode: String,          // "money" | "unit" | "order"
    val pointAmount: BigDecimal,
    val minimumAmount: Paise,
    val minimumTaxIncluded: Boolean,
    val minimumQty: Int,
    val anyProduct: Boolean,
    val validProductIds: Set<Int>,
)

/**
 * Points an order earns, the way the Odoo 19 web POS computes them
 * (`pos_loyalty/static/src/app/models/pos_order.js` `pointsForPrograms`, non-split branch):
 * per rule, `reward_point_amount` × the tax-included total of the matching lines, rounded
 * half-up to 2 decimals. Line totals come from Odoo's compute_all, so no tax is computed here (I2).
 *
 * ZZ Cash rewards are discounts of the same program: they never earn and need no
 * `_getPointsCorrection` (that only applies to free-product rewards).
 *
 * 1 ZZ Cash point = ₹1, so points are carried as [Paise] like the card balance.
 */
object LoyaltyPoints {

    fun earned(rules: List<LoyaltyRule>, lines: List<CartLine>): Paise {
        var points = BigDecimal.ZERO
        for (rule in rules) {
            if (rule.mode == "with_code") continue
            val matching = lines.filter { it.isPriced && (rule.anyProduct || it.product.id in rule.validProductIds) }
            if (!rule.anyProduct && matching.isEmpty()) continue
            val incl = matching.fold(Paise.ZERO) { a, l -> a + l.totalIncl!! }
            val excl = matching.fold(Paise.ZERO) { a, l -> a + l.totalExcl!! }
            val amountCheck = if (rule.minimumTaxIncluded) incl else excl
            if (rule.minimumAmount > amountCheck) continue
            if (matching.sumOf { it.qty } < rule.minimumQty) continue
            points += when (rule.pointMode) {
                "money" -> (rule.pointAmount * incl.toRupees()).setScale(2, RoundingMode.HALF_UP)
                "unit" -> rule.pointAmount * BigDecimal(matching.sumOf { it.qty })
                "order" -> rule.pointAmount
                else -> BigDecimal.ZERO
            }
        }
        return Paise.fromRupees(points)
    }

    /**
     * The `coupon_data` argument of `pos.order.confirm_coupon_programs`, shaped like the web POS
     * `postProcessLoyalty`. Keyed by the card id, or by a negative id when the customer has no
     * card yet (Odoo then creates it, or reuses the partner's existing card).
     * Null when nothing changes: no customer, nothing earned and nothing spent.
     */
    fun couponData(
        programId: Int,
        partnerId: Int?,
        cardId: Int?,
        earned: Paise,
        spent: Paise,
        rewardCodes: List<String>,
    ): JsonObject? {
        if (partnerId == null) return null                       // ZZ Cash is nominative
        if (earned.isZero && spent.isZero && rewardCodes.isEmpty()) return null
        val key = cardId ?: NEW_CARD_ID
        val entry = buildMap<String, Any?> {
            put("points", (earned - spent).toRupees())
            put("points_earned", earned.toRupees())
            put("points_spent", spent.toRupees())
            put("program_id", programId)
            put("coupon_id", key)
            put("barcode", false)
            put("partner_id", partnerId)
            if (rewardCodes.isNotEmpty()) put("line_codes", rewardCodes)
        }
        return jsonOf(key.toString() to entry)
    }

    /** Placeholder id for a card Odoo must create; the web POS uses negative ids the same way. */
    const val NEW_CARD_ID = -1

    internal fun ruleFromRow(r: JsonObject): LoyaltyRule? {
        val id = r.int("id") ?: return null
        val amount = (r["reward_point_amount"] as? JsonPrimitive)?.doubleOrNull ?: return null
        return LoyaltyRule(
            id = id,
            mode = r.str("mode") ?: "auto",
            pointMode = r.str("reward_point_mode") ?: "money",
            pointAmount = BigDecimal(amount.toString()),
            minimumAmount = r.money("minimum_amount"),
            minimumTaxIncluded = r.str("minimum_amount_tax_mode") != "excl",
            minimumQty = r.int("minimum_qty") ?: 0,
            anyProduct = r.bool("any_product"),
            validProductIds = r.ids("valid_product_ids").toSet(),
        )
    }

    val RULE_FIELDS = listOf(
        "mode", "reward_point_mode", "reward_point_amount", "minimum_amount",
        "minimum_amount_tax_mode", "minimum_qty", "any_product", "valid_product_ids",
    )
}
