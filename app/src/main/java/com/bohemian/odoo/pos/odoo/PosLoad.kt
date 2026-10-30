package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Odoo's own POS loader, as the web POS uses it (0.8.0): `pos.session.load_data` answers every model
 * the till needs in one call, and with `pos_last_server_date` only what changed since (Odoo 19
 * `pos_load_mixin._server_date_to_domain`: `write_date > date`, except pos.session / pos.config).
 * Checked on the live database, 31 Oct: the products it gives match the till's own reads exactly
 * (2,510 at ZenZebra and at SWN: ids, prices, taxes, tabs, HSN, barcodes, codes, names).
 */
class PosLoad(private val odoo: OdooJson2Client) {

    /** A loader answer and the server's clock when it answered. */
    class Reply(val data: JsonObject, val serverTimeMs: Long?)

    /** Everything, with no product limit (`pos_limited_loading` off: 5,000 otherwise). */
    suspend fun full(sessionId: Int): Reply = load(sessionId, mapOf("pos_limited_loading" to false))

    /** What changed since [sinceUtc] ("yyyy-MM-dd HH:mm:ss"); pos.session and pos.config always come. */
    suspend fun since(sessionId: Int, sinceUtc: String): Reply =
        load(sessionId, mapOf("pos_last_server_date" to sinceUtc, "pos_limited_loading" to true))

    private suspend fun load(sessionId: Int, context: Map<String, Any>): Reply {
        val a = odoo.callWithServerTime("pos.session", "load_data", jsonOf(
            "ids" to listOf(sessionId), "models_to_load" to MODELS, "context" to context,
        ))
        val data = a.value as? JsonObject ?: throw OdooException.Server(200, "BadResponse", "Odoo's POS loader sent no data")
        return Reply(data, a.serverTimeMs)
    }

    /**
     * Variants Odoo did not send with their template (an incremental reply leaves out a variant whose
     * own record did not change, e.g. a product put back on sale), read as plain records.
     */
    suspend fun variants(ids: List<Int>): List<JsonObject> =
        if (ids.isEmpty()) emptyList() else odoo.call("product.product", "read", jsonOf(
            "ids" to ids, "fields" to VARIANT_FIELDS, "load" to null,
        )).objects()

    companion object {
        /** The models the till uses; Odoo loads them in its own order (some need others: variants need templates). */
        val MODELS = listOf(
            "pos.config", "pos.session", "pos.preset", "pos.payment.method", "pos.category", "res.company",
            "product.template", "product.product", "account.tax", "product.pricelist", "product.pricelist.item",
            "account.fiscal.position", "pos.note", "loyalty.program", "loyalty.rule", "loyalty.reward",
        )

        val VARIANT_FIELDS = listOf("display_name", "lst_price", "barcode", "default_code", "product_tmpl_id")

        /** A write committed while the loader read is seen on the next pass: the "since" lags this much. */
        const val OVERLAP_MS = 5 * 60_000L

        /**
         * The next "since": the server's clock minus [OVERLAP_MS] (Odoo echoes the date sent instead of
         * moving `_data_server_date` forward on incremental calls). Never moves backwards.
         */
        fun nextSince(previous: String?, serverTimeMs: Long?): String? {
            val next = serverTimeMs?.let { utc(it - OVERLAP_MS) } ?: return previous
            return if (previous != null && previous > next) previous else next
        }

        fun utc(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms)
    }
}

/** What changed in a merge, by the part of the till it touches. */
data class PosChanges(val models: Set<String>) {
    val config get() = "pos.config" in models || "pos.session" in models
    val products get() = "product.template" in models || "product.product" in models
    val taxes get() = "account.tax" in models || "account.fiscal.position" in models
    val loyalty get() = models.any { it.startsWith("loyalty.") }
    val menu get() = "pos.category" in models
    val notes get() = "pos.note" in models
    val company get() = "res.company" in models || "pos.preset" in models
    val methods get() = "pos.payment.method" in models
    val pricelist get() = "product.pricelist" in models || "product.pricelist.item" in models
    val any get() = models.isNotEmpty()
}

/**
 * What the till holds from the POS loader: Odoo's own records by model and id, so an incremental
 * reply is merged by id and a record equal to the one held (Odoo always re-sends its reward and
 * special products) is no change. The till's objects are built from these records.
 */
class PosCatalog {
    private val records = HashMap<String, LinkedHashMap<Int, JsonObject>>()

    /** Variant id → its price over the template's, kept from the variant's last record (0 at every store today). */
    private val extras = HashMap<Int, Paise>()

    /**
     * Merge a loader answer. [full]: it is the whole picture, so what it does not hold is dropped.
     * Returns what changed.
     */
    fun merge(data: JsonObject, full: Boolean): PosChanges {
        val changed = mutableSetOf<String>()
        for (model in PosLoad.MODELS) {
            val rows = (data[model] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty().map(::stable)
            val held = records.getOrPut(model) { LinkedHashMap() }
            if (full) {
                val fresh = LinkedHashMap<Int, JsonObject>()
                rows.forEach { r -> r.int("id")?.let { fresh[it] = r } }
                if (fresh != held) changed += model
                records[model] = fresh
            } else {
                for (r in rows) {
                    val id = r.int("id") ?: continue
                    if (held[id] != r) { held[id] = r; changed += model }
                }
            }
        }
        // A variant's extra over its template, measured against the template as it is now.
        for (v in (data["product.product"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()) {
            val id = v.int("id") ?: continue
            val t = template(v.int("product_tmpl_id")) ?: continue
            extras[id] = v.money("lst_price") - t.money("list_price")
        }
        return PosChanges(changed)
    }

    /** Odoo adds keys starting with "_" to pos.config each call (`_data_server_date`, …): not part of the record. */
    private fun stable(r: JsonObject): JsonObject = if (r.keys.none { it.startsWith("_") }) r else JsonObject(r.filterKeys { !it.startsWith("_") })

    private fun all(model: String): Collection<JsonObject> = records[model]?.values.orEmpty()
    private fun template(id: Int?): JsonObject? = id?.let { records["product.template"]?.get(it) }

    val sessionId: Int? get() = all("pos.session").firstOrNull()?.int("id")

    /** pos.config with plain ids; the company and preset names from their own records. */
    fun config(): PosConfig? {
        val c = all("pos.config").firstOrNull() ?: return null
        val companyId = c.int("company_id")
        val presetId = c.int("default_preset_id")
        return PosConfig(
            id = c.int("id") ?: return null,
            name = c.str("name") ?: "POS",
            currentSessionId = c.int("current_session_id"),
            sessionOpen = c.str("current_session_state") == "opened",
            paymentMethodIds = c.ids("payment_method_ids"),
            companyName = all("res.company").firstOrNull { it.int("id") == companyId }?.str("name") ?: "",
            companyId = companyId,
            invoiceJournalId = c.int("invoice_journal_id"),
            defaultFiscalPositionId = c.int("default_fiscal_position_id"),
            fiscalPositionIds = c.ids("fiscal_position_ids"),
            cashControl = c.bool("cash_control"),
            manualDiscount = c.bool("manual_discount"),
            defaultPresetId = presetId,
            defaultPresetName = all("pos.preset").firstOrNull { it.int("id") == presetId }?.str("name"),
            noteIds = c.ids("note_ids"),
            limitCategories = c.bool("limit_categories"),
            availableCategIds = c.ids("iface_available_categ_ids"),
            // Odoo's loader leaves it unset when the POS does not use pricelists, as the web POS then prices with none.
            pricelistId = c.int("pricelist_id"),
        )
    }

    /** The products the till sells: variants of templates Odoo marks available in POS, by name then id. */
    fun products(): List<Product> = all("product.product").mapNotNull { v ->
        val id = v.int("id") ?: return@mapNotNull null
        val t = template(v.int("product_tmpl_id"))?.takeIf { (it["available_in_pos"] as? JsonPrimitive)?.booleanOrNull == true } ?: return@mapNotNull null
        Product(
            id = id,
            name = v.str("display_name") ?: t.str("display_name") ?: "?",
            price = t.money("list_price") + (extras[id] ?: Paise.ZERO),
            barcode = v.str("barcode"),
            defaultCode = v.str("default_code"),
            taxIds = t.ids("taxes_id"),
            hsnCode = t.str("l10n_in_hsn_code"),
            categIds = t.ids("pos_categ_ids"),
            writeDate = t.str("write_date"),
        )
    }.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

    /** Variants a sellable template lists that the till does not hold (to read with [PosLoad.variants]). */
    fun missingVariants(): List<Int> {
        val held = records["product.product"]?.keys.orEmpty()
        return all("product.template").filter { (it["available_in_pos"] as? JsonPrimitive)?.booleanOrNull == true }
            .flatMap { it.ids("product_variant_ids") }.filter { it !in held }
    }

    /** Variants read on their own, merged as if the loader had sent them. */
    fun addVariants(rows: List<JsonObject>): PosChanges =
        if (rows.isEmpty()) PosChanges(emptySet()) else merge(JsonObject(mapOf("product.product" to JsonArray(rows))), full = false)

    /** Products no longer sold here (archived, deleted, out of the POS): Odoo's loader never sends those. */
    fun keepOnlyProducts(sellable: Set<Int>): Int {
        val held = records["product.product"] ?: return 0
        val gone = held.values.filter { v ->
            val id = v.int("id") ?: return@filter false
            id !in sellable && (template(v.int("product_tmpl_id"))?.get("available_in_pos") as? JsonPrimitive)?.booleanOrNull == true
        }.mapNotNull { it.int("id") }
        gone.forEach { held.remove(it); extras.remove(it) }
        return gone.size
    }

    fun categories(cfg: PosConfig): List<PosCategory> = CatalogService.limitedTo(cfg, all("pos.category").mapNotNull { c ->
        PosCategory(c.int("id") ?: return@mapNotNull null, c.str("name") ?: "?", c.int("parent_id"), c.int("sequence") ?: 0, c.int("color") ?: 0)
    }.sortedWith(compareBy({ it.sequence }, { it.id })))

    fun notes(): List<PosNote> = all("pos.note").mapNotNull { n -> PosNote(n.int("id") ?: return@mapNotNull null, n.str("name") ?: "?", n.int("color") ?: 0) }

    fun taxNames(): Map<Int, String> = all("account.tax").mapNotNull { t -> t.int("id")?.let { it to (t.str("name") ?: "") } }.toMap()

    fun taxesWithFiscalPosition(): Set<Int> = all("account.tax").filter { it.ids("fiscal_position_ids").isNotEmpty() }.mapNotNull { it.int("id") }.toSet()

    fun fiscalPositionRecords(): List<JsonObject> = all("account.fiscal.position").toList()

    fun paymentMethods(): List<PaymentMethod> = all("pos.payment.method").mapNotNull { m ->
        PaymentMethod(m.int("id") ?: return@mapNotNull null, m.str("name") ?: "?", m.bool("is_cash_count"), m.str("use_payment_terminal"),
            type = m.str("type") ?: "bank", kind = m.str("payment_method_type") ?: "none")
    }

    /** The loyalty models as one `load_data`-shaped object, for `OffersService.fromReply`. */
    fun loyalty(): JsonObject = JsonObject(OffersService.MODELS.associateWith { JsonArray(all(it).toList()) })

    /** ZZ Cash earning rules of [programId], from the loyalty.rule records already held (no extra read). */
    fun loyaltyRules(programId: Int): List<LoyaltyRule> =
        all("loyalty.rule").filter { it.int("program_id") == programId }.mapNotNull { LoyaltyPoints.ruleFromRow(it) }

    /**
     * Odoo's tax and fiscal-position definitions as one fingerprint: the saved prices (F3) are valid
     * only while it is unchanged (account.tax has no write_date in the loader's records).
     */
    fun taxFingerprint(): String {
        val text = (all("account.tax").sortedBy { it.int("id") } + all("account.fiscal.position").sortedBy { it.int("id") }).joinToString("\n")
        return "ld:" + MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun JsonObject.money(key: String): Paise = (this[key] as? JsonPrimitive)?.doubleOrNull?.let { Paise.fromOdoo(it) } ?: Paise.ZERO
}
