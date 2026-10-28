package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

// Odoo 19 pos_loyalty, ported (pos_order.js / pos_store.js): which programs apply to a bill, the
// points each earns, which rewards can be claimed, which apply by themselves, and the reward lines
// they put on the bill. Pure: no Odoo calls, no clock. Lines are priced by Odoo afterwards (I2).
// Not ported: gift cards and eWallets (payment programs), points split into coupons for a next
// order, pricelists, combo products. None is used on the till's stores today.

data class OfferRule(
    val id: Int,
    val programId: Int,
    /** "auto" or "with_code" (then [code] must be entered on the bill). */
    val mode: String,
    val code: String?,
    /** "order", "money" or "unit". */
    val pointMode: String,
    val pointAmount: BigDecimal,
    val minimumAmount: BigDecimal,
    val minimumTaxIncluded: Boolean,
    val minimumQty: Int,
    val anyProduct: Boolean,
    /** Odoo's own `valid_product_ids` (categories, tags and domains resolved on the server). */
    val validProductIds: Set<Int>,
)

data class OfferReward(
    val id: Int,
    val programId: Int,
    /** "discount" or "product". */
    val type: String,
    val description: String,
    val requiredPoints: BigDecimal,
    val discount: BigDecimal,
    /** "percent", "per_order" or "per_point". */
    val discountMode: String,
    /** "order", "cheapest" or "specific". */
    val applicability: String,
    /** `all_discount_product_ids`: what a cheapest / specific discount may take off. */
    val discountProductIds: Set<Int>,
    /** 0 = no cap. */
    val discountMaxAmount: BigDecimal,
    /** The product Odoo puts on the reward line. */
    val discountLineProductId: Int,
    /** Free items the reward offers (one, or several to pick from). */
    val rewardProductIds: List<Int>,
    val rewardProductQty: Int,
    val multiProduct: Boolean,
    /** A whole-bill discount: only one per bill, the larger one wins. */
    val isGlobalDiscount: Boolean,
    val clearWallet: Boolean,
    /** The reward's product domain is "every product" (`[[1, "=", 1]]`): [discountProductIds] is not listed. */
    val discountAllProducts: Boolean = false,
) {
    /** May this reward take money off [productId]? */
    fun discounts(productId: Int?): Boolean = productId != null && (discountAllProducts || productId in discountProductIds)
}

data class OfferProgram(
    val id: Int,
    val name: String,
    /** loyalty, promotion, promo_code, coupons, buy_x_get_y, next_order_coupons, gift_card, ewallet. */
    val type: String,
    /** "auto" or "with_code". */
    val trigger: String,
    /** "current", "future" or "both". */
    val appliesOn: String,
    val nominative: Boolean,
    /** "yyyy-MM-dd" in the Odoo user's time zone; null = open. */
    val dateFrom: String?,
    val dateTo: String?,
    val limitUsage: Boolean,
    val maxUsage: Int,
    val totalOrderCount: Int,
    val pricelistIds: List<Int>,
    val rules: List<OfferRule>,
    val rewards: List<OfferReward>,
) {
    /** What the till can run: gift cards, eWallets and coupons for a next order stay with the web POS. */
    val supported: Boolean get() = type !in PAYMENT_TYPES && appliesOn != "future"

    companion object {
        val PAYMENT_TYPES = setOf("gift_card", "ewallet")
    }
}

/** The customer's card in a nominative program (ZZ Cash): its id and balance. */
data class CardBalance(val cardId: Int, val programId: Int, val points: BigDecimal)

/** A coupon / card code entered with Enter Code and accepted by Odoo (`pos.config.use_coupon_code`). */
data class ScannedCoupon(val cardId: Int, val programId: Int, val points: BigDecimal, val code: String)

/** A reward on the bill: chosen by the cashier, or applied by Odoo's rules ([auto]). */
data class Claim(
    val rewardId: Int,
    /** The card it spends from: the customer's card, a scanned coupon, or the bill's own coupon (negative). */
    val couponKey: Int,
    /** The free item picked, for a reward that offers several. */
    val productId: Int? = null,
    /** `reward_identifier_code`, kept for the life of the bill so a retry sends the same one. */
    val code: String,
    val auto: Boolean = false,
)

/** What the cashier did to the bill's offers: codes entered, rewards claimed or removed, coupons scanned. */
data class OfferState(
    /** Rule ids whose code was entered (Odoo's `codeActivatedProgramRules`). */
    val codes: Set<Int> = emptySet(),
    val claims: List<Claim> = emptyList(),
    /** Rewards the cashier took off: never applied by themselves again on this bill. */
    val disabled: Set<Int> = emptySet(),
    val scanned: List<ScannedCoupon> = emptyList(),
)

/** One reward line, before Odoo prices it: [unitPrice] is negative and tax-included. */
data class OfferLine(
    val rewardId: Int,
    val programId: Int,
    val couponKey: Int,
    val code: String,
    val productId: Int,
    val name: String,
    val unitPrice: Paise,
    val qty: Int,
    val taxIds: List<Int>,
    val pointsCost: BigDecimal,
    /** The free item a product reward gives. */
    val rewardProductId: Int? = null,
) {
    val amount: Paise get() = unitPrice * qty
}

/** Points a program gives on this bill, on its coupon or card ([couponKey]). */
data class CouponChange(val programId: Int, val couponKey: Int, val points: BigDecimal)

/** A reward the bill can take now; for a reward with several free items, [productChoices] are those in the cart. */
data class Claimable(
    val reward: OfferReward,
    val program: OfferProgram,
    val couponKey: Int,
    val productId: Int?,
    val productChoices: List<Int> = emptyList(),
)

data class OfferResult(
    val lines: List<OfferLine>,
    val coupons: List<CouponChange>,
    /** Rewards that could still be added (not on the bill yet). */
    val claimable: List<Claimable>,
    /** The claims that stand, with the ones Odoo's rules added; keep them as the new state. */
    val claims: List<Claim>,
    /** Why a reward the cashier chose was taken off ("ZZ Note ₹200: …"). */
    val messages: List<String>,
)

/** A reward line priced by Odoo (compute_all), as it goes to the order. */
data class PricedOffer(val line: OfferLine, val totalIncl: Paise, val totalExcl: Paise)

/** What booking the points needs to know of a program. */
data class OfferProgramInfo(val id: Int, val type: String, val appliesOn: String, val nominative: Boolean, val dateTo: String?) {
    companion object {
        fun of(p: OfferProgram) = OfferProgramInfo(p.id, p.type, p.appliesOn, p.nominative, p.dateTo)
    }
}

/**
 * The offers on a paid sale, ZZ Cash aside (it books on its own): the reward lines, the points each
 * program gives on this bill, and how each program books them.
 */
data class AppliedOffers(val lines: List<PricedOffer>, val coupons: List<CouponChange>, val programs: List<OfferProgramInfo>) {
    val totalIncl: Paise get() = lines.fold(Paise.ZERO) { a, l -> a + l.totalIncl }
    val totalExcl: Paise get() = lines.fold(Paise.ZERO) { a, l -> a + l.totalExcl }

    /**
     * `coupon_data` entries of `confirm_coupon_programs`, as the web POS builds them (pos_store.js
     * `postProcessLoyalty`): per coupon the points earned, minus the points its reward lines cost, with
     * their `line_codes` so Odoo links the lines to the coupon it creates. A program that only acts on
     * this bill is booked only when one of its rewards was used.
     */
    fun couponData(partnerId: Int?): Map<String, Map<String, Any?>> {
        val info = programs.associateBy { it.id }
        val entries = LinkedHashMap<Int, MutableMap<String, Any?>>()
        fun entry(programId: Int, key: Int, points: BigDecimal) = linkedMapOf<String, Any?>(
            "points" to points, "points_earned" to points, "points_spent" to BigDecimal.ZERO,
            "program_id" to programId, "coupon_id" to key, "barcode" to false,
        ).also { e ->
            val p = info[programId]
            if (p != null && (p.nominative || p.type == "next_order_coupons") && partnerId != null) e["partner_id"] = partnerId
            if (p != null && p.type != "loyalty" && p.dateTo != null) e["expiration_date"] = p.dateTo
        }
        for (c in coupons) entries[c.couponKey] = entry(c.programId, c.couponKey, c.points)
        for (l in lines.map { it.line }) {
            val e = entries.getOrPut(l.couponKey) { entry(l.programId, l.couponKey, BigDecimal.ZERO) }
            @Suppress("UNCHECKED_CAST")
            val codes = e.getOrPut("line_codes") { mutableListOf<String>() } as MutableList<String>
            if (l.code !in codes) codes += l.code
            e["points"] = (e["points"] as BigDecimal) - l.pointsCost
            e["points_spent"] = (e["points_spent"] as BigDecimal) + l.pointsCost
        }
        return entries.filter { (_, e) -> info[e["program_id"] as Int]?.appliesOn != "current" || e.containsKey("line_codes") }
            .entries.associate { (k, v) -> k.toString() to v }
    }
}

data class OfferInput(
    val programs: List<OfferProgram>,
    /** The cart, priced by Odoo. */
    val lines: List<CartLine>,
    val partnerId: Int?,
    /** "yyyy-MM-dd" in the Odoo user's time zone. */
    val today: String,
    val cards: List<CardBalance> = emptyList(),
    /** The line the cashier is on: a reward with several free items gives this one (Odoo's selected line). */
    val selectedProductId: Int? = null,
)

object Offers {

    private const val MAX_PASSES = 4
    private val MC = MathContext(20, RoundingMode.HALF_UP)
    private val ZERO = BigDecimal.ZERO
    private val ONE = BigDecimal.ONE
    private val HUNDRED = BigDecimal(100)

    /**
     * The bill's offers after any change (Odoo's `updateRewards`): points per program, the claims that
     * still stand, the rewards Odoo applies by itself (a program with one reward), and every reward
     * line recomputed in Odoo's order (free items first, then discounts). Repeated until stable,
     * since reward lines change points and points change rewards.
     */
    fun evaluate(input: OfferInput, state: OfferState, newCode: () -> String): OfferResult {
        val e = Engine(input, state)
        var claims = state.claims
        var lines = emptyList<OfferLine>()
        val messages = linkedSetOf<String>()
        for (pass in 1..MAX_PASSES) {
            val coupons = e.coupons(e.points(lines))
            val kept = claims.filter { c -> coupons.any { it.couponKey == c.couponKey } || state.scanned.any { it.cardId == c.couponKey } }
            if (pass == 1) (claims - kept.toSet()).filter { !it.auto }.forEach { c ->
                messages += "${e.reward(c.rewardId)?.description ?: "Reward"}: the bill no longer qualifies"
            }
            val auto = e.claimable(coupons, lines, auto = true)
                .filter { cl ->
                    cl.program.rewards.size == 1 && !cl.program.nominative && (cl.reward.type != "product" || cl.productId != null) &&
                        kept.none { it.rewardId == cl.reward.id && it.couponKey == cl.couponKey }
                }
                .map { Claim(it.reward.id, it.couponKey, it.productId, newCode(), auto = true) }
            val all = kept + auto
            val ordered = all.filter { e.reward(it.rewardId)?.type == "product" } + all.filter { e.reward(it.rewardId)?.type != "product" }
            val applied = e.applyAll(ordered, coupons)
            messages += applied.messages
            val stable = applied.lines == lines && applied.claims == claims
            lines = applied.lines
            claims = applied.claims
            if (stable) break
        }
        val coupons = e.coupons(e.points(lines))
        val claimable = e.claimable(coupons, lines, auto = false).filter { cl -> lines.none { it.rewardId == cl.reward.id } }
        return OfferResult(lines, coupons, claimable, claims, messages.toList())
    }

    /**
     * Enter Code, for a code of a program rule (Odoo `activateCode`): exact match first, then any
     * case. Returns the rule, or Odoo's refusal. Coupon codes that match no rule go to Odoo
     * (`pos.config.use_coupon_code`).
     */
    fun ruleForCode(programs: List<OfferProgram>, code: String, today: String, state: OfferState): CodeCheck {
        val rules = programs.flatMap { p -> p.rules.map { p to it } }.filter { it.second.mode == "with_code" && !it.second.code.isNullOrBlank() }
        val hit = rules.firstOrNull { it.second.code == code } ?: rules.firstOrNull { it.second.code.equals(code, ignoreCase = true) }
            ?: return CodeCheck.NoRule
        val (program, rule) = hit
        return when {
            !program.supported -> CodeCheck.Refused("${program.name} is not supported on this till yet. Use the web POS.")
            program.dateFrom != null && today < program.dateFrom -> CodeCheck.Refused("That promo code program is not yet valid.")
            program.dateTo != null && today > program.dateTo -> CodeCheck.Refused("That promo code program is expired.")
            program.pricelistIds.isNotEmpty() -> CodeCheck.Refused("That promo code program requires a specific pricelist.")
            program.limitUsage && program.totalOrderCount >= program.maxUsage -> CodeCheck.Refused("That promo code program has been used up.")
            rule.id in state.codes -> CodeCheck.Refused("That promo code program has already been activated.")
            else -> CodeCheck.Rule(program, rule)
        }
    }

    sealed interface CodeCheck {
        data class Rule(val program: OfferProgram, val rule: OfferRule) : CodeCheck
        data class Refused(val message: String) : CodeCheck
        data object NoRule : CodeCheck
    }

    /**
     * The bill's own coupon of a program (Odoo creates it in `confirm_coupon_programs`): a negative id,
     * apart from the -1 the ZZ Cash booking uses for a customer's first card.
     */
    fun newCouponKey(programId: Int): Int = -(1000 + programId)

    /** Buy [n] take [m]: how many of [numberItems] are free (pos_order.js `computeFreeQuantity`). */
    internal fun computeFreeQuantity(numberItems: Double, n: Double, m: Double): Int {
        val factor = kotlin.math.truncate(numberItems / (n + m))
        val free = factor * m
        val charged = numberItems - free
        val x = (factor + 1) * n
        val y = x + (factor + 1) * m
        val adjustment = if (x <= charged && charged < y) charged - x else 0.0
        return kotlin.math.floor(free + adjustment).toInt()
    }

    /** One bill line as the rules see it: a cart line, or a reward line already on the bill. */
    private data class L(
        val key: String,
        val productId: Int,
        val qty: Int,
        /** price_unit, tax-included (the till's taxes are price-included GST). */
        val unit: BigDecimal,
        val incl: BigDecimal,
        val excl: BigDecimal,
        val taxKey: List<Int>,
        val reward: OfferLine? = null,
    ) {
        /** Odoo `basePrice`: qty × price_unit. */
        val base: BigDecimal get() = unit.multiply(BigDecimal(qty))
    }

    private class Applied(val lines: List<OfferLine>, val claims: List<Claim>, val messages: List<String>)

    private class Points(val byProgram: Map<Int, BigDecimal>, val countedRules: Map<Int, List<Int>>)

    private class Engine(val input: OfferInput, val state: OfferState) {
        val programs: Map<Int, OfferProgram> = input.programs.associateBy { it.id }
        private val rewards: Map<Int, OfferReward> = input.programs.flatMap { it.rewards }.associateBy { it.id }
        fun reward(id: Int) = rewards[id]

        val regular: List<L> = input.lines.filter { it.isPriced && it.qty != 0 }.map {
            L(it.uuid, it.product.id, it.qty, it.product.price.toRupees(), it.totalIncl!!.toRupees(), it.totalExcl!!.toRupees(),
                it.product.taxIds.sorted())
        }

        /** Tax-excluded share per tax group, for the excluded value of reward lines (minimum checks only). */
        private val exclShare: Map<List<Int>, BigDecimal> = regular.groupBy { it.taxKey }.mapValues { (_, ls) ->
            val incl = ls.sumOf { it.incl }
            if (incl.signum() == 0) ONE else ls.sumOf { it.excl }.divide(incl, MC)
        }

        private fun rewardL(r: OfferLine, i: Int): L {
            val incl = r.amount.toRupees()
            return L("${r.code}#$i", r.productId, r.qty, r.unitPrice.toRupees(), incl,
                incl.multiply(exclShare[r.taxIds.sorted()] ?: ONE, MC), r.taxIds.sorted(), r)
        }

        private fun all(lines: List<OfferLine>): List<L> = regular + lines.mapIndexed { i, r -> rewardL(r, i) }
        private fun totalIncl(lines: List<OfferLine>) = regular.sumOf { it.incl } + lines.sumOf { it.amount.toRupees() }
        private fun totalExcl(lines: List<OfferLine>) = all(lines).sumOf { it.excl }

        private fun isApplicable(p: OfferProgram): Boolean {
            if (!p.supported) return false
            if (p.trigger == "auto" && p.rules.none { it.mode == "auto" || it.id in state.codes }) return false
            if (p.trigger == "with_code" && p.rules.none { it.id in state.codes }) return false
            if (p.nominative && input.partnerId == null) return false
            if (p.dateFrom != null && input.today < p.dateFrom) return false
            if (p.dateTo != null && input.today > p.dateTo) return false
            if (p.limitUsage && p.totalOrderCount >= p.maxUsage) return false
            if (p.pricelistIds.isNotEmpty()) return false // the till sells on the config's default pricelist only
            return true
        }

        /** Odoo `pointsForPrograms` (no split points): per applicable program, what this bill earns. */
        fun points(lines: List<OfferLine>): Points {
            val result = LinkedHashMap<Int, BigDecimal>()
            val counted = HashMap<Int, MutableList<Int>>()
            val rewardLs = lines.mapIndexed { i, r -> rewardL(r, i) }
            for (p in input.programs.filter { isApplicable(it) }) {
                var points = ZERO
                for (rule in p.rules) {
                    if (rule.mode == "with_code" && rule.id !in state.codes) continue
                    fun matches(l: L) = rule.anyProduct || l.productId in rule.validProductIds
                    // Lines for the minimum: not the discounts of automatic programs, nor this program's own reward
                    // lines. Odoo leaves a program's own free item in its minimum, so "free can on bills over ₹199"
                    // takes the can off, drops below ₹199 and removes it again on the next tap; the till keeps
                    // the bill as it was before the free item, which is what the offer means.
                    val forRule = regular.filter(::matches) + rewardLs.filter { rl ->
                        val isDiscount = rewards[rl.reward!!.rewardId]?.type == "discount"
                        val auto = programs[rl.reward.programId]?.trigger == "auto"
                        matches(rl) && !(isDiscount && auto) && rl.reward.programId != p.id
                    }
                    val amountCheck = if (rule.minimumTaxIncluded) forRule.sumOf { it.incl }.takeIf { it.signum() != 0 } ?: forRule.sumOf { it.excl }
                    else forRule.sumOf { it.excl }
                    if (rule.minimumAmount > amountCheck) continue
                    val validRegular = regular.filter(::matches)
                    val paid = validRegular.sumOf { it.incl } + rewardLs.filter { matches(it) && it.reward!!.programId != p.id }.sumOf { it.incl }
                    val qty = validRegular.sumOf { it.qty }
                    if (!rule.anyProduct && validRegular.isEmpty()) continue
                    if (qty < rule.minimumQty) continue
                    counted.getOrPut(p.id) { mutableListOf() } += rule.id
                    points += when (rule.pointMode) {
                        "order" -> rule.pointAmount
                        "money" -> rule.pointAmount.multiply(paid).setScale(2, RoundingMode.HALF_UP)
                        "unit" -> rule.pointAmount.multiply(BigDecimal(qty))
                        else -> ZERO
                    }
                }
                if (points.signum() != 0 || p.type == "coupons") result[p.id] = points
                else if (p.nominative && input.partnerId != null) result[p.id] = ZERO // the customer's card is always on the bill
            }
            return Points(result, counted)
        }

        private var lastPoints: Points? = null

        fun coupons(points: Points): List<CouponChange> {
            lastPoints = points
            return points.byProgram.map { (programId, pts) -> CouponChange(programId, couponKey(programs.getValue(programId)), pts) }
        }

        private fun couponKey(p: OfferProgram): Int =
            if (p.nominative) input.cards.firstOrNull { it.programId == p.id }?.cardId ?: newCouponKey(p.id) else newCouponKey(p.id)

        /** Odoo `_getRealCouponPoints`: the card's balance, plus what this bill earns on it, minus rewards already taken. */
        private fun realPoints(key: Int, coupons: List<CouponChange>, lines: List<OfferLine>): BigDecimal {
            var pts = input.cards.firstOrNull { it.cardId == key }?.points ?: state.scanned.firstOrNull { it.cardId == key }?.points ?: ZERO
            coupons.firstOrNull { it.couponKey == key }?.let { c -> if (programs[c.programId]?.appliesOn != "future") pts += c.points }
            return pts - lines.filter { it.couponKey == key }.sumOf { it.pointsCost }
        }

        /** Odoo `_canGenerateRewards`: a code program's rules are conditions of the whole bill. */
        private fun canGenerate(p: OfferProgram, incl: BigDecimal, excl: BigDecimal): Boolean = p.rules.all { rule ->
            val valid = regular.filter { rule.anyProduct || it.productId in rule.validProductIds }
            rule.minimumAmount <= (if (rule.minimumTaxIncluded) incl else excl) &&
                valid.sumOf { it.qty } >= rule.minimumQty &&
                (rule.anyProduct || valid.isNotEmpty())
        }

        private fun programOf(key: Int, coupons: List<CouponChange>): OfferProgram? =
            (coupons.firstOrNull { it.couponKey == key }?.programId ?: state.scanned.firstOrNull { it.cardId == key }?.programId)?.let { programs[it] }

        /** Odoo `getClaimableRewards`. */
        fun claimable(coupons: List<CouponChange>, lines: List<OfferLine>, auto: Boolean): List<Claimable> {
            val sources = coupons.map { it.programId to it.couponKey } +
                state.scanned.filter { s -> coupons.none { it.couponKey == s.cardId } }.map { it.programId to it.cardId }
            val incl = totalIncl(lines)
            val excl = totalExcl(lines)
            val globalPct = lines.firstNotNullOfOrNull { l -> rewards[l.rewardId]?.takeIf { it.isGlobalDiscount }?.discount } ?: ZERO
            val out = mutableListOf<Claimable>()
            for ((programId, key) in sources) {
                val p = programs[programId] ?: continue
                if (!p.supported || p.pricelistIds.isNotEmpty()) continue
                if (p.trigger == "with_code" && !canGenerate(p, incl, excl)) continue
                val pts = realPoints(key, coupons, lines)
                for (r in p.rewards) {
                    if (pts < r.requiredPoints) continue
                    if ((p.type == "coupons" || (auto && r.type == "discount")) && lines.any { it.rewardId == r.id }) continue
                    if (auto && r.id in state.disabled) continue
                    if (r.isGlobalDiscount && r.discount <= globalPct) continue
                    if (r.type == "discount" && incl.signum() == 0) continue
                    if (r.type != "product") { out += Claimable(r, p, key, null); continue }
                    val product = when {
                        !r.multiProduct -> r.rewardProductIds.firstOrNull()
                        auto -> r.rewardProductIds.firstOrNull { it == input.selectedProductId }
                        else -> null
                    }
                    if (product != null) {
                        if (unclaimed(r, key, product, pts, coupons, lines) > 0) out += Claimable(r, p, key, product)
                    } else if (!auto) {
                        // Several free items to pick from: those in the cart that can still be given.
                        val choices = r.rewardProductIds.filter { pid ->
                            regular.any { it.productId == pid } && unclaimed(r, key, pid, pts, coupons, lines) > 0
                        }
                        if (choices.isNotEmpty()) out += Claimable(r, p, key, null, choices)
                    }
                }
            }
            return out
        }

        /** Odoo `_updateRewardLines` + `_applyReward`, in order; a claim that cannot apply is dropped. */
        fun applyAll(ordered: List<Claim>, coupons: List<CouponChange>): Applied {
            var lines = emptyList<OfferLine>()
            val kept = mutableListOf<Claim>()
            val messages = mutableListOf<String>()
            for (claim in ordered) {
                val r = rewards[claim.rewardId] ?: continue
                val p = programs[r.programId] ?: continue
                if (programOf(claim.couponKey, coupons)?.id != p.id) continue
                when (val res = apply(claim, r, p, coupons, lines)) {
                    is Res.Ok -> {
                        lines = res.lines
                        kept.filter { it.code in res.removedCodes && !it.auto }.forEach { old ->
                            messages += "${rewards[old.rewardId]?.description ?: "Reward"}: replaced by ${r.description}, the larger whole-bill discount"
                        }
                        kept.removeAll { it.code in res.removedCodes }
                        kept += claim
                    }
                    is Res.Err -> if (!claim.auto) messages += "${r.description}: ${res.message}"
                }
            }
            return Applied(lines, kept, messages)
        }

        private sealed interface Res {
            class Ok(val lines: List<OfferLine>, val removedCodes: Set<String> = emptySet()) : Res
            class Err(val message: String) : Res
        }

        private fun apply(claim: Claim, r: OfferReward, p: OfferProgram, coupons: List<CouponChange>, current: List<OfferLine>): Res {
            if (realPoints(claim.couponKey, coupons, current) < r.requiredPoints) {
                return Res.Err("There are not enough points on the coupon to claim this reward.")
            }
            var lines = current
            var removed = emptySet<String>()
            if (r.isGlobalDiscount) {
                val globals = lines.filter { rewards[it.rewardId]?.isGlobalDiscount == true }
                if (globals.isNotEmpty()) {
                    val other = rewards.getValue(globals.first().rewardId)
                    if (other.id != r.id && other.discount >= r.discount) return Res.Err("A better global discount is already applied.")
                    removed = globals.map { it.code }.toSet()
                    lines = lines.filterNot { it.code in removed }
                }
            }
            val new = when (r.type) {
                "discount" -> discountLines(claim, r, coupons, lines)
                "product" -> productLines(claim, r, p, coupons, lines)
                else -> return Res.Err("This kind of reward is not supported on this till yet.")
            }
            return when (new) {
                is Res.Err -> new
                is Res.Ok -> if (new.lines.isEmpty()) Res.Err("The reward could not be applied.") else Res.Ok(lines + new.lines, removed)
            }
        }

        // ---------------------------------------------------------------- discounts

        private class Discountable(val total: BigDecimal, val perTax: LinkedHashMap<List<Int>, BigDecimal>)

        /** Odoo `_getDiscountableOnOrder`: every line, reward lines included. */
        private fun onOrder(lines: List<OfferLine>): Discountable {
            val perTax = LinkedHashMap<List<Int>, BigDecimal>()
            var total = ZERO
            for (l in all(lines)) {
                total += l.incl
                perTax[l.taxKey] = (perTax[l.taxKey] ?: ZERO) + l.base
            }
            return Discountable(total, perTax)
        }

        private fun cheapest(r: OfferReward): L? =
            regular.filter { r.discounts(it.productId) }.minByOrNull { it.incl.divide(BigDecimal(it.qty), MC) }

        /** Odoo `_getDiscountableOnCheapest`: one unit of the cheapest eligible item. */
        private fun onCheapest(r: OfferReward): Discountable {
            val line = cheapest(r) ?: return Discountable(ZERO, LinkedHashMap())
            return Discountable(line.unit, linkedMapOf(line.taxKey to line.unit))
        }

        /** Odoo `_getDiscountableOnSpecific`: the eligible items, after the percentage discounts already on them. */
        private fun onSpecific(r: OfferReward, lines: List<OfferLine>): Discountable {
            val everything = all(lines)
            val orderProducts = everything.map { it.productId }.toSet()
            val remaining = LinkedHashMap<String, BigDecimal>()
            val toDiscount = mutableListOf<L>()
            val perCode = LinkedHashMap<String, MutableList<L>>()
            for (l in everything) {
                if (l.qty == 0 || l.unit.signum() == 0) continue
                remaining[l.key] = l.incl
                val rw = l.reward
                if ((rw == null && r.discounts(l.productId)) || r.discounts(rw?.rewardProductId)) {
                    toDiscount += l
                } else if (rw != null) {
                    val lr = rewards[rw.rewardId]
                    if (lr != null && (lr.id == r.id ||
                            (orderProducts.any { lr.discounts(it) && r.discounts(it) } && lr.type == "discount" && lr.discountMode != "percent"))
                    ) toDiscount += l
                    perCode.getOrPut(rw.code) { mutableListOf() } += l
                }
            }
            for (group in perCode.values) {
                val lr = rewards[group.first().reward!!.rewardId] ?: continue
                if (lr.type != "discount" || lr.discountMode != "percent") continue
                val discounted = when (lr.applicability) {
                    "cheapest" -> listOfNotNull(cheapest(lr))
                    "specific" -> everything.filter { it.qty != 0 && (lr.discounts(it.productId) || lr.discounts(it.reward?.rewardProductId)) }
                    else -> everything
                }
                val pct = lr.discount.divide(HUNDRED, MC)
                for (l in discounted) {
                    if (l.reward != null) continue
                    val rem = remaining[l.key] ?: continue
                    var off = rem.multiply(pct).negate()
                    if (lr.applicability == "cheapest") off = off.divide(BigDecimal(l.qty), MC)
                    if (lr.discountMaxAmount.signum() > 0) off = off.max(lr.discountMaxAmount.negate())
                    remaining[l.key] = rem + off
                }
            }
            val perTax = LinkedHashMap<List<Int>, BigDecimal>()
            var total = ZERO
            for (l in toDiscount) {
                val rem = remaining[l.key] ?: ZERO
                total += rem
                val share = if (l.incl.signum() != 0) l.base.multiply(rem.divide(l.incl, MC), MC) else ZERO
                perTax[l.taxKey] = (perTax[l.taxKey] ?: ZERO) + share
            }
            return Discountable(total, perTax)
        }

        /** Odoo `_getRewardLineValuesDiscount` for a non-payment program: one line per tax group. */
        private fun discountLines(claim: Claim, r: OfferReward, coupons: List<CouponChange>, lines: List<OfferLine>): Res {
            val d = when (r.applicability) {
                "order" -> onOrder(lines)
                "cheapest" -> onCheapest(r)
                "specific" -> onSpecific(r, lines)
                else -> return Res.Err("Unknown discount type")
            }
            val priceIncl = totalIncl(lines)
            val totalFactor = if (d.total.signum() > 0) ONE.min(priceIncl.divide(d.total, MC)) else ONE
            val discountable = priceIncl.min(d.total)
            if (discountable.abs() < HALF_PAISA) return Res.Ok(emptyList())
            var max: BigDecimal? = r.discountMaxAmount.takeIf { it.signum() > 0 }
            fun cap(v: BigDecimal) = max?.min(v) ?: v
            val pts = realPoints(claim.couponKey, coupons, lines)
            max = when (r.discountMode) {
                "per_point" -> cap(r.discount.multiply(pts.divide(r.perUnit, 0, RoundingMode.FLOOR).multiply(r.perUnit)))
                "per_order" -> cap(r.discount)
                "percent" -> cap(discountable.multiply(r.discount).divide(HUNDRED, MC))
                else -> return Res.Err("Unknown discount mode")
            }
            val maxDiscount = max!!
            var pointCost = if (r.clearWallet) pts else r.requiredPoints
            if (r.discountMode == "per_point" && !r.clearWallet) pointCost = maxDiscount.min(discountable).divide(r.discount, MC)
            val discountFactor = if (discountable.signum() != 0) ONE.min(maxDiscount.divide(discountable, MC)) else ONE
            val shares = d.perTax.filterValues { it.signum() != 0 }.mapValues { (_, v) -> v.multiply(totalFactor, MC).multiply(discountFactor, MC) }
            return Res.Ok(toPaise(shares).entries.mapIndexed { i, (taxIds, amount) ->
                OfferLine(r.id, r.programId, claim.couponKey, claim.code, r.discountLineProductId, r.description, Paise(-amount.value), 1,
                    taxIds, if (i == 0) pointCost else ZERO)
            })
        }

        /**
         * Exact rupees → paise per tax group: each share half-up, the last one takes the remainder, so
         * the parts always add up to the discount rounded once (₹200 off is ₹200.00, never ₹199.99).
         */
        private fun toPaise(shares: Map<List<Int>, BigDecimal>): LinkedHashMap<List<Int>, Paise> {
            val total = Paise.fromRupees(shares.values.fold(ZERO, BigDecimal::add))
            val out = LinkedHashMap<List<Int>, Paise>()
            var left = total
            shares.entries.forEachIndexed { i, (k, v) ->
                val p = if (i == shares.size - 1) left else Paise.fromRupees(v)
                left -= p
                out[k] = p
            }
            return out
        }

        // ---------------------------------------------------------------- free items

        /** Odoo `_getRewardLineValuesProduct`: the free item(s) at their own price, taken off on the reward's product. */
        private fun productLines(claim: Claim, r: OfferReward, p: OfferProgram, coupons: List<CouponChange>, lines: List<OfferLine>): Res {
            val product = r.rewardProductIds.firstOrNull { it == claim.productId } ?: r.rewardProductIds.firstOrNull()
                ?: return Res.Err("This reward has no free item set in Odoo.")
            val pts = realPoints(claim.couponKey, coupons, lines)
            val unclaimed = unclaimed(r, claim.couponKey, product, pts, coupons, lines)
            if (unclaimed <= 0) return Res.Err("There are not enough products in the basket to claim this reward.")
            val count = if (r.clearWallet) 1 else minOf(
                kotlin.math.ceil(unclaimed.toDouble() / r.rewardProductQty).toInt(),
                pts.divide(r.perUnit, 0, RoundingMode.FLOOR).toInt(),
            )
            val cost = if (r.clearWallet) pts else r.requiredPoints.multiply(BigDecimal(count))
            val freeQty = minOf(unclaimed, r.rewardProductQty * count)
            val item = input.lines.firstOrNull { it.product.id == product }?.product
                ?: return Res.Err("Add the free item to the bill first.")
            if (freeQty <= 0) return Res.Ok(emptyList())
            return Res.Ok(listOf(OfferLine(r.id, p.id, claim.couponKey, claim.code, r.discountLineProductId,
                "${r.description}: ${item.name}", Paise(-item.price.value), freeQty, item.taxIds, cost, rewardProductId = product)))
        }

        /** Odoo `_computeUnclaimedFreeProductQty`. */
        private fun unclaimed(r: OfferReward, key: Int, product: Int, points: BigDecimal, coupons: List<CouponChange>, lines: List<OfferLine>): Int {
            var remaining = points
            var claimed = 0
            var available = 0
            var shouldCorrect = false
            if (product in r.rewardProductIds) for (l in regular) {
                if (l.productId !in r.rewardProductIds) continue
                if (lines.isEmpty()) { if (l.productId == product) available += l.qty } else available += l.qty
            }
            for (rl in lines) {
                if (rl.rewardProductId == null || rl.rewardProductId !in r.rewardProductIds) continue
                if (rl.rewardId == r.id) { remaining += rl.pointsCost; claimed += rl.qty } else shouldCorrect = true
            }
            val p = programs.getValue(r.programId)
            val perReward = remaining.divide(r.perUnit, MC).multiply(BigDecimal(r.rewardProductQty)).setScale(0, RoundingMode.FLOOR).toInt()
            val freeQty = if (p.trigger == "auto" && p.appliesOn != "future" && p.rules.any { it.anyProduct || product in it.validProductIds }) {
                val counted = lastPoints?.countedRules?.get(p.id)
                val rules = if (counted != null) p.rules.filter { it.id in counted } else p.rules
                var factor = ZERO
                var orderPoints = ZERO
                val price = input.lines.firstOrNull { it.product.id == product }?.product?.price?.toRupees() ?: ZERO
                for (rule in rules) {
                    if (!(rule.anyProduct || product in rule.validProductIds)) continue
                    when (rule.pointMode) {
                        "order" -> orderPoints += rule.pointAmount
                        "money" -> factor += rule.pointAmount.multiply(price).setScale(2, RoundingMode.HALF_UP)
                        "unit" -> factor += rule.pointAmount
                    }
                }
                if (factor.signum() == 0) perReward
                else {
                    val correction = if (shouldCorrect) pointsCorrection(p, lines) else ZERO
                    computeFreeQuantity(
                        (remaining - correction - orderPoints).divide(factor, MC).toDouble(),
                        r.perUnit.divide(factor, MC).toDouble(),
                        r.rewardProductQty.toDouble(),
                    ) + orderPoints.divide(r.perUnit, MC).multiply(BigDecimal(r.rewardProductQty)).setScale(0, RoundingMode.FLOOR).toInt()
                }
            } else perReward
            return minOf(available, freeQty) - claimed
        }

        /** Odoo `_getPointsCorrection`: points the free items of a program would otherwise earn. */
        private fun pointsCorrection(p: OfferProgram, lines: List<OfferLine>): BigDecimal {
            if (!canGenerate(p, totalIncl(lines), totalExcl(lines))) return ZERO
            var res = ZERO
            for (rule in p.rules) for (rl in lines) {
                val reward = rewards[rl.rewardId] ?: continue
                if (reward.type != "product" || rule.pointMode == "order" || reward.programId != p.id) continue
                if (!(rule.anyProduct || rl.rewardProductId in rule.validProductIds)) continue
                when (rule.pointMode) {
                    "money" -> res -= rule.pointAmount.multiply(rl.amount.toRupees()).setScale(2, RoundingMode.HALF_UP)
                    "unit" -> res += rule.pointAmount.multiply(BigDecimal(rl.qty))
                }
            }
            return res
        }
    }

    private val HALF_PAISA = BigDecimal("0.005")

    /** Points per reward, never 0 in a division (Odoo's default is 1). */
    private val OfferReward.perUnit: BigDecimal get() = requiredPoints.takeIf { it.signum() > 0 } ?: ONE
}
