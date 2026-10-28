package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** An offer on a sale, as Odoo stores it: HQ27GGN-000601 (ZZ Note ₹200 on a ₹899 set, 18% GST). */
class OfferPayloadTest {
    private val set = CartLine("l1", Product(27857, "Kiss Ceramic Set", Paise.ofRupees(899), null, null, listOf(30)), 1,
        totalIncl = Paise.ofRupees(899), totalExcl = Paise(76186))
    private val noteKey = Offers.newCouponKey(8)
    private val note = PricedOffer(
        OfferLine(11, 8, noteKey, "k3xq9z1ab", 29107, "ZZ Note ₹200 (min bill ₹800)", Paise.ofRupees(-200), 1, listOf(30), BigDecimal.ONE),
        totalIncl = Paise.ofRupees(-200), totalExcl = Paise(-16949))
    private val programs = listOf(
        OfferProgramInfo(8, "promo_code", "current", nominative = false, dateTo = null),
        OfferProgramInfo(19, "promotion", "current", nominative = false, dateTo = "2026-10-28"),
    )
    private val offers = AppliedOffers(listOf(note), listOf(CouponChange(8, noteKey, BigDecimal.ONE), CouponChange(19, Offers.newCouponKey(19), BigDecimal.ONE)), programs)

    private val cfg = PosConfig(id = 4, name = "HQ27GGN", currentSessionId = 397, sessionOpen = true, paymentMethodIds = listOf(2, 22),
        companyName = "ZenZebra", companyId = 1)

    private fun sale(o: AppliedOffers? = offers, zzProgram: Int? = null) = PaidSale(
        orderUuid = "u1", sessionId = 397, lines = listOf(set), totals = CartTotals(Paise.ofRupees(699), Paise(59237)),
        customer = Customer(id = 19, name = "Diwakar", phone = "+91 98765 43210"), payments = listOf(PaymentLineData(2, Paise.ofRupees(699), null, null, null)),
        paidAtMillis = 0, offers = o, loyaltyProgramId = zzProgram, loyaltyCardId = 3563, loyaltyEarned = Paise(0),
    )

    @Test fun `the reward line goes as Odoo stores it, with no coupon id until Odoo creates the coupon`() {
        val lines = OrderSyncService.buildPayload(sale())["orders"]!!.jsonArray.single().jsonObject["lines"]!!.jsonArray
        assertEquals(2, lines.size)
        val r = lines[1].jsonArray[2].jsonObject
        assertEquals("29107", r["product_id"]!!.jsonPrimitive.content)
        assertEquals(-200.0, r["price_unit"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(-200.0, r["price_subtotal_incl"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("[[6,0,[30]]]", r["tax_ids"].toString())
        assertEquals("true", r["is_reward_line"]!!.jsonPrimitive.content)
        assertEquals("11", r["reward_id"]!!.jsonPrimitive.content)
        assertEquals("false", r["coupon_id"]!!.jsonPrimitive.content)
        assertEquals("k3xq9z1ab", r["reward_identifier_code"]!!.jsonPrimitive.content)
        assertEquals(1.0, r["points_cost"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test fun `the bill's coupon is booked issued 1, used 1, linked by its code - an unused promotion is not booked`() {
        val data = OrderSyncService.couponData(sale())!!
        assertEquals(setOf(noteKey.toString()), data.keys)
        val e = data.getValue(noteKey.toString()).jsonObject
        assertEquals(0.0, e["points"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(1.0, e["points_earned"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(1.0, e["points_spent"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("8", e["program_id"]!!.jsonPrimitive.content)
        assertEquals(noteKey.toString(), e["coupon_id"]!!.jsonPrimitive.content)
        assertEquals("[\"k3xq9z1ab\"]", e["line_codes"].toString())
        assertFalse(e.containsKey("partner_id"))
    }

    @Test fun `ZZ Cash and an offer are booked in one call`() {
        val data = OrderSyncService.couponData(sale(zzProgram = 4).copy(loyaltyEarned = Paise.ofRupees(18)))!!
        assertEquals(setOf("3563", noteKey.toString()), data.keys)
    }

    @Test fun `points its own free items would earn are not booked, as in Odoo`() {
        val corrected = AppliedOffers(emptyList(), listOf(CouponChange(19, Offers.newCouponKey(19), BigDecimal(3), correction = BigDecimal.ONE)),
            listOf(OfferProgramInfo(19, "loyalty", "both", nominative = false, dateTo = null)))
        val e = corrected.couponData(19).getValue(Offers.newCouponKey(19).toString())
        assertEquals(0, (e["points_earned"] as BigDecimal).compareTo(BigDecimal(2)))
    }

    @Test fun `a program whose booking rules are unknown is booked only with a reward used`() {
        val unknown = AppliedOffers(emptyList(), listOf(CouponChange(77, Offers.newCouponKey(77), BigDecimal.ONE)), emptyList())
        assertTrue(unknown.couponData(19).isEmpty())
    }

    @Test fun `a stored bill keeps the codes and coupons entered on it`() {
        val state = OfferState(codes = setOf(30), claims = listOf(Claim(11, noteKey, code = "k3xq9z1ab")),
            scanned = listOf(ScannedCoupon(9001, 3, BigDecimal.ONE, "CPN-1")))
        val snap = SaleSnapshot.of("u1", cfg, listOf(set), CartTotals(Paise.ofRupees(699), Paise(59237)), customer = null, offers = offers, offerState = state)
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(state, json.decodeFromString<SaleSnapshot>(json.encodeToString(snap)).offers!!.state())
    }

    @Test fun `nothing to book, nothing sent`() = assertNull(OrderSyncService.couponData(sale(o = null)))

    @Test fun `a stored sale sends the same offer lines and codes after a crash`() {
        val snap = SaleSnapshot.of("u1", cfg, listOf(set), CartTotals(Paise.ofRupees(699), Paise(59237)), customer = null, offers = offers)
        val json = Json { ignoreUnknownKeys = true }
        val back = json.decodeFromString<SaleSnapshot>(json.encodeToString(snap))
        assertEquals(offers, back.appliedOffers())
        assertTrue(json.decodeFromString<SaleSnapshot>(json.encodeToString(snap.copy(offers = null))).appliedOffers() == null)
    }

    @Test fun `an old stored sale without offers still reads`() {
        val old = """{"orderUuid":"u0","sessionId":1,"configName":"ZenZebra","companyName":"ZenZebra","lines":[],"totalInclPaise":0,"totalExclPaise":0}"""
        assertNull(Json { ignoreUnknownKeys = true }.decodeFromString<SaleSnapshot>(old).appliedOffers())
    }
}
