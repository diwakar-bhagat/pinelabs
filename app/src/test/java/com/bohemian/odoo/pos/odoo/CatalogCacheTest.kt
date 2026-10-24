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
