package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.gst.GstTreatment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** An open bill waits on the till with only what the cashier did, and comes back priced from today's catalogue. */
class ParkedBillTest {
    private val chai = Product(8202, "Cardamom Chai", Paise.ofRupees(60), null, null, listOf(30))
    private val coffee = Product(8625, "Cookie Butter Coffee", Paise.ofRupees(180), null, null, listOf(30))

    private val bill = ParkedBill(
        orderUuid = "u-7", number = 3, createdAt = 1_000L,
        lines = listOf(ParkedLine("l1", 8202, 2, listOf(LineNote("Less sugar", 2))), ParkedLine("l2", 8625, 1)),
        customer = Customer(83, "Asha Rao", "+919000000000", gstin = null, gstTreatment = GstTreatment.UNREGISTERED, stateId = 6, stateName = "Delhi"),
        offers = OfferState(codes = setOf(41), claims = listOf(Claim(12, -1, productId = 8202, code = "R-1")), disabled = setOf(9),
            scanned = listOf(ScannedCoupon(77, 5, BigDecimal("1.50"), "SAMPLE-1"))),
        zzReward = ZzCashReward(3, "₹50 off", Paise(5000), Paise(5000), 900),
    )

    @Test fun `a waiting bill is saved and read back whole`() {
        assertEquals(bill, ParkedBill.fromJson(bill.toJson()))
    }

    @Test fun `the JSON keeps no price, only products and quantities`() {
        val json = bill.toJson()
        assertTrue(json, "\"productId\":8202" in json && "\"qty\":2" in json)
        assertTrue(json, "price" !in json && "total" !in json)
    }

    @Test fun `it comes back from today's catalogue, without products gone or held by the pricelist`() {
        val r = bill.restore(mapOf(8202 to chai.copy(price = Paise.ofRupees(65)))) { false }
        assertEquals(listOf("l1"), r.lines.map { it.uuid })
        assertEquals(Paise.ofRupees(65), r.lines.single().product.price) // today's price, priced again by Odoo
        assertNull(r.lines.single().totalIncl)
        assertEquals(1, r.dropped)
        assertEquals(2, bill.restore(mapOf(8202 to chai, 8625 to coffee)) { it == 8625 }.let { it.dropped + it.lines.size })
    }

    @Test fun `the chip shows the customer's first name, else the bill number`() {
        assertEquals("Asha", bill.label)
        assertEquals("Bill 3", bill.copy(customer = null).label)
        assertEquals(3, bill.itemCount)
        assertTrue(ParkedBill("u", 1, 0, emptyList()).isEmpty)
    }

    @Test fun `text this build cannot read is no bill`() {
        assertNull(ParkedBill.fromJson("not json"))
        assertNull(ParkedBill.fromJson("""{"number": 2}"""))
    }
}
