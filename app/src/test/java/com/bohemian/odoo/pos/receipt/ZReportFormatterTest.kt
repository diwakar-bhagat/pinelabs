package com.bohemian.odoo.pos.receipt

import com.bohemian.odoo.pos.core.Paise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZReportFormatterTest {
    private fun sample(counted: Paise? = Paise(259000)) = ZReportData(
        storeName = "Bohemian Curations Pvt Ltd", configName = "ZenZebra KLJ",
        sessionName = "#42", printedAt = "23-Oct-2026 21:05",
        orderCount = 12, orderTotal = Paise(839050),
        cash = ZReportCash("Cash", opening = Paise(100000), sales = Paise(170000), moves = Paise(-10000),
            expected = Paise(260000), counted = counted),
        methods = listOf(ZReportMethod("Pine Labs E700 (A2A)", 9, Paise(669050))),
        notes = "Short by 10, logged",
    )

    @Test fun `no line exceeds any supported width`() {
        for (w in listOf(24, 32, 48)) {
            ZReportFormatter(w).format(sample()).forEach { assertTrue("width $w: '${it.text}'", it.text.length <= w) }
        }
    }

    @Test fun `shows Odoo totals and the counted difference`() {
        val lines = ZReportFormatter(32).format(sample()).map { it.text }
        assertTrue(lines.any { it.startsWith("Sales total") && it.endsWith("8390.50") })
        assertTrue(lines.any { it.trim().startsWith("Expected") && it.endsWith("2600.00") })
        assertTrue(lines.any { it.trim().startsWith("Counted") && it.endsWith("2590.00") })
        assertTrue(lines.any { it.trim().startsWith("Difference") && it.endsWith("-10.00") })
        assertTrue(lines.any { it.trim().startsWith("9 payment(s)") && it.endsWith("6690.50") })
    }

    @Test fun `difference is omitted when cash was not counted`() {
        val lines = ZReportFormatter(32).format(sample(counted = null)).map { it.text.trim() }
        assertFalse(lines.any { it.startsWith("Counted") || it.startsWith("Difference") })
    }

    @Test fun `cash difference is counted minus expected`() {
        assertEquals(Paise(-1000), sample().cash!!.difference)
    }
}
