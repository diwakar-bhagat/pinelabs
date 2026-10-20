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

    /** res.country.state.l10n_in_tin → state id (the GSTIN's first two digits). */
    suspend fun findStateByTin(tin: String): Int? =
        odoo.call("res.country.state", "search_read", jsonOf(
            "domain" to listOf(listOf("l10n_in_tin", "=", tin)),
            "fields" to listOf("name"),
            "limit" to 1,
        )).objects().firstOrNull()?.int("id")

    private fun JsonObject.toCustomer() = Customer(
        id = int("id")!!,
        name = str("name") ?: "",
        phone = str("phone"),
        gstin = str("vat"),
        gstTreatment = GstTreatment.fromOdoo(str("l10n_in_gst_treatment")),
        stateId = m2oId("state_id"),
        stateName = m2oName("state_id"),
        fiscalPositionId = m2oId("property_account_position_id"),
    )

    private companion object {
        val PARTNER_FIELDS = listOf(
            "name", "phone", "vat", "l10n_in_gst_treatment", "state_id", "property_account_position_id",
        )
    }
}
