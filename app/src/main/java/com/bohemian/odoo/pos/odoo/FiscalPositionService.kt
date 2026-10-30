package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.PosLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Fiscal positions decide CGST+SGST vs IGST. In India the position follows the place of
 * supply: the customer's state against the company's state.
 *
 * Odoo's POS does this mapping client-side, so we do the same rather than calling
 * `map_tax` over RPC (it takes a recordset, not ids).
 *
 * Port of `account.fiscal.position.map_tax` (addons/account/models/partner.py):
 *
 *     if not self:                 return taxes
 *     if not self.tax_ids:         return taxes.filtered(lambda t: not t.fiscal_position_ids)
 *     return browse(unique(tax_id for tax in taxes
 *                          for tax_id in (self.tax_map or {}).get(tax.id, [tax.id])))
 *
 * and of `getTaxesAfterFiscalPosition` in
 * point_of_sale/static/src/app/models/account_fiscal_position.js.
 */
class FiscalPositionService(private val odoo: OdooJson2Client) {

    /** Taxes that are themselves attached to a fiscal position (the "tax unit" pattern). */
    private var taxesWithFiscalPosition: Set<Int> = emptySet()

    suspend fun load(ids: List<Int>): Map<Int, FiscalPosition> {
        if (ids.isEmpty()) return emptyMap()
        val rows = odoo.call("account.fiscal.position", "read", jsonOf(
            "ids" to ids,
            "fields" to listOf("name", "tax_map", "tax_ids"),
        )).objects()

        return fromRecords(rows)
    }

    /** Positions from records that carry name, tax_map and tax_ids (a read, or the POS loader's, 0.8.0). */
    suspend fun fromRecords(rows: List<JsonObject>): Map<Int, FiscalPosition> {
        val positions = rows.associate { row ->
            val id = row.int("id")!!
            val taxIds = row.ids("tax_ids")
            val map = parseTaxMap(row["tax_map"]) ?: fallbackTaxMap(taxIds)
            id to FiscalPosition(id, row.str("name") ?: "Fiscal position $id", map, taxIds)
        }
        positions.values.forEach {
            if (it.taxIds.isNotEmpty() && it.taxMap.isEmpty()) {
                PosLog.w("FPOS_EMPTY_TAX_MAP", "fpos" to it.id, "name" to it.name)
            }
        }
        return positions
    }

    /**
     * `tax_map` is a computed Binary field holding {src_tax_id: [dest_tax_id, ...]}.
     * Over JSON-2 it should arrive as an object with string keys; anything else
     * (false, base64) falls back to [fallbackTaxMap].
     */
    private fun parseTaxMap(el: kotlinx.serialization.json.JsonElement?): Map<Int, List<Int>>? {
        val obj = el as? JsonObject ?: return null
        val out = mutableMapOf<Int, List<Int>>()
        for ((k, v) in obj) {
            val src = k.toIntOrNull() ?: return null
            val dest = (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: return null
            out[src] = dest
        }
        return out
    }

    /**
     * Rebuild the map from `account.tax.original_tax_ids`, which is what Odoo's
     * `_compute_tax_map` iterates. Used when `tax_map` does not survive JSON-2.
     */
    private suspend fun fallbackTaxMap(destTaxIds: List<Int>): Map<Int, List<Int>> {
        if (destTaxIds.isEmpty()) return emptyMap()
        val out = mutableMapOf<Int, MutableList<Int>>()
        odoo.call("account.tax", "read", jsonOf(
            "ids" to destTaxIds, "fields" to listOf("original_tax_ids"),
        )).objects().forEach { row ->
            val dest = row.int("id")!!
            row.ids("original_tax_ids").forEach { src -> out.getOrPut(src) { mutableListOf() }.add(dest) }
        }
        PosLog.i("FPOS_TAX_MAP_FALLBACK", "entries" to out.size)
        return out
    }

    /** The taxes that carry their own fiscal positions, from the POS loader's account.tax records (0.8.0). */
    fun useTaxFiscalPositions(taxIds: Set<Int>) { taxesWithFiscalPosition = taxIds }

    /** Cache which taxes carry their own fiscal positions; needed for the empty-tax_ids branch. */
    suspend fun primeTaxFiscalPositions(taxIds: List<Int>) {
        if (taxIds.isEmpty()) return
        taxesWithFiscalPosition = odoo.call("account.tax", "read", jsonOf(
            "ids" to taxIds.distinct(), "fields" to listOf("fiscal_position_ids"),
        )).objects().filter { it.ids("fiscal_position_ids").isNotEmpty() }
            .mapNotNull { it.int("id") }.toSet()
    }

    /** Exact port of `map_tax`. Order is preserved and duplicates removed, as `unique` does. */
    fun mapTaxes(position: FiscalPosition?, taxIds: List<Int>): List<Int> {
        if (position == null) return taxIds
        if (position.taxIds.isEmpty()) return taxIds.filter { it !in taxesWithFiscalPosition }
        val out = LinkedHashSet<Int>()
        for (tax in taxIds) out.addAll(position.taxMap[tax] ?: listOf(tax))
        return out.toList()
    }

    /**
     * Which fiscal position applies to an order.
     *
     * Port of point_of_sale/static/src/app/models/pos_order.js: the customer's own
     * position wins, but only when the till is allowed to use it; otherwise the till
     * default applies.
     */
    fun resolve(config: PosConfig, customer: Customer?): Int? {
        val partnerFpos = customer?.fiscalPositionId
        if (partnerFpos != null && partnerFpos in config.fiscalPositionIds) return partnerFpos
        return config.defaultFiscalPositionId
    }
}
