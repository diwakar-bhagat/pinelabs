package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.gst.GstTreatment

data class PosConfig(
    val id: Int,
    val name: String,
    val currentSessionId: Int?,
    val sessionOpen: Boolean,
    val paymentMethodIds: List<Int>,
    val companyName: String,
    val companyId: Int? = null,
    /** pos.config.invoice_journal_id — Odoo raises on sync_from_ui when unset (G5). */
    val invoiceJournalId: Int? = null,
    /** pos.config.default_fiscal_position_id */
    val defaultFiscalPositionId: Int? = null,
    /** pos.config.fiscal_position_ids — the only positions selectable on this till. */
    val fiscalPositionIds: List<Int> = emptyList(),
    /** pos.config.cash_control (computed: a cash-count method exists) — closing needs counted cash. */
    val cashControl: Boolean = false,
)

/** res.company GST settings, needed before the till may sell. */
data class CompanyGst(
    val id: Int,
    val name: String,
    /** res.company.state_id — invoicing and session close are blocked without it. */
    val stateId: Int?,
    val stateName: String?,
    /** res.company.l10n_in_is_gst_registered */
    val isGstRegistered: Boolean,
    /** res.company.l10n_in_hsn_code_digit ("4" | "6" | "8"), null when not enforced. */
    val hsnCodeDigits: Int?,
    /** res.company.vat — the merchant GSTIN printed on the tax invoice. */
    val gstin: String?,
)

/**
 * account.fiscal.position. `taxMap` is Odoo's computed `tax_map`
 * ({source tax id: [destination tax ids]}), used by [FiscalPositionService.mapTaxes].
 */
data class FiscalPosition(
    val id: Int,
    val name: String,
    val taxMap: Map<Int, List<Int>>,
    /** Destination taxes on the position. Empty means "map nothing" (see map_tax). */
    val taxIds: List<Int>,
)

data class PaymentMethod(val id: Int, val name: String, val isCash: Boolean, val terminal: String?)

data class Product(
    val id: Int,
    val name: String,
    val price: Paise,          // lst_price (tax-included for GST-included taxes)
    val barcode: String?,
    val defaultCode: String?,
    val taxIds: List<Int>,
    /** product.template.l10n_in_hsn_code — required on every sold line when GST-registered. */
    val hsnCode: String? = null,
)

data class Customer(
    val id: Int,
    val name: String,
    val phone: String?,
    /** res.partner.vat — the customer GSTIN. */
    val gstin: String? = null,
    /** res.partner.l10n_in_gst_treatment */
    val gstTreatment: GstTreatment? = null,
    /** res.partner.state_id — decides place of supply, hence CGST+SGST vs IGST. */
    val stateId: Int? = null,
    val stateName: String? = null,
    /** res.partner.property_account_position_id */
    val fiscalPositionId: Int? = null,
)

data class CartLine(
    val uuid: String,
    val product: Product,
    val qty: Int,
    /** Filled by PricingService from Odoo account.tax.compute_all. */
    val totalIncl: Paise? = null,
    val totalExcl: Paise? = null,
    /**
     * Taxes actually applied, after the order's fiscal position was mapped over
     * [Product.taxIds]. Diagnostic only: `pos.order.line.tax_ids` must carry the
     * UNMAPPED product taxes, because Odoo re-derives
     * `tax_ids_after_fiscal_position` from the order's fiscal position itself.
     */
    val appliedTaxIds: List<Int> = emptyList(),
) {
    val isPriced: Boolean get() = totalIncl != null && totalExcl != null
}

data class CartTotals(val totalIncl: Paise, val totalExcl: Paise) {
    val tax: Paise get() = totalIncl - totalExcl
}
