package com.bohemian.odoo.pos.odoo

import kotlinx.serialization.json.JsonObject

class CatalogService(private val odoo: OdooJson2Client) {

    suspend fun configs(): List<PosConfig> =
        odoo.call("pos.config", "search_read", jsonOf(
            "domain" to emptyList<Any>(),
            "fields" to CONFIG_FIELDS,
        )).objects().map { it.toConfig() }

    suspend fun config(id: Int): PosConfig =
        odoo.call("pos.config", "read", jsonOf(
            "ids" to listOf(id),
            "fields" to CONFIG_FIELDS,
        )).objects().first().toConfig()

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
                "fields" to listOf("display_name", "lst_price", "barcode", "default_code", "taxes_id", "l10n_in_hsn_code"),
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
    )

    private fun JsonObject.toProduct() = Product(
        id = int("id")!!,
        name = str("display_name") ?: "?",
        price = money("lst_price"),
        barcode = str("barcode"),
        defaultCode = str("default_code"),
        taxIds = ids("taxes_id"),
        hsnCode = str("l10n_in_hsn_code"),
    )

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
        )
    }
}
