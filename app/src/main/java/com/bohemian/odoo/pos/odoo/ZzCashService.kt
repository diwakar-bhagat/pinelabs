package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import kotlinx.serialization.json.JsonObject

/**
 * ZZ Cash card balance for a customer. The balance is the card's `points` (1 point = ₹1).
 * Read live from Odoo; never stored locally, so the till cannot show a stale figure for
 * long. Redemption is NOT here: Odoo's own loyalty validation applies it at sync time
 * (see docs/ZZ_CASH.md).
 */
data class ZzCashBalance(
    val partnerId: Int,
    val cardId: Int,
    val points: Paise,
) {
    /** Rewards are whole rupees, so only whole rupees of the balance can be redeemed. */
    val redeemable: Paise get() = Paise(points.value - points.value % 100)
}

class ZzCashService(private val odoo: OdooJson2Client) {

    /** Null when the customer has no ZZ Cash card. Throws on network/server errors. */
    suspend fun balance(partnerId: Int): ZzCashBalance? {
        val rows = odoo.call("loyalty.card", "search_read", jsonOf(
            "domain" to listOf(
                listOf("partner_id", "=", partnerId),
                listOf("program_id", "=", PROGRAM_ID),
            ),
            "fields" to listOf("points", "code"),
            "limit" to 1,
        )).objects()
        return fromRows(partnerId, rows)
    }

    companion object {
        /** loyalty.program id 4 = "ZZ Cash" (30 rules, 10 rewards, INR). */
        const val PROGRAM_ID = 4

        internal fun fromRows(partnerId: Int, rows: List<JsonObject>): ZzCashBalance? {
            val row = rows.firstOrNull() ?: return null
            val cardId = row.int("id") ?: return null
            // A negative balance should never come back; treat it as empty rather than
            // showing the cashier a debt they cannot redeem.
            val points = row.money("points").let { if (it.isPositive) it else Paise.ZERO }
            return ZzCashBalance(partnerId = partnerId, cardId = cardId, points = points)
        }
    }
}
