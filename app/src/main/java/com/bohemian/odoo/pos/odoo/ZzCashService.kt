package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.core.PosLog
import kotlinx.serialization.json.JsonObject

/**
 * ZZ Cash card balance for a customer. The balance is the card's `points` (1 point = ₹1).
 * Read live from Odoo; never stored locally, so the till cannot show a stale figure for
 * long. Redemption itself is validated by Odoo at sync time (see docs/ZZ_CASH.md).
 */
data class ZzCashBalance(
    val partnerId: Int,
    val cardId: Int,
    val points: Paise,
) {
    /** Rewards are whole rupees, so only whole rupees of the balance can be redeemed. */
    val redeemable: Paise get() = Paise(points.value - points.value % 100)
}

/**
 * A ZZ Cash reward from loyalty.reward: "₹N off", costs N points. [discountProductId] is
 * the product Odoo puts on the reward line (loyalty.reward.discount_line_product_id).
 */
data class ZzCashReward(
    val rewardId: Int,
    val description: String,
    val points: Paise,
    val amount: Paise,
    val discountProductId: Int,
)

/** The loyalty.program this till earns and redeems ZZ Cash on, found in Odoo (never a fixed id). */
data class ZzCashProgram(val id: Int, val name: String)

class ZzCashService(private val odoo: OdooJson2Client) {

    /**
     * The one active POS loyalty program open to [config], as the web POS loads it
     * (pos_loyalty: pos_ok, and pos_config_ids empty or containing the config). Null when
     * there is none, or more than one: the till cannot tell which card the cashier means.
     */
    suspend fun program(config: PosConfig): ZzCashProgram? {
        val rows = odoo.call("loyalty.program", "search_read", jsonOf(
            "domain" to listOf(
                listOf("pos_ok", "=", true),
                listOf("program_type", "=", "loyalty"),
                listOf("company_id", "in", listOfNotNull<Any>(config.companyId) + false),
                "|",
                listOf("pos_config_ids", "=", false),
                listOf("pos_config_ids", "in", listOf(config.id)),
            ),
            "fields" to listOf("name"),
            "limit" to 2,
        )).objects()
        return fromProgramRows(rows)
    }

    /** Null when the customer has no card in [programId]. Throws on network/server errors. */
    suspend fun balance(partnerId: Int, programId: Int): ZzCashBalance? {
        val rows = odoo.call("loyalty.card", "search_read", jsonOf(
            "domain" to listOf(
                listOf("partner_id", "=", partnerId),
                listOf("program_id", "=", programId),
            ),
            "fields" to listOf("points", "code"),
            "order" to "id", // the same card every time, should a customer ever have two
            "limit" to 2,
        )).objects()
        if (rows.size > 1) PosLog.w("ZZ_CASH_TWO_CARDS", "partner" to partnerId, "program" to programId)
        return fromRows(partnerId, rows)
    }

    /**
     * The "₹N off per order" rewards the till can apply, cheapest first. Only rewards
     * whose cost equals their value are accepted, so the rule "1 point = ₹1" holds.
     */
    suspend fun rewards(programId: Int): List<ZzCashReward> {
        val rows = odoo.call("loyalty.reward", "search_read", jsonOf(
            "domain" to listOf(
                listOf("program_id", "=", programId),
                listOf("reward_type", "=", "discount"),
                listOf("discount_mode", "=", "per_order"),
                listOf("discount_applicability", "=", "order"),
            ),
            "fields" to listOf("description", "required_points", "discount", "discount_line_product_id"),
            "limit" to 50,
        )).objects()
        return fromRewardRows(rows)
    }

    /** Earning rules of the program, for the "+N points" preview and the post-sync confirm. */
    suspend fun rules(programId: Int): List<LoyaltyRule> =
        odoo.call("loyalty.rule", "search_read", jsonOf(
            "domain" to listOf(listOf("program_id", "=", programId)),
            "fields" to LoyaltyPoints.RULE_FIELDS,
        )).objects().mapNotNull { LoyaltyPoints.ruleFromRow(it) }

    /**
     * Odoo's pre-payment check, as the web POS calls it before validating an order
     * (`pos.order.validate_coupon_programs(point_changes, new_codes)`, pos_loyalty 19.0):
     * the card still exists, is active and still covers the order. The change is the net one,
     * points [earned] by this order minus the reward's [spent] points, as the web POS sends it;
     * Odoo refuses when the card holds less than `−change`. Returns Odoo's message when it
     * refuses, or null when the reward can be charged.
     */
    suspend fun validateRedemption(cardId: Int, earned: Paise, spent: Paise): String? {
        val res = odoo.call("pos.order", "validate_coupon_programs", jsonOf(
            "ids" to emptyList<Int>(),
            "point_changes" to pointChanges(cardId, earned, spent),
            "new_codes" to emptyList<String>(),
        )) as? JsonObject ?: return "unexpected answer from Odoo"
        if (res.bool("successful")) return null
        return (res["payload"] as? JsonObject)?.str("message") ?: "Odoo refused the reward"
    }

    companion object {
        internal fun fromProgramRows(rows: List<JsonObject>): ZzCashProgram? {
            val row = rows.singleOrNull() ?: return null
            return ZzCashProgram(row.int("id") ?: return null, row.str("name") ?: "ZZ Cash")
        }

        internal fun fromRows(partnerId: Int, rows: List<JsonObject>): ZzCashBalance? {
            val row = rows.firstOrNull() ?: return null
            val cardId = row.int("id") ?: return null
            // A negative balance should never come back; treat it as empty rather than
            // showing the cashier a debt they cannot redeem.
            val points = row.money("points").let { if (it.isPositive) it else Paise.ZERO }
            return ZzCashBalance(partnerId = partnerId, cardId = cardId, points = points)
        }

        /** `point_changes` of validate_coupon_programs: card id → earned − spent, in points (rupees). */
        internal fun pointChanges(cardId: Int, earned: Paise, spent: Paise): Map<String, Any> =
            mapOf(cardId.toString() to (earned - spent).toRupees())

        internal fun fromRewardRows(rows: List<JsonObject>): List<ZzCashReward> = rows.mapNotNull { r ->
            val id = r.int("id") ?: return@mapNotNull null
            val productId = r.m2oId("discount_line_product_id") ?: return@mapNotNull null
            val points = r.money("required_points")
            val amount = r.money("discount")
            if (!amount.isPositive || points != amount) return@mapNotNull null
            ZzCashReward(id, r.str("description") ?: "", points, amount, productId)
        }.sortedBy { it.points.value }
    }
}
