package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** F3: Odoo's price answers survive a restart, so a cart Odoo priced before sells while Odoo is out of reach. */
class PriceCacheTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private class MemStore : PricingService.PriceStore {
        val rows = mutableListOf<PricingService.PriceStore.Saved>()
        override suspend fun all() = rows.toList()
        override suspend fun save(s: PricingService.PriceStore.Saved) { rows.removeAll { it.copy(incl = 0, excl = 0, stamp = "") == s.copy(incl = 0, excl = 0, stamp = "") }; rows += s }
        override suspend fun keepOnly(stamp: String) { rows.removeAll { it.stamp != stamp } }
    }

    private fun pricing(store: PricingService.PriceStore): PricingService {
        val odoo = OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" },
            throttle = OdooJson2Client.Throttle(tapBudgetMs = 100, saleBudgetMs = 100, backgroundBudgetMs = 100, firstBackoffMs = 20))
        return PricingService(odoo, FiscalPositionService(odoo), store)
    }

    private val chai = CartLine("l1", Product(8202, "Cardamom Chai", Paise.ofRupees(60), null, null, listOf(30)), 2)
    private fun computeAll() = MockResponse().setBody("""{"total_included": 120.0, "total_excluded": 114.29, "taxes": []}""")

    @Test fun `an answer Odoo gave is used again after a restart, without Odoo`() = runBlocking {
        val store = MemStore()
        server.enqueue(computeAll())
        val first = pricing(store).apply { useTaxStamp("2026-10-30 05:00:00|") }.price(chai)
        assertEquals(Paise.ofRupees(120), first.totalIncl)
        assertEquals(1, server.requestCount)

        server.shutdown() // Odoo out of reach
        val again = pricing(store).price(chai) // a new till process
        assertEquals(Paise.ofRupees(120), again.totalIncl)
        assertEquals(Paise(11429), again.totalExcl)
    }

    @Test fun `the same tax stamp keeps the answers, a new one empties them`() = runBlocking {
        val store = MemStore()
        server.enqueue(computeAll())
        val p = pricing(store).apply { useTaxStamp("A|") }
        p.price(chai)
        p.useTaxStamp("A|")
        p.price(chai)
        assertEquals(1, server.requestCount) // still saved
        p.useTaxStamp("B|") // a tax changed in Odoo
        assertTrue(store.rows.isEmpty())
        server.enqueue(computeAll())
        p.price(chai)
        assertEquals(2, server.requestCount)
        assertEquals("B|", store.rows.single().stamp)
    }

    @Test fun `an item Odoo never priced cannot be sold while Odoo is out of reach`() = runBlocking {
        server.shutdown()
        val e = runCatching { pricing(MemStore()).price(chai) }.exceptionOrNull()
        assertEquals(OdooErrorKind.TRANSIENT, OdooErrors.kind(e!!))
    }
}
