package com.bohemian.odoo.pos.odoo

import kotlinx.serialization.json.JsonObject

class CatalogService(private val odoo: OdooJson2Client) {

    suspend fun configs(): List<PosConfig> =
        odoo.call("pos.config", "search_read", jsonOf(
            "domain" to emptyList<Any>(),
            "fields" to listOf("name", "current_session_id", "current_session_state", "payment_method_ids", "company_id"),
        )).objects().map { it.toConfig() }

    suspend fun config(id: Int): PosConfig =
        odoo.call("pos.config", "read", jsonOf(
            "ids" to listOf(id),
            "fields" to listOf("name", "current_session_id", "current_session_state", "payment_method_ids", "company_id"),
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
                "fields" to listOf("display_name", "lst_price", "barcode", "default_code", "taxes_id"),
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
    )

    private fun JsonObject.toProduct() = Product(
        id = int("id")!!,
        name = str("display_name") ?: "?",
        price = money("lst_price"),
        barcode = str("barcode"),
        defaultCode = str("default_code"),
        taxIds = ids("taxes_id"),
    )
}
