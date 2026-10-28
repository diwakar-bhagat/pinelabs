package com.bohemian.odoo.pos.odoo

/**
 * What the product grid shows, always drawn from the whole catalogue in memory: the selected
 * tab (null = All, which includes the products that have no menu tab), or the search results
 * across every product. Never capped; the grid is lazy. Built once per catalogue load so names
 * are lower-cased once, not on every keystroke.
 */
class ProductGrid(private val products: List<Product>, private val menu: MenuTree) {
    private val names = products.map { it.name.lowercase() }

    /**
     * A non-blank [query] searches all products whatever the tab: name, barcode or internal reference.
     * Best matches first: an exact barcode / reference, then names starting with the query, then
     * names with a word starting with it, then any other name containing it; each group A→Z.
     */
    fun show(selected: Int?, query: String): List<Product> {
        val q = query.trim()
        if (q.isEmpty()) return menu.filter(products, selected)
        val lower = q.lowercase()
        return products.indices.mapNotNull { i ->
            val p = products[i]
            val name = names[i]
            val rank = when {
                p.barcode.equals(q, ignoreCase = true) || p.defaultCode.equals(q, ignoreCase = true) -> 0
                name.startsWith(lower) -> 1
                wordStarts(name, lower) -> 2
                name.contains(lower) -> 3
                else -> return@mapNotNull null
            }
            Triple(rank, name, p)
        }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }

    /** "elaichi" in "adrak elaichi chai", "breeze" in "sea-breeze": the query starts a word, not mid-word. */
    private fun wordStarts(name: String, q: String): Boolean {
        var at = name.indexOf(q)
        while (at > 0) {
            if (!name[at - 1].isLetterOrDigit()) return true
            at = name.indexOf(q, at + 1)
        }
        return false
    }

    /** Category ids that products point at but that are not in the menu, for the load log. */
    fun unknownCategoryRefs(): Int = products.sumOf { p -> p.categIds.count { menu.get(it) == null } }

    companion object {
        val EMPTY = ProductGrid(emptyList(), MenuTree(emptyList()))
    }
}
