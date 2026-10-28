package com.bohemian.odoo.pos.odoo

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The products whose price the store's Odoo pricelist sets.
 *
 * - **The mismatch:** the web POS prices every order with the config's pricelist. The till charges
 *   a product's list price and does not compute pricelists, so on such a product the two would charge
 *   differently at one store.
 * - **What the till does:** it refuses those products, never charging a price the web POS would not.
 * - **Today (2026-10-28):** pricelist 1 (ZenZebra, KLJ, SWN) has 8 rules, all on shelf-rental products
 *   that are not in POS, so nothing is held back.
 */
data class PricelistHold(val pricelistId: Int?, val everything: Boolean = false, val productIds: Set<Int> = emptySet()) {
    fun holds(productId: Int): Boolean = everything || productId in productIds

    companion object { val NONE = PricelistHold(null) }
}

class PricelistService(private val odoo: OdooJson2Client) {

    /**
     * The POS products the rules of [cfg]'s pricelist cover. Rules that ended before [nowUtc] are
     * skipped; a future rule already counts.
     */
    suspend fun hold(cfg: PosConfig, nowUtc: String): PricelistHold {
        val pricelistId = cfg.pricelistId ?: return PricelistHold.NONE
        val rules = odoo.call("product.pricelist.item", "search_read", jsonOf(
            "domain" to listOf(listOf("pricelist_id", "=", pricelistId)),
            "fields" to listOf("applied_on", "product_tmpl_id", "product_id", "categ_id", "date_end"),
        )).objects().filter { r -> r.str("date_end")?.let { it >= nowUtc } ?: true }
        val domains = rules.map(::ruleDomain)
        if (domains.any { it == null }) return PricelistHold(pricelistId, everything = true)
        // Odoo resolves each rule's products itself (category trees with child_of).
        val ids = coroutineScope {
            domains.filterNotNull().distinct().map { d ->
                async {
                    odoo.call("product.product", "search", jsonOf("domain" to d + listOf(listOf("available_in_pos", "=", true))))
                        .jsonArray.mapNotNull { it.jsonPrimitive.intOrNull }
                }
            }.awaitAll().flatten().toSet()
        }
        return PricelistHold(pricelistId, productIds = ids)
    }

    companion object {
        /** The products a rule covers, as an Odoo domain; null for every product (a global rule). */
        fun ruleDomain(r: JsonObject): List<Any>? = when (r.str("applied_on")) {
            "0_product_variant" -> r.m2oId("product_id")?.let { listOf(listOf("id", "=", it)) }
            "1_product" -> r.m2oId("product_tmpl_id")?.let { listOf(listOf("product_tmpl_id", "=", it)) }
            "2_product_category" -> r.m2oId("categ_id")?.let { listOf(listOf("categ_id", "child_of", it)) }
            else -> null // 3_global, or a kind this till does not know: assume every product
        }
    }
}
