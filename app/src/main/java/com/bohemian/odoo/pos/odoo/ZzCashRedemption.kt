package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import java.security.SecureRandom

/**
 * One negative reward line: the share of the reward that falls on the cart lines taxed with
 * [taxIds]. Priced by Odoo's compute_all; totals are null until then.
 */
data class RewardPart(
    val taxIds: List<Int>,
    val amount: Paise,
    val totalIncl: Paise? = null,
    val totalExcl: Paise? = null,
)

/**
 * One ZZ Cash reward applied to one sale. Odoo's web POS turns it into one negative line per
 * tax group of the cart ([parts]), all carrying the same [rewardCode]; the first line carries
 * the points cost. The points move after sync with confirm_coupon_programs (OrderSyncService).
 *
 * [rewardCode] is random per redemption and is kept in the sale snapshot, so a retry
 * sends the same code, never a new one.
 */
data class ZzCashRedemption(
    val cardId: Int,
    val reward: ZzCashReward,
    val rewardCode: String,
    /** Empty until the cart is split by tax group ([ZzCashRules.split]). */
    val parts: List<RewardPart> = emptyList(),
) {
    val isPriced: Boolean get() = parts.isNotEmpty() && parts.all { it.totalIncl != null && it.totalExcl != null }
    /** Sum of the priced parts (negative). Null until every part is priced. */
    val totalIncl: Paise? get() = if (isPriced) parts.fold(Paise.ZERO) { a, p -> a + p.totalIncl!! } else null
    val totalExcl: Paise? get() = if (isPriced) parts.fold(Paise.ZERO) { a, p -> a + p.totalExcl!! } else null
}

object ZzCashRules {
    /**
     * Points this card can spend on this cart, in whole rupees. Like Odoo (pos_loyalty 19
     * `_getRealCouponPoints`; ZZ Cash applies on the current order too), the points this cart
     * [earned] count with the balance: 34.6 on the card + 401.44 earned can take ₹400 off.
     */
    fun available(balance: ZzCashBalance, earned: Paise): Paise =
        (balance.points + earned).let { Paise(it.value - it.value % 100) }

    /**
     * Rewards this card can pay for on this cart ([available]) and that stay below the cart total
     * (a sale must never reach zero or go negative).
     */
    fun eligible(balance: ZzCashBalance, earned: Paise, rewards: List<ZzCashReward>, cartTotal: Paise): List<ZzCashReward> =
        rewards.filter { it.points <= available(balance, earned) && it.amount < cartTotal }

    /**
     * Odoo's split of a per-order discount over the cart's tax groups
     * (pos_order.js `_getDiscountableOnOrder` + `_getRewardLineValuesDiscount`, 19.0): each group of
     * lines with the same taxes gets `reward × group total / cart total`, so every rate is discounted
     * in proportion and GST stays right. Groups keep the order of their first line. Each share is
     * rounded half-up to the paisa and the last group takes the remainder, so the parts always add
     * up to the reward exactly. Taxes are compared as sets (unmapped product taxes, as sent to Odoo).
     */
    fun split(reward: Paise, lines: List<CartLine>): List<RewardPart> {
        val groups = LinkedHashMap<List<Int>, Paise>()
        for (l in lines) {
            if (l.qty == 0) continue
            val incl = l.totalIncl ?: return emptyList()
            val key = l.product.taxIds.sorted()
            groups[key] = (groups[key] ?: Paise.ZERO) + incl
        }
        val cart = groups.values.fold(Paise.ZERO) { a, p -> a + p }
        if (!cart.isPositive || !reward.isPositive) return emptyList()
        var left = reward
        return groups.entries.filter { it.value.isPositive }.let { positive ->
            positive.mapIndexed { i, (taxIds, groupTotal) ->
                val share = if (i == positive.lastIndex) left else Paise(
                    java.math.BigDecimal(reward.value).multiply(java.math.BigDecimal(groupTotal.value))
                        .divide(java.math.BigDecimal(cart.value), 0, java.math.RoundingMode.HALF_UP).toLong()
                )
                left -= share
                RewardPart(taxIds, share)
            }
        }
    }

    private const val CODE_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

    /** 9 characters, the same shape Odoo generates (e.g. "uvjen744u"). */
    fun newRewardCode(random: SecureRandom = SecureRandom()): String =
        (1..9).map { CODE_CHARS[random.nextInt(CODE_CHARS.length)] }.joinToString("")
}
