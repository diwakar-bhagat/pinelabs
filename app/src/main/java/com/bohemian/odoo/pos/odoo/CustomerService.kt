package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.gst.GstTreatment
import com.bohemian.odoo.pos.gst.InvoiceDecision
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phone / GSTIN → res.partner. Needed for the WhatsApp bill, ZZ Cash, and — for a
 * registered business — the GST tax invoice and the place of supply that decides
 * CGST+SGST vs IGST.
 */
class CustomerService(private val odoo: OdooJson2Client) {

    /** Accepts "98xxxxxxxx", "+91 98xx…", etc. Returns E.164 "+91XXXXXXXXXX" or null. */
    fun normalizeIndianMobile(input: String): String? {
        val d = input.filter { it.isDigit() }.removePrefix("91").let { if (it.length == 11 && it.startsWith("0")) it.drop(1) else it }
        return if (d.length == 10 && d.first() in "6789") "+91$d" else null
    }

    /** Odoo 19 has no res.partner.mobile; phone_sanitized holds E.164 ("+919910605187"). */
    suspend fun findByPhone(e164: String): Customer? = search(listOf("phone_sanitized", "=", e164))

    /**
     * "Name or mobile" in the customer dialog: [MIN_DIGITS]+ digits search the mobile's first or last
     * digits, [MIN_LETTERS]+ letters search the name. One page of [limit] from [offset], A→Z, so every
     * match can be reached by scrolling. Empty when too little is typed.
     */
    suspend fun search(query: String, offset: Int = 0, limit: Int = PAGE): List<Customer> =
        if (isPhoneQuery(query)) searchByPhone(query, offset, limit) else searchByName(query, offset, limit)

    /**
     * Search by the first or the last digits of the mobile (minimum 4): cashiers remember either end.
     * One call: `phone_sanitized` (E.164, "+919910605187") ends with the digits, or its Indian number
     * starts with them. A full number typed with 91 / 0 in front is searched without it.
     */
    suspend fun searchByPhone(digits: String, offset: Int = 0, limit: Int = PAGE): List<Customer> {
        val clean = nationalDigits(digits)
        if (clean.length < MIN_DIGITS) return emptyList()
        return page(phoneDomain(clean), offset, limit)
    }

    /**
     * Customers whose name (or email) contains [text], A→Z, those whose name starts with it first in each
     * page: "ra" lists Rahul and Ravi, then Sara.
     */
    suspend fun searchByName(text: String, offset: Int = 0, limit: Int = PAGE): List<Customer> {
        val q = text.trim()
        if (q.count { it.isLetter() } < MIN_LETTERS) return emptyList()
        return page(listOf("|", listOf("name", "ilike", q), listOf("email", "ilike", q)), offset, limit)
            .sortedBy { if (it.name.startsWith(q, ignoreCase = true)) 0 else 1 }
    }

    private suspend fun page(domain: List<Any?>, offset: Int, limit: Int): List<Customer> =
        odoo.call("res.partner", "search_read", jsonOf(
            "domain" to domain,
            "fields" to PARTNER_FIELDS,
            "offset" to offset,
            "limit" to limit,
            "order" to "name, id",
        )).objects().map { it.toCustomer() }

    /** Look a business up by its GSTIN, so a repeat B2B customer is not re-created. */
    suspend fun findByGstin(gstin: String): Customer? =
        search(listOf("vat", "=", gstin.trim().uppercase()))

    private suspend fun search(term: List<Any?>): Customer? =
        odoo.call("res.partner", "search_read", jsonOf(
            "domain" to listOf(term),
            "fields" to PARTNER_FIELDS,
            "limit" to 1,
        )).objects().firstOrNull()?.toCustomer()

    suspend fun create(
        name: String,
        e164: String?,
        gstin: String? = null,
        gstTreatment: GstTreatment? = null,
        stateId: Int? = null,
    ): Customer {
        val clean = gstin?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        require(clean == null || InvoiceDecision.isValidGstin(clean)) { "GSTIN is not valid: $clean" }

        // Odoo derives the state from the GSTIN's first two digits; do the same so the
        // place of supply is right even when the cashier did not pick a state.
        val resolvedState = stateId ?: clean?.let { InvoiceDecision.stateTinOf(it) }
            ?.takeIf { !InvoiceDecision.isForeignTin(it) }
            ?.let { findStateByTin(it) }

        val vals = buildMap<String, Any?> {
            put("name", name.ifBlank { e164?.let { "Customer ${it.takeLast(4)}" } ?: "Walk-in customer" })
            e164?.let { put("phone", it) }
            clean?.let { put("vat", it) }
            // A GSTIN with no stated treatment is a regular registered business.
            (gstTreatment ?: clean?.let { GstTreatment.REGULAR })?.let { put("l10n_in_gst_treatment", it.odoo) }
            resolvedState?.let { put("state_id", it) }
        }
        val id = odoo.call("res.partner", "create", jsonOf("vals_list" to listOf(vals)))
            .jsonArray.first().jsonPrimitive.intOrNull!!
        // Read back: Odoo computes fields (state from VAT, fiscal position) on write.
        return read(id) ?: Customer(id, name, e164, clean, gstTreatment, resolvedState)
    }

    suspend fun read(id: Int): Customer? =
        odoo.call("res.partner", "read", jsonOf("ids" to listOf(id), "fields" to PARTNER_FIELDS))
            .objects().firstOrNull()?.toCustomer()

    /**
     * Every customer with a mobile, for the till's [CustomerIndex]: pages of [INDEX_PAGE] by id, and
     * Odoo's clock at the first page (the next delta asks from there, less the overlap).
     */
    suspend fun indexAll(): Pair<List<IndexedCustomer>, Long?> {
        val all = ArrayList<IndexedCustomer>()
        var afterId = 0
        var serverTime: Long? = null
        while (true) {
            val a = odoo.callWithServerTime("res.partner", "search_read", jsonOf(
                "domain" to listOf(listOf("phone_sanitized", "!=", false), listOf("id", ">", afterId)),
                "fields" to INDEX_FIELDS,
                "order" to "id",
                "limit" to INDEX_PAGE,
            ))
            if (serverTime == null) serverTime = a.serverTimeMs
            val rows = a.value.objects()
            all += indexRows(rows)
            if (rows.size < INDEX_PAGE) return all to serverTime
            afterId = rows.last().int("id") ?: return all to serverTime
        }
    }

    /**
     * Customers changed in Odoo since [sinceUtc], archived ones too (`active_test` off), with Odoo's clock.
     * Null when more changed than one read brings (an import): the index is then read in full again.
     */
    suspend fun indexChanges(sinceUtc: String): IndexDelta? {
        val a = odoo.callWithServerTime("res.partner", "search_read", jsonOf(
            "domain" to listOf(listOf("write_date", ">", sinceUtc)),
            "fields" to INDEX_FIELDS + "active",
            "order" to "id",
            "limit" to INDEX_PAGE,
            "context" to mapOf("active_test" to false),
        ))
        val rows = a.value.objects()
        if (rows.size >= INDEX_PAGE) return null
        return indexDelta(rows, a.serverTimeMs)
    }

    /** res.country.state.l10n_in_tin → state id (the GSTIN's first two digits). */
    suspend fun findStateByTin(tin: String): Int? =
        odoo.call("res.country.state", "search_read", jsonOf(
            "domain" to listOf(listOf("l10n_in_tin", "=", tin)),
            "fields" to listOf("name"),
            "limit" to 1,
        )).objects().firstOrNull()?.int("id")

    /** What one delta brought: customers to put in the index again, ids to take out, and Odoo's clock. */
    data class IndexDelta(val rows: List<IndexedCustomer>, val removed: List<Int>, val serverTimeMs: Long?)

    companion object {
        private val PARTNER_FIELDS = listOf(
            "name", "phone", "vat", "l10n_in_gst_treatment", "state_id", "property_account_position_id",
        )

        /** The customer fields plus the E.164 mobile the index matches on. */
        private val INDEX_FIELDS = PARTNER_FIELDS + "phone_sanitized"

        /** Customers per index read: 2,000 rows are about 390 KB. */
        const val INDEX_PAGE = 2000

        /**
         * The nested customer of a `web_search_read` specification ([PARTNER_SPEC]): many2ones come as
         * an id, or as `{"id", "display_name"}` when their fields are asked for.
         */
        val PARTNER_SPEC: Map<String, Any> = mapOf("fields" to mapOf(
            "name" to emptyMap<String, Any>(), "phone" to emptyMap<String, Any>(), "phone_sanitized" to emptyMap<String, Any>(),
            "vat" to emptyMap<String, Any>(), "l10n_in_gst_treatment" to emptyMap<String, Any>(),
            "state_id" to mapOf("fields" to mapOf("display_name" to emptyMap<String, Any>())),
            "property_account_position_id" to emptyMap<String, Any>(),
        ))

        internal fun JsonObject.toCustomer() = Customer(
            id = int("id")!!,
            name = str("name") ?: "",
            phone = str("phone"),
            gstin = str("vat"),
            gstTreatment = GstTreatment.fromOdoo(str("l10n_in_gst_treatment")),
            stateId = m2oId("state_id"),
            stateName = m2oName("state_id"),
            fiscalPositionId = m2oId("property_account_position_id"),
        )

        /** A customer nested in a `web_search_read` answer, read with [PARTNER_SPEC]. */
        internal fun fromSpec(o: JsonObject): IndexedCustomer? {
            val id = o.int("id") ?: return null
            val state = o["state_id"] as? JsonObject
            val customer = Customer(
                id = id,
                name = o.str("name") ?: "",
                phone = o.str("phone"),
                gstin = o.str("vat"),
                gstTreatment = GstTreatment.fromOdoo(o.str("l10n_in_gst_treatment")),
                stateId = state?.int("id"),
                stateName = state?.str("display_name"),
                fiscalPositionId = o.int("property_account_position_id"),
            )
            return IndexedCustomer(customer, o.str("phone_sanitized") ?: "")
        }

        internal fun indexRows(rows: List<JsonObject>): List<IndexedCustomer> = rows.mapNotNull { r ->
            val phone = r.str("phone_sanitized") ?: return@mapNotNull null
            IndexedCustomer(r.toCustomer(), phone)
        }

        /** An active customer with a mobile goes in again; an archived one, or one without a mobile, goes out. */
        internal fun indexDelta(rows: List<JsonObject>, serverTimeMs: Long?): IndexDelta {
            val (keep, drop) = rows.partition { it.bool("active") && it.str("phone_sanitized") != null }
            return IndexDelta(indexRows(keep), drop.mapNotNull { it.int("id") }, serverTimeMs)
        }

        /** Results per page in the customer dialog; the next page loads as the list is scrolled. */
        const val PAGE = 20
        const val MIN_DIGITS = 4
        const val MIN_LETTERS = 2

        /** The digits typed, without the 91 or 0 of a full Indian number ("+91 99106 05187" → "9910605187"). */
        fun nationalDigits(typed: String): String {
            val d = typed.filter { it.isDigit() }
            return when {
                d.length >= 12 && d.startsWith("91") -> d.drop(2)
                d.length == 11 && d.startsWith("0") -> d.drop(1)
                else -> d
            }
        }

        /** A mobile ending with [digits], or whose Indian number starts with them. */
        fun phoneDomain(digits: String): List<Any> =
            listOf("|", listOf("phone_sanitized", "=ilike", "%$digits"), listOf("phone_sanitized", "=ilike", "+91$digits%"))

        /** Typed text is a mobile (digits, spaces, +), not a name. */
        fun isPhoneQuery(query: String): Boolean = query.any { it.isDigit() } && query.none { it.isLetter() }

        /** Enough typed to search: [MIN_DIGITS] digits of a mobile, or [MIN_LETTERS] letters of a name. */
        fun isSearchable(query: String): Boolean =
            if (isPhoneQuery(query)) query.count { it.isDigit() } >= MIN_DIGITS else query.count { it.isLetter() } >= MIN_LETTERS
    }
}
