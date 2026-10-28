package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The offers engine against the stores' live programs of 2026-10-28 (codes made up: the repo is
 * public). Two cases are real orders, checked line by line: HQ27GGN-000601 (a ZZ Note ₹200) and
 * HQ27GGN-000613 (the ₹89 momos combo).
 */
class OffersTest {
    private var n = 0
    private val codes = { "code${++n}" }
    private val today = "2026-10-28"

    private fun d(v: String) = BigDecimal(v)

    private fun line(id: Int, name: String, rupees: String, taxes: List<Int>, qty: Int = 1, uuid: String = "l$id") =
        Paise.fromRupees(d(rupees)).let { unit ->
            CartLine(uuid, Product(id, name, unit, null, null, taxes), qty, totalIncl = unit * qty,
                // Excluded value as Odoo's compute_all gives it for 18% / 5% GST included.
                totalExcl = Paise.fromRupees(d(rupees).multiply(BigDecimal(qty)).divide(d(if (30 in taxes) "1.18" else "1.05"), 2, java.math.RoundingMode.HALF_UP)))
        }

    private fun rule(id: Int, program: Int, mode: String = "auto", code: String? = null, pointMode: String = "order", amount: String = "1",
                     min: String = "0", minQty: Int = 0, any: Boolean = false, valid: Set<Int> = emptySet()) =
        OfferRule(id, program, mode, code, pointMode, d(amount), d(min), true, minQty, any, valid)

    private fun discount(id: Int, program: Int, desc: String, mode: String, applicability: String, value: String, line: Int,
                         products: Set<Int> = emptySet(), required: String = "1", global: Boolean = false) =
        OfferReward(id, program, "discount", desc, d(required), d(value), mode, applicability, products, BigDecimal.ZERO, line,
            emptyList(), 1, false, global, false)

    private fun free(id: Int, program: Int, desc: String, products: List<Int>, line: Int) =
        OfferReward(id, program, "product", desc, d("1"), BigDecimal.ZERO, "percent", "order", emptySet(), BigDecimal.ZERO, line,
            products, 1, products.size > 1, false, false)

    private fun program(id: Int, name: String, type: String, trigger: String, rules: List<OfferRule>, rewards: List<OfferReward>,
                        nominative: Boolean = false, appliesOn: String = "current", from: String? = null, to: String? = null) =
        OfferProgram(id, name, type, trigger, appliesOn, nominative, from, to, false, 0, 0, emptyList(), rules, rewards)

    // The live programs (ids as in Odoo).
    private val zzNote200 = program(8, "ZZ Note ₹200", "promo_code", "with_code",
        listOf(rule(30, 8, mode = "with_code", code = "NOTE200X", min = "800", any = true)),
        listOf(discount(11, 8, "ZZ Note ₹200 (min bill ₹800)", "per_order", "order", "200", 29107, global = true)))
    private val momosCombo = program(28, "HQ27 combo: Just the Classic + momos", "promotion", "auto",
        listOf(rule(70, 28, valid = setOf(21796))),
        listOf(discount(40, 28, "Combo ₹199: ₹89 off Steamy Paneer Momos", "per_order", "specific", "89", 34936, products = setOf(21916))))
    private val sampling = program(9, "Sampling (manager code)", "promo_code", "with_code",
        listOf(rule(31, 9, mode = "with_code", code = "SAMPLEX", any = true)),
        listOf(discount(12, 9, "Free sample", "percent", "cheapest", "100", 29200, products = setOf(1, 2))))
    private val artiscents = program(17, "Artiscents: 2+ perfumes at ₹899 each", "promotion", "auto",
        listOf(rule(50, 17, pointMode = "unit", minQty = 2, valid = setOf(501, 502))),
        listOf(discount(25, 17, "₹100 off each perfume", "per_point", "specific", "100", 30001, products = setOf(501, 502))))
    private val fugeno = program(19, "Fugeno: flat 35% off", "promotion", "auto",
        listOf(rule(60, 19, valid = setOf(601))),
        listOf(discount(30, 19, "35% off Fugeno", "percent", "specific", "35", 30002, products = setOf(601))))
    private val peping = program(27, "Club 125: free Peping can on bills over ₹199", "promotion", "auto",
        listOf(rule(80, 27, min = "199", any = true)),
        listOf(free(45, 27, "Free Peping can", listOf(701, 702, 703), 30003)))
    private val zzCash = program(4, "ZZ Cash", "loyalty", "auto",
        listOf(rule(1, 4, pointMode = "money", amount = "0.06", valid = setOf(21796, 21916))),
        listOf(discount(3, 4, "₹50 off (50 ZZ Cash)", "per_order", "order", "50", 29000, required = "50", global = true)),
        nominative = true, appliesOn = "both")

    private fun run(programs: List<OfferProgram>, lines: List<CartLine>, state: OfferState = OfferState(), partner: Int? = 19,
                    cards: List<CardBalance> = emptyList(), selected: Int? = null) =
        Offers.evaluate(OfferInput(programs, lines, partner, today, cards, selected), state, codes)

    private fun activated(programs: List<OfferProgram>, code: String) =
        (Offers.ruleForCode(programs, code, today, OfferState()) as Offers.CodeCheck.Rule).rule.id

    @Test fun `HQ27GGN-000601 - ZZ Note 200 on a 899 bill takes 200 off at the item's tax, costing 1 point`() {
        val programs = listOf(zzNote200)
        val r = run(programs, listOf(line(27857, "Kiss Ceramic Set", "899", listOf(30))), OfferState(codes = setOf(activated(programs, "NOTE200X"))))
        val l = r.lines.single()
        assertEquals(29107, l.productId)
        assertEquals(Paise.ofRupees(-200), l.unitPrice)
        assertEquals(listOf(30), l.taxIds)
        assertEquals(0, l.pointsCost.compareTo(BigDecimal.ONE))
        assertEquals(listOf(CouponChange(8, Offers.newCouponKey(8), BigDecimal.ONE)), r.coupons)
    }

    @Test fun `ZZ Note 200 is refused at 799 and applies at 800`() {
        val programs = listOf(zzNote200)
        val state = OfferState(codes = setOf(activated(programs, "NOTE200X")))
        assertTrue(run(programs, listOf(line(1, "Mug", "799", listOf(30))), state).lines.isEmpty())
        assertEquals(Paise.ofRupees(-200), run(programs, listOf(line(1, "Mug", "800", listOf(30))), state).lines.single().amount)
    }

    @Test fun `HQ27GGN-000613 - the combo takes 89 off the momos only with the cold coffee`() {
        val momos = line(21916, "Steamy Paneer Momos", "149", listOf(36))
        val coffee = line(21796, "Just the Classic - Cold Coffee", "139", listOf(36))
        val r = run(listOf(momosCombo), listOf(momos, coffee))
        val l = r.lines.single()
        assertEquals(34936, l.productId)
        assertEquals(Paise.ofRupees(-89), l.amount)
        assertEquals(listOf(36), l.taxIds)
        assertTrue(r.claims.single().auto)
        assertTrue(run(listOf(momosCombo), listOf(momos)).lines.isEmpty())
        assertTrue(run(listOf(momosCombo), listOf(coffee)).lines.isEmpty())
    }

    @Test fun `the sampling code makes one unit of the cheapest item free`() {
        val programs = listOf(sampling)
        val r = run(programs, listOf(line(1, "Cookie", "120", listOf(31), qty = 2), line(2, "Toffee", "80", listOf(31))),
            OfferState(codes = setOf(activated(programs, "samplex")))) // any case, after no exact match
        assertEquals(Paise.ofRupees(-80), r.lines.single().amount)
    }

    @Test fun `Artiscents gives 100 off each perfume from the second one`() {
        assertTrue(run(listOf(artiscents), listOf(line(501, "Sea Breeze 50 ml", "999", listOf(30)))).lines.isEmpty())
        val r = run(listOf(artiscents), listOf(line(501, "Sea Breeze 50 ml", "999", listOf(30)), line(502, "Oud 50 ml", "999", listOf(30))))
        assertEquals(Paise.ofRupees(-200), r.lines.single().amount)
        assertEquals(0, r.lines.single().pointsCost.compareTo(BigDecimal(2)))
    }

    @Test fun `Fugeno takes 35 percent off its own items only`() {
        val r = run(listOf(fugeno), listOf(line(601, "Fugeno Tee", "299", listOf(30)), line(9, "Mug", "100", listOf(30))))
        assertEquals(Paise.fromRupees(d("-104.65")), r.lines.single().amount)
    }

    @Test fun `the free can is the one the cashier is on, and only on a bill over 199`() {
        val momos = line(21916, "Momos", "149", listOf(36))
        val can = line(702, "Peping Lemon", "80", listOf(31))
        val r = run(listOf(peping), listOf(momos, can), selected = 702)
        val l = r.lines.single()
        assertEquals(702, l.rewardProductId)
        assertEquals(Paise.ofRupees(-80), l.amount)
        assertEquals(listOf(31), l.taxIds)
        // On another line: not applied by itself, but offered with the can in the cart as the choice.
        val other = run(listOf(peping), listOf(momos, can), selected = 21916)
        assertTrue(other.lines.isEmpty())
        assertEquals(listOf(702), other.claimable.single().productChoices)
        // Below ₹199 there is nothing.
        assertTrue(run(listOf(peping), listOf(line(21916, "Momos", "100", listOf(36)), can), selected = 702).lines.isEmpty())
    }

    @Test fun `a picked free item comes off at its own price`() {
        val momos = line(21916, "Momos", "149", listOf(36))
        val can = line(703, "Peping Cola", "90", listOf(31))
        val r = run(listOf(peping), listOf(momos, can), OfferState(claims = listOf(Claim(45, Offers.newCouponKey(27), productId = 703, code = "pick"))))
        assertEquals(Paise.ofRupees(-90), r.lines.single().amount)
    }

    @Test fun `only one whole-bill discount - the larger replaces the smaller, a smaller one is refused`() {
        val programs = listOf(zzCash, zzNote200)
        val cart = listOf(line(21916, "Momos", "900", listOf(36)))
        val card = listOf(CardBalance(cardId = 3563, programId = 4, points = d("120")))
        val zzClaim = Claim(3, 3563, code = "zz")
        // ZZ Cash ₹50 first, then the ₹200 note: the note wins and the cashier is told.
        val both = run(programs, cart, OfferState(codes = setOf(30), claims = listOf(zzClaim)), cards = card)
        assertEquals(listOf(11), both.lines.map { it.rewardId })
        assertTrue(both.messages.single().contains("replaced by ZZ Note ₹200"))
        // The note first, then ZZ Cash ₹50 chosen: refused.
        val noteFirst = run(programs, cart, OfferState(codes = setOf(30), claims = listOf(Claim(11, Offers.newCouponKey(8), code = "n"), zzClaim)), cards = card)
        assertEquals(listOf(11), noteFirst.lines.map { it.rewardId })
        assertTrue(noteFirst.messages.single().contains("A better global discount is already applied"))
    }

    @Test fun `ZZ Cash is spent from the card, never applied by itself, and only for a customer`() {
        val cart = listOf(line(21916, "Momos", "300", listOf(36)))
        val card = listOf(CardBalance(3563, 4, d("40")))
        // 40 on the card + 18 earned now (6% of ₹300) covers the ₹50 reward, as in the web POS.
        val r = run(listOf(zzCash), cart, OfferState(claims = listOf(Claim(3, 3563, code = "zz"))), cards = card)
        assertEquals(Paise.ofRupees(-50), r.lines.single().amount)
        assertEquals(listOf(CouponChange(4, 3563, d("18.00"))), r.coupons)
        assertTrue(run(listOf(zzCash), cart, cards = card).lines.isEmpty())
        assertTrue(run(listOf(zzCash), cart, partner = null).coupons.isEmpty())
    }

    @Test fun `a program out of its dates neither applies nor takes its code`() {
        val expired = zzNote200.copy(dateTo = "2026-10-27")
        assertEquals(Offers.CodeCheck.Refused("That promo code program is expired."), Offers.ruleForCode(listOf(expired), "NOTE200X", today, OfferState()))
        assertEquals(Offers.CodeCheck.NoRule, Offers.ruleForCode(listOf(expired), "NOPE", today, OfferState()))
        val later = momosCombo.copy(dateFrom = "2026-10-29")
        assertTrue(run(listOf(later), listOf(line(21916, "Momos", "149", listOf(36)), line(21796, "Coffee", "139", listOf(36)))).lines.isEmpty())
    }

    @Test fun `a code already on the bill is refused`() =
        assertEquals(Offers.CodeCheck.Refused("That promo code program has already been activated."),
            Offers.ruleForCode(listOf(zzNote200), "NOTE200X", today, OfferState(codes = setOf(30))))

    @Test fun `a reward the cashier took off is not put back by itself`() {
        val cart = listOf(line(21916, "Momos", "149", listOf(36)), line(21796, "Coffee", "139", listOf(36)))
        assertTrue(run(listOf(momosCombo), cart, OfferState(disabled = setOf(40))).lines.isEmpty())
    }

    @Test fun `codes stay the same from one reprice to the next`() {
        val cart = listOf(line(21916, "Momos", "149", listOf(36)), line(21796, "Coffee", "139", listOf(36)))
        val first = run(listOf(momosCombo), cart)
        val again = run(listOf(momosCombo), cart, OfferState(claims = first.claims))
        assertEquals(first.lines, again.lines)
    }

    @Test fun `gift cards stay with the web POS`() {
        val gift = program(1, "Gift Cards", "gift_card", "auto", listOf(rule(2, 1)), emptyList(), appliesOn = "future")
        assertTrue(!gift.supported)
        assertTrue(run(listOf(gift), listOf(line(1, "x", "100", listOf(30)))).coupons.isEmpty())
    }

    @Test fun `buy n take m, as in Odoo's table`() {
        assertEquals(listOf(0, 0, 1, 1, 1, 2, 2, 2, 3, 3), (1..10).map { Offers.computeFreeQuantity(it.toDouble(), 2.0, 1.0) })
        assertEquals(listOf(0, 0, 1, 2, 3, 3, 3, 4, 5, 6), (1..10).map { Offers.computeFreeQuantity(it.toDouble(), 2.0, 3.0) })
    }
}
