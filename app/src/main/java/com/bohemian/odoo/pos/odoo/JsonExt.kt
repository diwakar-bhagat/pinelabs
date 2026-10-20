package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

// Odoo returns `false` for empty fields and [id, "name"] for many2one. These helpers
// read that shape safely without a serializer per model.

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

internal fun JsonObject.money(key: String): Paise =
    (this[key] as? JsonPrimitive)?.doubleOrNull?.let { Paise.fromOdoo(it) } ?: Paise.ZERO

/** many2one → id (or null when Odoo sent false). */
internal fun JsonObject.m2oId(key: String): Int? = (this[key] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.intOrNull

internal fun JsonObject.m2oName(key: String): String? =
    (this[key] as? JsonArray)?.getOrNull(1)?.let { (it as? JsonPrimitive)?.content }

internal fun JsonObject.ids(key: String): List<Int> =
    (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList()

internal fun JsonElement.objects(): List<JsonObject> = if (this is JsonNull) emptyList() else jsonArray.map { it as JsonObject }

internal fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to toJson(v) })

internal fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
    is Iterable<*> -> JsonArray(v.map { toJson(it) })
    is Array<*> -> JsonArray(v.map { toJson(it) })
    else -> error("Unsupported JSON value: ${v::class}")
}

/** Odoo domain helper: listOf(listOf("field", "=", value), …) */
internal fun domain(vararg terms: List<Any?>): List<List<Any?>> = terms.toList()
