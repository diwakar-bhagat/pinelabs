package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.PosLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

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

    /**
     * Every product this POS may sell (2,421 on 2026-10-27, most of them without a menu tab), sorted
     * by name. Paged to keep each response small on terminal Wi-Fi, and checked against Odoo's own
     * count: a catalogue with products missing is never returned (it would replace a complete one).
     *
     * @throws CatalogIncomplete when two full passes still miss products.
     */
    suspend fun products(cfg: PosConfig, pageSize: Int = PAGE_SIZE): List<Product> {
        val domain = productDomain(cfg)
        var pass = fetchProducts(cfg.id, domain, pageSize)
        // Products added or archived while paging shift the offsets; one more pass settles it.
        if (!pass.complete) pass = fetchProducts(cfg.id, domain, pageSize)
        if (!pass.complete) {
            PosLog.w("CATALOG_INCOMPLETE", "config" to cfg.id, "expected" to pass.expected, "got" to pass.products.size)
            throw CatalogIncomplete(pass.expected, pass.products.size)
        }
        return pass.products.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
    }

    private class ProductPass(val expected: Int, val products: List<Product>) {
        val complete: Boolean get() = products.size >= expected
    }

    private suspend fun fetchProducts(configId: Int, domain: List<Any>, pageSize: Int): ProductPass {
        val expected = odoo.call("product.product", "search_count", jsonOf("domain" to domain)).jsonPrimitive.int
        PosLog.i("CATALOG_START", "config" to configId, "expected" to expected)
        val byId = LinkedHashMap<Int, Product>()
        var duplicates = 0
        var offset = 0
        var end: String
        while (true) {
            val page = odoo.call("product.product", "search_read", jsonOf(
                "domain" to domain,
                "fields" to listOf("display_name", "lst_price", "barcode", "default_code", "taxes_id", "l10n_in_hsn_code",
                    "pos_categ_ids", "write_date"),
                "order" to "id", // stable while paging; the grid is sorted by name afterwards
                "limit" to pageSize,
                "offset" to offset,
            )).objects()
            val before = byId.size
            for (row in page) { val p = row.toProduct(); if (byId.put(p.id, p) != null) duplicates++ }
            PosLog.i("CATALOG_PAGE", "offset" to offset, "rows" to page.size)
            // Odoo may cap a page below what was asked: only an empty page, or a short one once the
            // count is reached, ends the walk. A page with nothing new stops a server that ignores offset.
            end = when {
                page.isEmpty() -> "empty_page"
                byId.size == before -> "no_new_rows"
                page.size < pageSize && byId.size >= expected -> "short_page"
                else -> ""
            }
            if (end.isNotEmpty()) break
            offset += page.size
        }
        PosLog.i("CATALOG_DONE", "config" to configId, "expected" to expected, "unique" to byId.size,
            "duplicates" to duplicates, "uncategorised" to byId.values.count { it.categIds.isEmpty() }, "end" to end)
        return ProductPass(expected, byId.values.toList())
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
        limitCategories = bool("limit_categories"),
        availableCategIds = ids("iface_available_categ_ids"),
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

    /**
     * The menu tree, in Odoo's tab order. A POS that limits its categories shows only those and
     * everything below them (Odoo 19 `_get_available_categories`).
     */
    suspend fun categories(cfg: PosConfig): List<PosCategory> {
        val all = odoo.call("pos.category", "search_read", jsonOf(
            "domain" to emptyList<Any>(),
            "fields" to listOf("name", "parent_id", "sequence", "color"),
            "order" to "sequence, id",
        )).objects().map {
            PosCategory(it.int("id")!!, it.str("name") ?: "?", it.m2oId("parent_id"), it.int("sequence") ?: 0, it.int("color") ?: 0)
        }
        if (!cfg.limitCategories || cfg.availableCategIds.isEmpty()) return all
        val tree = MenuTree(all)
        val allowed = cfg.availableCategIds.flatMap { tree.subtree(it) }.toSet()
        return all.filter { it.id in allowed }
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

    companion object {
        const val PAGE_SIZE = 500

        private val CONFIG_FIELDS = listOf(
            "name", "current_session_id", "current_session_state", "payment_method_ids",
            "company_id", "invoice_journal_id", "default_fiscal_position_id", "fiscal_position_ids",
            "cash_control", "manual_discount", "default_preset_id", "note_ids",
            "limit_categories", "iface_available_categ_ids",
        )

        /**
         * The products the Odoo 19 web POS loads for [cfg]: available in POS and sellable, of the
         * config's company or of none, and — only when the config limits its categories — in those
         * categories or below. Archived products are left out by Odoo itself.
         */
        fun productDomain(cfg: PosConfig): List<Any> = buildList {
            add(listOf("available_in_pos", "=", true))
            add(listOf("sale_ok", "=", true))
            cfg.companyId?.let { add("|"); add(listOf("company_id", "=", false)); add(listOf("company_id", "parent_of", listOf(it))) }
            if (cfg.limitCategories && cfg.availableCategIds.isNotEmpty()) {
                add(listOf("pos_categ_ids", "child_of", cfg.availableCategIds))
            }
        }
    }
}

/** Odoo has more products for this POS than the till could fetch; the last complete catalogue stays. */
class CatalogIncomplete(val expected: Int, val got: Int) :
    Exception("Odoo sent $got of $expected products")
