package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The grid draws from the whole catalogue: café tabs plus the retail SKUs that have no tab. */
class ProductGridTest {
    private val menu = MenuTree(listOf(
        PosCategory(1, "Chai & Coffee", null, 1, 8),
        PosCategory(3, "WhatsApp Menu", null, 9, 0),
        PosCategory(4, "WA HO", 3, 0, 4),
    ))

    private fun product(id: Int, name: String, vararg categ: Int, barcode: String? = null, code: String? = null) =
        Product(id, name, Paise.ofRupees(100), barcode, code, emptyList(), categIds = categ.toList())

    private val chai = product(8201, "Adrak Elaichi Chai", 1, 4)
    private val latte = product(8210, "Creamy Latte, Hazelnut", 1)
    // Retail SKUs with no POS category, the last of 2,421 when paged by id.
    private val perfume = product(30001, "A Fragrance Story Sea Breeze 50 ml", barcode = "8906123AB45")
    private val tee = product(30002, "Abnorml BE KIND SWEATSHIRT REGULAR GREEN M SIZE", code = "ABN-BK-GM")
    private val all = listOf(chai, latte, perfume, tee)
    private val grid = ProductGrid(all, menu)

    @Test fun `All shows every product, also those without a tab`() =
        assertEquals(all, grid.show(selected = null, query = ""))

    @Test fun `a tab filters the full catalogue to its subtree`() {
        assertEquals(listOf(chai, latte), grid.show(1, ""))
        assertEquals(listOf(chai), grid.show(3, ""))
    }

    @Test fun `search covers products that are in no tab`() {
        assertEquals(listOf(perfume), grid.show(selected = 1, query = "sea breeze"))
        assertEquals(listOf(tee), grid.show(null, "  sweatshirt "))
    }

    @Test fun `barcode and internal reference match whatever the case`() {
        assertEquals(listOf(perfume), grid.show(null, "8906123ab45"))
        assertEquals(listOf(tee), grid.show(null, "abn-bk-gm"))
    }

    @Test fun `names starting with the search come first, then a word starting with it, then the rest, each A to Z`() {
        val masala = product(1, "Masalachai Premix")
        val latteChai = product(2, "Chai Latte")
        val kulhad = product(3, "Kulhad Chai")
        val adrak = product(4, "Adrak Elaichi Chai")
        val chaiBun = product(5, "chai bun")
        val g = ProductGrid(listOf(masala, latteChai, kulhad, adrak, chaiBun), menu)
        assertEquals(listOf(chaiBun, latteChai, adrak, kulhad, masala), g.show(null, "chai"))
    }

    @Test fun `an exact barcode comes before names that merely contain it`() {
        val code = product(1, "Gift box", barcode = "1234")
        val named = product(2, "Box 1234 large")
        assertEquals(listOf(code, named), ProductGrid(listOf(named, code), menu).show(null, "1234"))
    }

    @Test fun `nothing is capped`() {
        val many = (1..2421).map { product(it, "SKU $it") }
        assertEquals(2421, ProductGrid(many, menu).show(null, "").size)
        assertEquals(2421, ProductGrid(many, menu).show(null, "sku").size)
    }

    @Test fun `category references outside the menu are counted`() {
        assertEquals(0, grid.unknownCategoryRefs())
        assertTrue(ProductGrid(listOf(product(1, "x", 99)), menu).unknownCategoryRefs() == 1)
    }
}
