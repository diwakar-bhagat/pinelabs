package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.core.sum
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
 */
class PricingService(private val odoo: OdooJson2Client) {
    private data class Key(val taxIds: List<Int>, val unit: Long, val qty: Int)
    private val cache = ConcurrentHashMap<Key, Pair<Paise, Paise>>()

    suspend fun price(line: CartLine): CartLine {
        val key = Key(line.product.taxIds.sorted(), line.product.price.value, line.qty)
        val (incl, excl) = cache[key] ?: compute(key).also { cache[key] = it }
        return line.copy(totalIncl = incl, totalExcl = excl)
    }

    fun totals(lines: List<CartLine>): CartTotals? =
        if (lines.isEmpty() || lines.any { !it.isPriced }) null
        else CartTotals(lines.mapNotNull { it.totalIncl }.sum(), lines.mapNotNull { it.totalExcl }.sum())

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
