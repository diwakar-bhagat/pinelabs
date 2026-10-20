package com.bohemian.odoo.pos.gst

/**
 * l10n_in `res.partner.l10n_in_gst_treatment`.
 * Values copied verbatim from addons/l10n_in/models/res_partner.py (Odoo 19.0).
 */
enum class GstTreatment(val odoo: String, val label: String) {
    REGULAR("regular", "Registered Business - Regular"),
    COMPOSITION("composition", "Registered Business - Composition"),
    UNREGISTERED("unregistered", "Unregistered Business"),
    CONSUMER("consumer", "Consumer"),
    OVERSEAS("overseas", "Overseas"),
    SPECIAL_ECONOMIC_ZONE("special_economic_zone", "Special Economic Zone"),
    DEEMED_EXPORT("deemed_export", "Deemed Export"),
    UIN_HOLDERS("uin_holders", "UIN Holders");

    companion object {
        fun fromOdoo(v: String?): GstTreatment? = v?.let { s -> entries.firstOrNull { it.odoo == s } }

        /**
         * The treatments for which the Odoo POS does NOT raise an invoice.
         * Source: l10n_in_pos/static/src/app/models/pos_order.js `setPartner`.
         */
        val NON_INVOICEABLE = setOf(UNREGISTERED, CONSUMER, OVERSEAS)
    }
}
