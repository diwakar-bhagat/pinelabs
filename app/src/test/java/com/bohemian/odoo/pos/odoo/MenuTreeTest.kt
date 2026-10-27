package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import org.junit.Assert.assertEquals
import org.junit.Test

/** The live pos.category tree of 2026-10-27 (a subset), as the web POS shows it. */
class MenuTreeTest {
    private val cats = listOf(
        PosCategory(1, "Chai & Coffee", null, 1, 8),
        PosCategory(2, "Bites", null, 2, 3),
        PosCategory(3, "WhatsApp Menu", null, 999, 0),
        PosCategory(4, "WA HO", 3, 0, 4),
        PosCategory(9, "WA KLJ", 3, 0, 4),
        PosCategory(5, "Chai Bina Chain Kahan Re", 4, 0, 8),
        PosCategory(10, "Chai Bina Chain Kahan Re", 9, 0, 3),
    )
    private val tree = MenuTree(cats)

    private fun product(id: Int, vararg categ: Int) =
        Product(id, "p$id", Paise.ofRupees(20), null, null, emptyList(), categIds = categ.toList())

    @Test fun `top level tabs follow Odoo sequence`() =
        assertEquals(listOf("Chai & Coffee", "Bites", "WhatsApp Menu"), tree.roots.map { it.name })

    @Test fun `selecting a sub menu shows every level above it`() {
        val rows = tree.rows(5).map { r -> r.map { it.id } }
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 9), listOf(5)), rows)
        assertEquals(listOf(listOf(1, 2, 3)), tree.rows(null).map { r -> r.map { it.id } })
    }

    @Test fun `a tab shows its own products and those of its children`() {
        val chai = product(8201, 10, 5, 1) // Adrak Elaichi Chai is in three menus
        val nachos = product(8632, 13)
        val waOnly = product(1, 10)
        val all = listOf(chai, nachos, waOnly)
        assertEquals(listOf(chai, waOnly), tree.filter(all, 3))
        assertEquals(listOf(chai), tree.filter(all, 1))
        assertEquals(all, tree.filter(all, null))
    }

    @Test fun `tile colour comes from the first coloured tab`() {
        assertEquals(3, tree.colorOf(product(1, 10, 5)))
        assertEquals(0, tree.colorOf(product(2, 3)))
    }

    @Test fun `a missing parent makes the category a root rather than hiding it`() =
        assertEquals(listOf(7), MenuTree(listOf(PosCategory(7, "Orphan", 99, 0, 0))).roots.map { it.id })

    @Test fun `line notes use Odoo's JSON`() {
        assertEquals("[]", LineNotes.toOdoo(emptyList()))
        assertEquals("""[{"text":"Wait","colorIndex":1},{"text":"no ice","colorIndex":0}]""",
            LineNotes.toOdoo(listOf(LineNote("Wait", 1), LineNote("no ice"))))
    }
}
