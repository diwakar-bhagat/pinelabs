package com.bohemian.odoo.pos.gst

import com.bohemian.odoo.pos.odoo.Customer
import com.bohemian.odoo.pos.odoo.FiscalPosition
import com.bohemian.odoo.pos.odoo.FiscalPositionService
import com.bohemian.odoo.pos.odoo.PosConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour is pinned to Odoo 19.0 (branch 19.0, commit c10bb5bb):
 *  - to_invoice  : l10n_in_pos/static/src/app/models/pos_order.js `setPartner`
 *  - map_tax     : account/models/partner.py `map_tax` / `_compute_tax_map`
 *  - fpos choice : point_of_sale/static/src/app/models/pos_order.js
 */
class GstFoundationTest {

    private val gstinDelhi = "07AABCU9603R1ZM"
    private val gstinHaryana = "06AABCU9603R1Z2"

    private fun customer(
        gstin: String? = null,
        treatment: GstTreatment? = null,
        fpos: Int? = null,
    ) = Customer(1, "Acme", "+919810000000", gstin, treatment, null, null, fpos)

    // ---------------------------------------------------------------- to_invoice

    @Test fun `walk-in sale is never invoiced`() {
        assertFalse(InvoiceDecision.toInvoice(null))
    }

    @Test fun `customer without GSTIN is not invoiced`() {
        assertFalse(InvoiceDecision.toInvoice(customer(treatment = GstTreatment.REGULAR)))
    }

    @Test fun `customer without a GST treatment is not invoiced`() {
        assertFalse(InvoiceDecision.toInvoice(customer(gstin = gstinDelhi)))
    }

    @Test fun `consumer unregistered and overseas are not invoiced even with a GSTIN`() {
        for (t in listOf(GstTreatment.CONSUMER, GstTreatment.UNREGISTERED, GstTreatment.OVERSEAS)) {
            assertFalse("$t must not be invoiced", InvoiceDecision.toInvoice(customer(gstinDelhi, t)))
        }
    }

    @Test fun `registered business with a GSTIN is invoiced`() {
        for (t in listOf(GstTreatment.REGULAR, GstTreatment.COMPOSITION, GstTreatment.SPECIAL_ECONOMIC_ZONE,
                         GstTreatment.DEEMED_EXPORT, GstTreatment.UIN_HOLDERS)) {
            assertTrue("$t must be invoiced", InvoiceDecision.toInvoice(customer(gstinDelhi, t)))
        }
    }

    @Test fun `a malformed GSTIN never reaches Odoo as invoiceable`() {
        assertFalse(InvoiceDecision.toInvoice(customer("NOTAGSTIN", GstTreatment.REGULAR)))
        assertFalse(InvoiceDecision.isValidGstin("07AABCU9603R1Z"))   // too short
        assertFalse(InvoiceDecision.isValidGstin("7AABCU9603R1ZM"))   // 1-digit state
        assertFalse(InvoiceDecision.isValidGstin("07AABCU9603R1AM"))  // 14th char not Z
        assertTrue(InvoiceDecision.isValidGstin(gstinDelhi))
        assertTrue(InvoiceDecision.isValidGstin(" 07aabcu9603r1zm "))  // trimmed, upper-cased
    }

    @Test fun `state TIN comes from the first two GSTIN digits`() {
        assertEquals("07", InvoiceDecision.stateTinOf(gstinDelhi))
        assertEquals("06", InvoiceDecision.stateTinOf(gstinHaryana))
        assertNull(InvoiceDecision.stateTinOf("garbage"))
        assertTrue(InvoiceDecision.isForeignTin("96"))
        assertFalse(InvoiceDecision.isForeignTin("07"))
    }

    // ---------------------------------------------------------------- map_tax

    // mapTaxes and resolve are pure; the client is constructed but never called.
    private val svc = FiscalPositionService(
        com.bohemian.odoo.pos.odoo.OdooJson2Client("http://unused", "unused", { "unused" })
    )

    /** CGST(1)+SGST(2) replaced by IGST(3) — the inter-state case. */
    private val igst = FiscalPosition(10, "Inter-State", mapOf(1 to listOf(3), 2 to listOf(3)), listOf(3))

    @Test fun `no fiscal position leaves taxes untouched`() {
        assertEquals(listOf(1, 2), svc.mapTaxes(null, listOf(1, 2)))
    }

    @Test fun `CGST and SGST collapse to a single IGST`() {
        // unique() in Odoo: IGST appears once even though two source taxes map to it.
        assertEquals(listOf(3), svc.mapTaxes(igst, listOf(1, 2)))
    }

    @Test fun `an unmapped tax passes through unchanged`() {
        assertEquals(listOf(3, 9), svc.mapTaxes(igst, listOf(1, 9)))
    }

    @Test fun `a position with no destination taxes maps nothing`() {
        val empty = FiscalPosition(11, "Empty", emptyMap(), emptyList())
        assertEquals(listOf(1, 2), svc.mapTaxes(empty, listOf(1, 2)))
    }

    // ------------------------------------------------- which position applies

    private val cfg = PosConfig(
        id = 1, name = "E700", currentSessionId = 5, sessionOpen = true,
        paymentMethodIds = listOf(1), companyName = "Bohemian",
        defaultFiscalPositionId = 20, fiscalPositionIds = listOf(10, 20),
    )

    @Test fun `till default applies to a walk-in`() {
        assertEquals(20, svc.resolve(cfg, null))
    }

    @Test fun `customer position wins when the till allows it`() {
        assertEquals(10, svc.resolve(cfg, customer(fpos = 10)))
    }

    @Test fun `customer position is ignored when the till does not allow it`() {
        assertEquals(20, svc.resolve(cfg, customer(fpos = 99)))
    }
}
