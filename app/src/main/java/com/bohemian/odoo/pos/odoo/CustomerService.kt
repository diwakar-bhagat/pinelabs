package com.bohemian.odoo.pos.odoo

import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Phone → res.partner. Needed for the WhatsApp bill and ZZ Cash (both keyed on partner). */
class CustomerService(private val odoo: OdooJson2Client) {

    /** Accepts "98xxxxxxxx", "+91 98xx…", etc. Returns E.164 "+91XXXXXXXXXX" or null. */
    fun normalizeIndianMobile(input: String): String? {
        val d = input.filter { it.isDigit() }.removePrefix("91").let { if (it.length == 11 && it.startsWith("0")) it.drop(1) else it }
        return if (d.length == 10 && d.first() in "6789") "+91$d" else null
    }

    /** Odoo 19 has no res.partner.mobile; phone_sanitized holds E.164 ("+919910605187"). */
    suspend fun findByPhone(e164: String): Customer? {
        return odoo.call("res.partner", "search_read", jsonOf(
            "domain" to listOf(listOf("phone_sanitized", "=", e164)),
            "fields" to listOf("name", "phone"),
            "limit" to 1,
        )).objects().firstOrNull()?.let { Customer(it.int("id")!!, it.str("name") ?: "", it.str("phone")) }
    }

    suspend fun create(name: String, e164: String): Customer {
        val id = odoo.call("res.partner", "create", jsonOf(
            "vals_list" to listOf(mapOf("name" to name.ifBlank { "Customer ${e164.takeLast(4)}" }, "phone" to e164)),
        )).jsonArray.first().jsonPrimitive.intOrNull!!
        return Customer(id, name, e164)
    }
}
