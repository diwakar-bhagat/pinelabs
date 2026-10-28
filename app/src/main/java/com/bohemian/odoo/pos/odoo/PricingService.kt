package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.core.sum
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * HARD RULE 2: Odoo computes the money. Each cart line is priced with
 * account.tax.compute_all (handles GST groups, CESS and code-type taxes exactly
 * like the Odoo web POS / invoice). Company rounding is round_per_line, so the
 * order total = sum of line totals.
 *
 * The taxes sent to compute_all are the product taxes **after** the order's fiscal
 * position has been mapped over them, matching
 * `pos.order.line._compute_amount_line_all`:
 *
 *     tax_ids_after_fiscal_position = fpos.map_tax(self.tax_ids)
 *     taxes = tax_ids_after_fiscal_position.compute_all(...)
 *
 * Without the mapping an inter-state B2B sale is priced as CGST+SGST while Odoo
 * books it as IGST, and the totals we send no longer match the ones Odoo derives.
 */
class PricingService(
    private val odoo: OdooJson2Client,
    private val fiscalPositions: FiscalPositionService,
) {
    /** The fiscal position is part of the key: the same product prices differently under IGST. */
    private data class Key(val taxIds: List<Int>, val unit: Long, val qty: Int, val fiscalPositionId: Int?)

    private val cache = ConcurrentHashMap<Key, Pair<Paise, Paise>>()

    suspend fun price(line: CartLine, position: FiscalPosition? = null): CartLine {
        val applied = fiscalPositions.mapTaxes(position, line.product.taxIds)
        val key = Key(applied.sorted(), line.product.price.value, line.qty, position?.id)
        val (incl, excl) = cache[key] ?: compute(key).also { cache[key] = it }
        return line.copy(totalIncl = incl, totalExcl = excl, appliedTaxIds = applied)
    }

    /**
     * Re-price a whole cart; call this whenever the customer (hence the position) changes. Lines not
     * in the cache are priced in parallel (at most [PARALLEL] calls at once), not one after another.
     */
    suspend fun priceAll(lines: List<CartLine>, position: FiscalPosition? = null): List<CartLine> = coroutineScope {
        val gate = Semaphore(PARALLEL)
        lines.map { line -> async { gate.withPermit { price(line, position) } } }.awaitAll()
    }

    fun totals(lines: List<CartLine>): CartTotals? =
        if (lines.isEmpty() || lines.any { !it.isPriced }) null
        else CartTotals(lines.mapNotNull { it.totalIncl }.sum(), lines.mapNotNull { it.totalExcl }.sum())

    /**
     * Not needed when the fiscal position changes (the key covers it). Called on every catalogue
     * load, so a price or tax changed in Odoo is picked up without restarting the till.
     */
    fun clearCache() = cache.clear()

    private companion object {
        /** Odoo calls in flight at once for one cart. */
        const val PARALLEL = 4
    }

    private suspend fun compute(k: Key): Pair<Paise, Paise> {
        val unit = Paise(k.unit)
        if (k.taxIds.isEmpty()) return (unit * k.qty) to (unit * k.qty)
        val r: JsonObject = odoo.call("account.tax", "compute_all", jsonOf(
            "ids" to k.taxIds,
            "price_unit" to unit.toRupees(),
            "quantity" to k.qty,
        )).jsonObject
        val incl = r["total_included"]!!.jsonPrimitive.doubleOrNull!!
        val excl = r["total_excluded"]!!.jsonPrimitive.doubleOrNull!!
        return Paise.fromOdoo(incl) to Paise.fromOdoo(excl)
    }
}
