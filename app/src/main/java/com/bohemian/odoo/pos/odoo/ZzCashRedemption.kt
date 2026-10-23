package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import java.security.SecureRandom

/**
 * One ZZ Cash reward applied to one sale. Odoo prices it as a negative order line, and
 * validates the points at sync (pos.order.sync_from_ui, is_reward_line / coupon_id).
 *
 * [rewardCode] is random per redemption and is kept in the sale snapshot, so a retry
 * sends the same code, never a new one.
 */
data class ZzCashRedemption(
    val cardId: Int,
    val reward: ZzCashReward,
    val rewardCode: String,
    /** Priced by Odoo's compute_all on the reward line. Null until the cart is priced. */
    val totalIncl: Paise? = null,
    val totalExcl: Paise? = null,
    /** Taxes of the cart products, so the reward is taxed like what it discounts. */
    val taxIds: List<Int> = emptyList(),
)

object ZzCashRules {
    /**
     * Rewards this card can pay for and that stay below the cart total (a sale must never
     * reach zero or go negative). The card needs whole-rupee points for the reward.
     */
    fun eligible(balance: ZzCashBalance, rewards: List<ZzCashReward>, cartTotal: Paise): List<ZzCashReward> =
        rewards.filter { it.points <= balance.redeemable && it.amount < cartTotal }

    /**
     * The cart is taxed at one rate, so the reward can carry the same taxes. Mixed-rate
     * carts would need Odoo's proportional split, which is not built yet, so they are refused.
     */
    fun singleTaxSet(productTaxIds: List<List<Int>>): List<Int>? =
        productTaxIds.map { it.sorted() }.distinct().singleOrNull()

    private const val CODE_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

    /** 9 characters, the same shape Odoo generates (e.g. "uvjen744u"). */
    fun newRewardCode(random: SecureRandom = SecureRandom()): String =
        (1..9).map { CODE_CHARS[random.nextInt(CODE_CHARS.length)] }.joinToString("")
}
