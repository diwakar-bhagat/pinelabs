package com.bohemian.odoo.pos.odoo

/**
 * What the product grid shows, always drawn from the whole catalogue in memory: the selected
 * tab (null = All, which includes the products that have no menu tab), or the search results
 * across every product. Never capped; the grid is lazy. Built once per catalogue load so names
 * are lower-cased once, not on every keystroke.
 */
class ProductGrid(private val products: List<Product>, private val menu: MenuTree) {
    private val names = products.map { it.name.lowercase() }

    /** A non-blank [query] searches all products whatever the tab: name, barcode or internal reference. */
    fun show(selected: Int?, query: String): List<Product> {
        val q = query.trim()
        if (q.isEmpty()) return menu.filter(products, selected)
        val lower = q.lowercase()
        return products.filterIndexed { i, p ->
            names[i].contains(lower) || p.barcode.equals(q, ignoreCase = true) || p.defaultCode.equals(q, ignoreCase = true)
        }
    }

    /** Category ids that products point at but that are not in the menu, for the load log. */
    fun unknownCategoryRefs(): Int = products.sumOf { p -> p.categIds.count { menu.get(it) == null } }

    companion object {
        val EMPTY = ProductGrid(emptyList(), MenuTree(emptyList()))
    }
}
