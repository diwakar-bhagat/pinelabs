package com.bohemian.odoo.pos.receipt

import com.bohemian.odoo.pos.core.Paise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptFormatterTest {
    private fun sample() = ReceiptData(
        storeName = "ZenZebra", storeLines = listOf("KLJ Office Shelf"),
        orderRef = "KLJX260921153000123", dateTime = "21-Sep-2026 15:30",
        items = listOf(
            ReceiptItem("29 TEE overized thirt POP UP FIZZ- orange color S", 1, Paise(69900), Paise(69900)),
            ReceiptItem("Makino Corn Munch Cream & Onion", 2, Paise(7000), Paise(14000)),
        ),
        subtotalExcl = Paise(72573), tax = Paise(11327), total = Paise(83900),
        paymentLabel = "UPI", paymentRef = "412345678901", authCode = null, cardLast4 = null,
        customerName = "Priya", footer = listOf("Thank you for shopping!"),
    )

    @Test fun `no line exceeds any supported width`() {
        for (w in listOf(24, 32, 48)) {
            val lines = ReceiptFormatter(w).format(sample())
            lines.forEach { assertTrue("width $w: '${it.text}'", it.text.length <= w) }
        }
    }

    @Test fun `the brand prints as ZenZebra, not upper-cased`() {
        val header = ReceiptFormatter(32).format(sample()).first()
        assertEquals("ZenZebra", header.text.trim())
        assertTrue(header.bold)
    }

    @Test fun `total is right aligned and fully visible`() {
        val lines = ReceiptFormatter(32).format(sample()).map { it.text }
        val total = lines.single { it.startsWith("TOTAL") }
        assertTrue(total.endsWith("Rs 839.00"))
        assertEquals(32, total.length)
    }

    @Test fun `long product names wrap instead of truncating`() {
        val f = ReceiptFormatter(24)
        val wrapped = f.wrap("29 TEE overized thirt POP UP FIZZ- orange color S")
        assertTrue(wrapped.size > 1)
        assertEquals("29 TEE overized thirt POP UP FIZZ- orange color S", wrapped.joinToString(" "))
    }

    @Test fun `words longer than the line are hard split`() {
        val w = ReceiptFormatter(24).wrap("A".repeat(50))
        assertEquals(listOf(24, 24, 2), w.map { it.length })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported width rejected`() { ReceiptFormatter(40) }
}
