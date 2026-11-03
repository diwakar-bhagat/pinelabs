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

    @Test fun `a mobile starting with 91 keeps its 91 (it was rejected before 0_10_0)`() {
        assertEquals("+919123456789", svc.normalizeIndianMobile("9123456789"))
        assertEquals("+919123456789", svc.normalizeIndianMobile("919123456789"))
        assertEquals("+919123456789", svc.normalizeIndianMobile("+91 91234 56789"))
        assertEquals("+919123456789", svc.normalizeIndianMobile("09123456789"))
    }

    @Test fun `numbers show as Odoo's customer list shows them`() {
        assertEquals("+91 98212 71573", CustomerService.displayPhone("+919821271573"))
        assertEquals("+91 98212 71573", CustomerService.displayPhone("+91 98212 71573"))
        assertEquals("+91 98212 71573", CustomerService.displayPhone("9821271573"))
        assertEquals("+971 50 123 4567", CustomerService.displayPhone("+971 50 123 4567"))
        assertNull(CustomerService.displayPhone(null))
        assertNull(CustomerService.displayPhone("  "))
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
