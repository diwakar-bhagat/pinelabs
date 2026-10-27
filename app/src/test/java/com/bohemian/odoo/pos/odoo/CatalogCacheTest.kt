package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CatalogCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private val config = PosConfig(
        id = 1, name = "ZenZebra KLJ", currentSessionId = 42, sessionOpen = true, paymentMethodIds = listOf(3, 8),
        companyName = "Bohemian Curations", companyId = 1, invoiceJournalId = 9, defaultFiscalPositionId = null,
        fiscalPositionIds = listOf(2), cashControl = true,
    )
    private val products = listOf(
        Product(12, "29 TEE orange S", Paise(69900), "8901234567890", "TEE-S", listOf(5), "6109"),
        Product(40, "Makino Corn Munch", Paise(14000), null, null, listOf(7)),
    )

    @Test fun `a config that limits its categories survives a restart`() {
        val cache = CatalogCache(File(tmp.root, "catalog/catalog.json"))
        val limited = config.copy(limitCategories = true, availableCategIds = listOf(5, 6, 8, 7))
        cache.save(limited, products, now = 1)
        assertEquals(limited, cache.load()!!.config)
    }

    @Test fun `a refresh replaces the catalogue, so added, changed and archived products follow Odoo`() {
        val cache = CatalogCache(File(tmp.root, "catalog/catalog.json"))
        cache.save(config, products, now = 1)
        val renamed = products[0].copy(name = "29 TEE orange S (new)", price = Paise(64900))
        val added = Product(41, "A Fragrance Story Sea Breeze 50 ml", Paise(85400), "890612345", null, listOf(5))
        cache.save(config, listOf(renamed, added), now = 2) // product 40 archived in Odoo
        assertEquals(listOf(renamed, added), cache.load()!!.products)
    }

    @Test fun `saved catalogue loads back exactly`() {
        val cache = CatalogCache(File(tmp.root, "catalog/catalog.json"))
        cache.save(config, products, now = 1234)
        val c = cache.load()!!
        assertEquals(1234, c.savedAt)
        assertEquals(config, c.config)
        assertEquals(products, c.products)
    }

    @Test fun `missing or corrupt file means no cache, not a crash`() {
        val f = File(tmp.root, "catalog.json")
        assertNull(CatalogCache(f).load())
        f.writeText("{not json")
        assertNull(CatalogCache(f).load())
    }

    @Test fun `a newer save replaces the old one`() {
        val cache = CatalogCache(File(tmp.root, "catalog.json"))
        cache.save(config, products, now = 1)
        cache.save(config.copy(name = "Renamed"), products.take(1), now = 2)
        val c = cache.load()!!
        assertEquals("Renamed", c.config.name)
        assertEquals(1, c.products.size)
    }
}
