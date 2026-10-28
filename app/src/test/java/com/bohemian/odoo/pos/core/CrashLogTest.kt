package com.bohemian.odoo.pos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `phone numbers and PANs keep only the last four digits`() {
        assertEquals("customer ******3210 failed", CrashLog.redact("customer 9876543210 failed"))
        assertEquals("pan ************1111", CrashLog.redact("pan 4111111111111111"))
        assertEquals("+********3210", CrashLog.redact("+919876543210"))
    }

    @Test fun `bearer tokens and api keys are removed`() {
        assertEquals("Authorization: bearer ***", CrashLog.redact("Authorization: bearer abc123SECRET"))
        assertEquals("api_key=*** next", CrashLog.redact("api_key=abc123SECRET next"))
    }

    @Test fun `short numbers like line numbers and amounts survive`() {
        val s = "at PaymentCoordinator.kt:212 amount 52800 ref KLJX2610"
        assertEquals(s, CrashLog.redact(s))
    }

    @Test fun `report is written redacted and only the newest ten are kept`() {
        var now = 1_000L
        val log = CrashLog(tmp.root, build = "0.4.0 (4) PROD") { now++ }
        repeat(12) { log.write("main", IllegalStateException("phone 9876543210")) }

        val reports = log.reports()
        assertEquals(CrashLog.KEEP, reports.size)
        assertTrue(reports.first().name.endsWith("1011.txt")) // newest first
        val text = reports.first().readText()
        assertTrue(text.contains("IllegalStateException"))
        assertTrue(text.contains("app: 0.4.0 (4) PROD")) // which build crashed
        assertTrue(text.contains("******3210"))
        assertFalse(text.contains("9876543210"))

        log.clear()
        assertTrue(log.reports().isEmpty())
    }
}
