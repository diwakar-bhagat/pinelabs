package com.bohemian.odoo.pos.ui

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.odoo.CartLine
import com.bohemian.odoo.pos.odoo.CartTotals
import com.bohemian.odoo.pos.odoo.Customer
import com.bohemian.odoo.pos.odoo.OdooBill
import com.bohemian.odoo.pos.odoo.OdooBillLine
import com.bohemian.odoo.pos.odoo.OdooBillPayment
import com.bohemian.odoo.pos.odoo.OdooBillZzCash
import com.bohemian.odoo.pos.odoo.PaidSale
import com.bohemian.odoo.pos.odoo.PaymentLineData
import com.bohemian.odoo.pos.odoo.Product
import com.bohemian.odoo.pos.pine.PayMode
import com.bohemian.odoo.pos.receipt.ReceiptItem
import com.bohemian.odoo.pos.receipt.ReceiptPayment
import com.bohemian.odoo.pos.receipt.ReceiptZzCash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.TimeZone

class BillReceiptTest {
    private val ist = TimeZone.getTimeZone("Asia/Kolkata")

    private val bill = OdooBill(
        name = "ZenZebra - 000020", posReference = "260-1-000020", dateOrderUtc = "2026-10-27 12:04:16",
        storeName = "ZenZebra", customerName = "Diwakar", customerPhone = "+91 70489 80055", cashier = "Ankur",
        lines = listOf(
            OdooBillLine("Go desi Masala Jamun Popz 8gm", BigDecimal("1.0"), BigDecimal("0.0"), listOf("5% GST S"), Paise(500)),
            OdooBillLine("Makino Corn Munch", BigDecimal("3.0"), BigDecimal("10.0"), listOf("12% GST S", "Cess"), Paise(10000)),
        ),
        amountTotal = Paise(10500), amountTax = Paise(1095),
        payments = listOf(OdooBillPayment("Pine Labs E700 (A2A)", Paise(10500))),
        accessToken = "tok",
        zzCash = OdooBillZzCash("6.30", null, "41.00", "26 Nov 2026"),
    )

    @Test fun `the paper bill is Odoo's bill`() {
        val r = BillReceipt.fromOdoo(bill, eBillUrl = "https://x.test/t", tz = ist)
        assertEquals("ZenZebra - 000020", r.billNo)
        assertEquals("27 oct 2026, 05:34 pm", r.dateTime.lowercase())
        assertEquals("Ankur", r.servedBy)
        assertEquals("+91 70489 80055", r.customerPhone)
        assertEquals(ReceiptItem("Go desi Masala Jamun Popz 8gm", "1", Paise(500), Paise(500), "5% GST S"), r.items[0])
        // rate = amount ÷ qty, as the template prints it; the discount is shown like Odoo's
        assertEquals(ReceiptItem("Makino Corn Munch", "3", Paise(3333), Paise(10000), "12% GST S, Cess", "10"), r.items[1])
        assertEquals(Paise(9405), r.taxable) // total − GST
        assertEquals(listOf(ReceiptPayment("Pine Labs E700 (A2A)", Paise(10500))), r.payments)
        assertEquals(ReceiptZzCash("6.30", null, "41.00", "26 Nov 2026"), r.zzCash)
        assertEquals("260-1-000020", r.billRef)
        assertFalse(r.pendingInOdoo)
    }

    @Test fun `a sale still in the outbox prints the till's copy and says so`() {
        val chai = Product(8201, "Adrak Elaichi Chai", Paise(2000), null, null, listOf(30))
        val sale = PaidSale(
            orderUuid = "3c5e0cf3-41ea", sessionId = 391,
            lines = listOf(CartLine("l1", chai, 2, totalIncl = Paise(4000), totalExcl = Paise(3810))),
            totals = CartTotals(Paise(4000), Paise(3810)),
            customer = Customer(19, "Diwakar", "+917048980055"),
            payments = listOf(PaymentLineData(22, Paise(4000), PayMode.UPI, "ZENZ261027173400123", null)),
            paidAtMillis = 1_793_104_456_000L,
        )
        val r = BillReceipt.fromSale(sale, "ZenZebra", "ZENZ261027173400123", ist)
        assertTrue(r.pendingInOdoo)
        assertEquals("ZENZ261027173400123", r.billNo)
        assertEquals(listOf(ReceiptItem("Adrak Elaichi Chai", "2", Paise(2000), Paise(4000))), r.items)
        assertEquals(listOf(ReceiptPayment("UPI", Paise(4000))), r.payments)
        assertNull(r.billRef)
        assertNull(r.zzCash)
    }

    @Test fun `quantities print like the template`() {
        assertEquals("1", BillReceipt.qtyText(BigDecimal("1.0")))
        assertEquals("0.5", BillReceipt.qtyText(BigDecimal("0.50")))
        assertEquals("12", BillReceipt.qtyText(BigDecimal("12")))
    }
}
