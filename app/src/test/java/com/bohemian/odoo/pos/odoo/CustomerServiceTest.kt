package com.bohemian.odoo.pos.odoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomerServiceTest {

    /** normalizeIndianMobile is pure — no Odoo calls. */
    private val svc = CustomerService(odoo = stubOdoo())

    @Test fun `full 10 digits normalize to E164`() {
        assertEquals("+917048980055", svc.normalizeIndianMobile("7048980055"))
    }

    @Test fun `with country code prefix`() {
        assertEquals("+917048980055", svc.normalizeIndianMobile("+917048980055"))
        assertEquals("+917048980055", svc.normalizeIndianMobile("917048980055"))
    }

    @Test fun `with leading zero`() {
        assertEquals("+917048980055", svc.normalizeIndianMobile("07048980055"))
    }

    @Test fun `with spaces and dashes`() {
        assertEquals("+917048980055", svc.normalizeIndianMobile("+91 70489 80055"))
        assertEquals("+917048980055", svc.normalizeIndianMobile("70489-80055"))
    }

    @Test fun `rejects short numbers`() {
        assertNull(svc.normalizeIndianMobile("12345"))
    }

    @Test fun `rejects invalid first digit`() {
        // Indian mobile numbers start with 6, 7, 8, or 9.
        assertNull(svc.normalizeIndianMobile("5048980055"))
    }

    @Test fun `rejects too many digits`() {
        assertNull(svc.normalizeIndianMobile("704898005512"))
    }

    private companion object {
        /** Stub that throws if any Odoo call is attempted — these tests are pure. */
        fun stubOdoo() = OdooJson2Client(
            baseUrl = "http://localhost",
            database = "test",
            apiKey = { "test-key" },
        )
    }
}
