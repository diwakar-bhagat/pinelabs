package com.bohemian.odoo.pos.receipt

import com.bohemian.odoo.pos.core.Paise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptFormatterTest {
    /** Bill "ZenZebra - 000020" as Odoo's "ZenZebra Bill" PDF shows it (2026-10-27). */
    private fun bill000020(zz: ReceiptZzCash? = null) = ReceiptData(
        storeName = "ZenZebra",
        companyAddress = "A-11, 2nd Floor, A Block Rd, DDA Sheds, Pocket A, Pocket A, Okhla Phase I, New Delhi 110020",
        billNo = "ZenZebra - 000020",
        dateTime = "27 Oct 2026, 05:34 pm",
        customerName = "Diwakar", customerPhone = "+91 98765 43210",
        servedBy = "Ankur",
        items = listOf(ReceiptItem("Go desi Masala Jamun Popz 8gm", "1", Paise(500), Paise(500), taxes = "5% GST S")),
        taxable = Paise(476), tax = Paise(24), total = Paise(500),
        payments = listOf(ReceiptPayment("Cash", Paise(500))),
        zzCash = zz,
        billRef = "260-1-000020",
    )

    private fun long() = bill000020().copy(
        items = listOf(
            ReceiptItem("29 TEE overized thirt POP UP FIZZ- orange color S", "1", Paise(69900), Paise(69900), "5% GST S"),
            ReceiptItem("Makino Corn Munch Cream & Onion", "2", Paise(7000), Paise(14000), "12% GST S", discountPct = "10"),
            ReceiptItem("₹400 off (400 ZZ Cash)", "1", Paise(-23523), Paise(-23523), "18% GST S"),
        ),
        terminals = listOf(TerminalSlip("CARD", "4242", "412345678901", "A1B2C3"), TerminalSlip("UPI", null, "512345678901", null)),
        zzCash = ReceiptZzCash("401.44", "400", "36.04", "26 Nov 2026"),
        eBillUrl = "https://zenzebra1.odoo.com/pos/ticket/validate?access_token=abc",
    )

    @Test fun `no line exceeds any supported width`() {
        for (w in listOf(24, 32, 48)) {
            ReceiptFormatter(w).format(long()).forEach { assertTrue("width $w: '${it.text}'", it.text.length <= w) }
        }
    }

    @Test fun `prints every part of Odoo's bill, in its order`() {
        val text = ReceiptFormatter(32).format(bill000020()).map { it.text.trim() }
        val expected = listOf(
            "ZENZEBRA", "Bohemian Curations", "Store: ZenZebra",
            "Bill No.", "Date", "Customer", "Served by",
            "Item", "Go desi Masala Jamun Popz 8gm", "5% GST S",
            "Taxable value", "GST", "TOTAL", "Paid via Cash",
            "Prices inclusive of GST.", "Thanks for stopping by.", "Bill ref: 260-1-000020",
        )
        var at = 0
        for (want in expected) {
            val i = text.drop(at).indexOfFirst { it.startsWith(want) }
            assertTrue("missing or out of order: $want\n${text.joinToString("\n")}", i >= 0)
            at += i + 1
        }
        assertTrue(text.any { it.contains("110020") })
    }

    @Test fun `bill rows carry Odoo's values`() {
        val lines = ReceiptFormatter(32).format(bill000020()).map { it.text }
        assertTrue(lines.contains(ReceiptFormatter(32).twoCol("Bill No.", "ZenZebra - 000020")))
        // 25 characters do not fit beside "Customer" on 32: the value goes right-aligned below it.
        assertTrue(lines.contains("Diwakar (+91 98765 43210)".padStart(32)))
        assertTrue(lines.contains(ReceiptFormatter(32).twoCol("Taxable value", "Rs 4.76")))
        assertTrue(lines.contains(ReceiptFormatter(32).twoCol("GST", "Rs 0.24")))
        assertTrue(lines.contains(ReceiptFormatter(32).twoCol("TOTAL", "Rs 5.00")))
        assertTrue(lines.contains(ReceiptFormatter(32).twoCol("Paid via Cash", "Rs 5.00")))
        assertTrue(lines.any { it.trimEnd().endsWith("1       5.00        5.00") })
    }

    @Test fun `a value too long for one row goes under its label`() {
        val f = ReceiptFormatter(24)
        val rows = f.keyValue("Customer", "Diwakar (+91 98765 43210)")
        assertEquals("Customer", rows.first())
        assertTrue(rows.drop(1).all { it.length == 24 })
        assertEquals("Diwakar (+91 98765 43210)", rows.drop(1).joinToString(" ") { it.trim() })
    }

    @Test fun `24 character paper puts Item above the number columns`() {
        val lines = ReceiptFormatter(24).format(bill000020()).map { it.text }
        val i = lines.indexOf("Item")
        assertTrue(i >= 0)
        assertTrue(lines[i + 1].trimEnd().endsWith("Qty     Rate    Amount"))
    }

    @Test fun `ZZ Cash block only when Odoo shows it`() {
        assertFalse(ReceiptFormatter(32).format(bill000020()).any { it.text.contains("Earned on this bill") })
        val lines = ReceiptFormatter(32).format(long()).map { it.text }
        assertTrue(lines.contains(ReceiptFormatter(32).twoCol("Earned on this bill", "+ Rs 401.44")))
        assertTrue(lines.contains(ReceiptFormatter(32).twoCol("Used on this bill", "- Rs 400")))
        assertTrue(lines.contains(ReceiptFormatter(32).twoCol("Balance", "Rs 36.04")))
        assertTrue(lines.contains(ReceiptFormatter(32).twoCol("Valid till", "26 Nov 2026")))
    }

    @Test fun `card details of the E700 follow the payment`() {
        val lines = ReceiptFormatter(32).format(long()).map { it.text }
        assertTrue(lines.contains(ReceiptFormatter(32).twoCol("Card", "**** 4242")))
        assertTrue(lines.contains(ReceiptFormatter(32).twoCol("RRN", "412345678901")))
    }

    @Test fun `a split bill prints every payment line and every terminal part`() {
        val split = bill000020().copy(
            payments = listOf(ReceiptPayment("Pine Labs E700 (A2A)", Paise(30000)), ReceiptPayment("Pine Labs E700 (A2A)", Paise(15000)),
                ReceiptPayment("Cash", Paise(5000))),
            terminals = listOf(TerminalSlip("UPI", null, "512345678901", null), TerminalSlip("CARD", "4242", "412345678901", "A1")),
        )
        val lines = ReceiptFormatter(32).format(split).map { it.text }
        val f = ReceiptFormatter(32)
        // The method name is too long to share a 32-char line with the amount: the amount goes below it.
        assertEquals(2, lines.count { it == "Paid via Pine Labs E700 (A2A)" })
        assertTrue(lines.contains("Rs 300.00".padStart(32)) && lines.contains("Rs 150.00".padStart(32)))
        assertTrue(lines.contains(f.twoCol("Paid via Cash", "Rs 50.00")))
        assertEquals(listOf("UPI", "CARD"), lines.filter { it.startsWith("Mode") }.map { it.removePrefix("Mode").trim() })
    }

    @Test fun `bill not yet in Odoo says the WhatsApp bill follows`() {
        for (w in listOf(24, 32, 48)) {
            val text = ReceiptFormatter(w).format(bill000020().copy(billNo = "ZENZ261027173400123", billRef = null, pendingInOdoo = true))
                .joinToString(" ") { it.text.trim() }
            assertTrue(text, text.contains("Odoo bill follows on WhatsApp."))
        }
    }

    @Test fun `logo prints first, as an image line`() {
        val lines = ReceiptFormatter(32).format(bill000020().copy(logoBase64 = "iVBORw0KGgo="))
        assertEquals("iVBORw0KGgo=", lines.first().imageBase64)
        assertEquals("ZENZEBRA", lines[1].text.trim())
    }

    @Test fun `long product names wrap instead of truncating`() {
        val f = ReceiptFormatter(24)
        val wrapped = f.wrap("29 TEE overized thirt POP UP FIZZ- orange color S")
        assertTrue(wrapped.size > 1)
        assertEquals("29 TEE overized thirt POP UP FIZZ- orange color S", wrapped.joinToString(" "))
    }
}
