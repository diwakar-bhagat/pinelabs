package com.bohemian.odoo.pos.odoo

import kotlinx.serialization.json.JsonObject

class CatalogService(private val odoo: OdooJson2Client) {

    suspend fun configs(): List<PosConfig> =
        odoo.call("pos.config", "search_read", jsonOf(
            "domain" to emptyList<Any>(),
            "fields" to CONFIG_FIELDS,
        )).objects().map { it.toConfig() }

    /**
     * The saved config, or null when it was deleted, archived or is outside this user's
     * record rules. search_read, not read: read by id still returns an archived config.
     */
    suspend fun config(id: Int): PosConfig? =
        odoo.call("pos.config", "search_read", jsonOf(
            "domain" to listOf(listOf("id", "=", id)),
            "fields" to CONFIG_FIELDS,
        )).objects().firstOrNull()?.toConfig()

    suspend fun paymentMethods(ids: List<Int>): List<PaymentMethod> =
        odoo.call("pos.payment.method", "read", jsonOf(
            "ids" to ids, "fields" to listOf("name", "is_cash_count", "use_payment_terminal"),
        )).objects().map {
            PaymentMethod(it.int("id")!!, it.str("name") ?: "?", it.bool("is_cash_count"), it.str("use_payment_terminal"))
        }

    /** All sellable POS products (~2k). Paged to keep each response small on terminal Wi-Fi. */
    suspend fun products(pageSize: Int = 500): List<Product> {
        val out = mutableListOf<Product>()
        var offset = 0
        while (true) {
            val page = odoo.call("product.product", "search_read", jsonOf(
                "domain" to listOf(listOf("available_in_pos", "=", true), listOf("sale_ok", "=", true)),
                "fields" to listOf("display_name", "lst_price", "barcode", "default_code", "taxes_id", "l10n_in_hsn_code",
                    "pos_categ_ids", "write_date"),
                "order" to "name, id", // display_name is not stored → cannot sort on it
                "limit" to pageSize,
                "offset" to offset,
            )).objects()
            page.mapTo(out) { it.toProduct() }
            if (page.size < pageSize) break
            offset += pageSize
        }
        return out
    }

    private fun JsonObject.toConfig() = PosConfig(
        id = int("id")!!,
        name = str("name") ?: "POS",
        currentSessionId = m2oId("current_session_id"),
        sessionOpen = str("current_session_state") == "opened",
        paymentMethodIds = ids("payment_method_ids"),
        companyName = m2oName("company_id") ?: "",
        companyId = m2oId("company_id"),
        invoiceJournalId = m2oId("invoice_journal_id"),
        defaultFiscalPositionId = m2oId("default_fiscal_position_id"),
        fiscalPositionIds = ids("fiscal_position_ids"),
        cashControl = bool("cash_control"),
        manualDiscount = bool("manual_discount"),
        defaultPresetId = m2oId("default_preset_id"),
        defaultPresetName = m2oName("default_preset_id"),
        noteIds = ids("note_ids"),
    )

    private fun JsonObject.toProduct() = Product(
        id = int("id")!!,
        name = str("display_name") ?: "?",
        price = money("lst_price"),
        barcode = str("barcode"),
        defaultCode = str("default_code"),
        taxIds = ids("taxes_id"),
        hsnCode = str("l10n_in_hsn_code"),
        categIds = ids("pos_categ_ids"),
        writeDate = str("write_date"),
    )

    /** The menu tree, in Odoo's tab order. */
    suspend fun categories(): List<PosCategory> =
        odoo.call("pos.category", "search_read", jsonOf(
            "domain" to emptyList<Any>(),
            "fields" to listOf("name", "parent_id", "sequence", "color"),
            "order" to "sequence, id",
        )).objects().map {
            PosCategory(it.int("id")!!, it.str("name") ?: "?", it.m2oId("parent_id"), it.int("sequence") ?: 0, it.int("color") ?: 0)
        }

    /** Note tags; an empty [ids] (pos.config.note_ids unset) means every tag, as in the web POS. */
    suspend fun notes(ids: List<Int>): List<PosNote> =
        odoo.call("pos.note", "search_read", jsonOf(
            "domain" to if (ids.isEmpty()) emptyList<Any>() else listOf(listOf("id", "in", ids)),
            "fields" to listOf("name", "color", "sequence"),
            "order" to "sequence, id",
        )).objects().map { PosNote(it.int("id")!!, it.str("name") ?: "?", it.int("color") ?: 0) }

    /**
     * Product photos as base64 PNG/JPEG (`image_256`, the size the web POS grid uses), through the
     * same authenticated JSON-2 call as everything else. Products without a photo are left out.
     */
    suspend fun images(ids: List<Int>): Map<Int, String> =
        odoo.call("product.product", "read", jsonOf("ids" to ids, "fields" to listOf("image_256")))
            .objects().mapNotNull { r -> r.str("image_256")?.takeIf { it.isNotBlank() }?.let { r.int("id")!! to it } }.toMap()

    /** res.company GST settings — read once at setup, checked before the till may sell. */
    suspend fun companyGst(companyId: Int): CompanyGst =
        odoo.call("res.company", "read", jsonOf(
            "ids" to listOf(companyId),
            "fields" to listOf("name", "state_id", "l10n_in_is_gst_registered", "l10n_in_hsn_code_digit", "vat"),
        )).objects().first().let {
            CompanyGst(
                id = it.int("id")!!,
                name = it.str("name") ?: "",
                stateId = it.m2oId("state_id"),
                stateName = it.m2oName("state_id"),
                isGstRegistered = it.bool("l10n_in_is_gst_registered"),
                hsnCodeDigits = it.str("l10n_in_hsn_code_digit")?.toIntOrNull(),
                gstin = it.str("vat"),
            )
        }

    private companion object {
        val CONFIG_FIELDS = listOf(
            "name", "current_session_id", "current_session_state", "payment_method_ids",
            "company_id", "invoice_journal_id", "default_fiscal_position_id", "fiscal_position_ids",
            "cash_control", "manual_discount", "default_preset_id", "note_ids",
        )
    }
}
