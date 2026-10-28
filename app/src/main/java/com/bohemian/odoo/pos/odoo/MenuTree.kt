package com.bohemian.odoo.pos.odoo

/**
 * The pos.category tree as the Odoo 19 web POS shows it: a row of top-level tabs, and under the
 * selected tab a row of its children (WhatsApp Menu › WA HO / WA KLJ › their sub-menus).
 * Selecting a tab filters to every product in that tab or below it (`child_of`).
 */
class MenuTree(categories: List<PosCategory>) {
    private val byId = categories.associateBy { it.id }
    private val children: Map<Int?, List<PosCategory>> =
        categories.groupBy { c -> c.parentId?.takeIf { it in byId } }
            .mapValues { (_, v) -> v.sortedWith(compareBy({ it.sequence }, { it.id })) }

    val roots: List<PosCategory> get() = children[null].orEmpty()

    fun get(id: Int): PosCategory? = byId[id]

    fun childrenOf(id: Int): List<PosCategory> = children[id].orEmpty()

    /** Root → … → [id]. Empty for an unknown id. */
    fun path(id: Int?): List<PosCategory> {
        val out = ArrayList<PosCategory>()
        var cur = id?.let { byId[it] }
        val seen = HashSet<Int>()
        while (cur != null && seen.add(cur.id)) { out.add(cur); cur = cur.parentId?.let { byId[it] } }
        return out.asReversed()
    }

    /**
     * The tab rows to draw for [selected]: the top level, then the children of every category on
     * the selected path that has any.
     */
    fun rows(selected: Int?): List<List<PosCategory>> =
        listOf(roots) + path(selected).map { childrenOf(it.id) }.filter { it.isNotEmpty() }

    /** [id] and everything below it. */
    fun subtree(id: Int): Set<Int> {
        val out = HashSet<Int>()
        val stack = ArrayDeque(listOf(id))
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            if (out.add(c)) childrenOf(c).forEach { stack.add(it.id) }
        }
        return out
    }

    /** Products shown under [selected]; null shows the whole catalogue. */
    fun filter(products: List<Product>, selected: Int?): List<Product> {
        if (selected == null) return products
        val ids = subtree(selected)
        return products.filter { p -> p.categIds.any { it in ids } }
    }

    /** Colour index of the product's first tab, for the bar under its tile (0 = none). */
    fun colorOf(p: Product): Int = p.categIds.firstNotNullOfOrNull { byId[it]?.color?.takeIf { c -> c != 0 } } ?: 0

    companion object {
        @Volatile private var last: Pair<List<PosCategory>, MenuTree>? = null

        /** The tree for [categories], reused while the same list is passed (one list per catalogue load). */
        fun of(categories: List<PosCategory>): MenuTree =
            last?.takeIf { it.first === categories }?.second ?: MenuTree(categories).also { last = categories to it }
    }
}
