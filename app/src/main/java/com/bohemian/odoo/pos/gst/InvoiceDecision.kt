package com.bohemian.odoo.pos.gst

import com.bohemian.odoo.pos.odoo.Customer

/**
 * Decides `pos.order.to_invoice`, and validates a GSTIN.
 *
 * Port of l10n_in_pos/static/src/app/models/pos_order.js `setPartner` (Odoo 19.0):
 *
 *     if (!partner?.vat || !l10n_in_gst_treatment ||
 *         ["unregistered","consumer","overseas"].includes(l10n_in_gst_treatment)) {
 *         this.setToInvoice(false);
 *     } else {
 *         this.setToInvoice(true);
 *     }
 *
 * A walk-in sale (no customer) is never invoiced.
 */
object InvoiceDecision {

    fun toInvoice(customer: Customer?): Boolean {
        val gstin = customer?.gstin?.takeIf { it.isNotBlank() } ?: return false
        val treatment = customer.gstTreatment ?: return false
        if (treatment in GstTreatment.NON_INVOICEABLE) return false
        return isValidGstin(gstin)
    }

    /**
     * GSTIN shape: 2-digit state TIN, 10-char PAN, 1 entity digit, 'Z', 1 checksum char.
     * Mirrors the structure l10n_in relies on in `res_partner.py` (`vat[:2]` state TIN,
     * `vat[2:12]` PAN). The checksum itself is validated by Odoo on write; we only
     * reject obviously malformed input before it reaches the server.
     */
    private val GSTIN = Regex("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]Z[0-9A-Z]$")

    fun isValidGstin(raw: String): Boolean = GSTIN.matches(raw.trim().uppercase())

    /** First two digits of a GSTIN are the state TIN (`res.country.state.l10n_in_tin`). */
    fun stateTinOf(gstin: String): String? =
        gstin.trim().uppercase().takeIf { isValidGstin(it) }?.take(2)

    /** TIN "96" is "Other Country" (`l10n_in.state_in_oc`); "99" is not a real state. */
    fun isForeignTin(tin: String?): Boolean = tin == "96" || tin == "99"
}
