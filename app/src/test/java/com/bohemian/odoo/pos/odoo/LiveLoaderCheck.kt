package com.bohemian.odoo.pos.odoo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Read-only check of the POS loader against the live database, run by hand only:
 * `LIVE_ODOO_ENV=/path/to/.env ./gradlew testUatDebugUnitTest --tests '*LiveLoaderCheck*'`.
 * For each open session it builds the products from `pos.session.load_data` (PosCatalog) and compares
 * them with the till's own product reads (CatalogService.products). It only reads.
 */
class LiveLoaderCheck {
    @Test fun `the loader's products equal the till's own reads on the live database`() = runBlocking {
        val path = System.getenv("LIVE_ODOO_ENV")
        assumeTrue("set LIVE_ODOO_ENV to run", path != null)
        val env = File(path!!).readLines().filter { "=" in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().trim('"') }
        val odoo = OdooJson2Client(env.getValue("ODOO_URL").trimEnd('/'), env.getValue("ODOO_DB"), apiKey = { env.getValue("ODOO_API_KEY") },
            throttle = OdooJson2Client.Throttle(perSecond = 5.0, burst = 5.0))
        val sessions = odoo.call("pos.session", "search_read", jsonOf("domain" to listOf(listOf("state", "=", "opened")), "fields" to listOf("config_id")))
            .objects().mapNotNull { it.int("id") }
        for (sid in sessions) {
            val cat = PosCatalog()
            cat.merge(PosLoad(odoo).full(sid).data, full = true)
            cat.missingVariants().takeIf { it.isNotEmpty() }?.let { cat.addVariants(PosLoad(odoo).variants(it)) }
            val cfg = cat.config()!!
            val fromLoader = cat.products()
            val fromReads = CatalogService(odoo).products(cfg)
            println("session $sid (${cfg.name}): loader ${fromLoader.size}, reads ${fromReads.size}, tabs ${cat.categories(cfg).size}, taxes ${cat.taxNames().size}")
            val byId = fromLoader.associateBy { it.id }
            val differing = fromReads.mapNotNull { r ->
                val l = byId[r.id] ?: return@mapNotNull "missing ${r.id}"
                listOfNotNull(
                    "name".takeIf { l.name != r.name }, "price".takeIf { l.price != r.price }, "barcode".takeIf { l.barcode != r.barcode },
                    "code".takeIf { l.defaultCode != r.defaultCode }, "taxes ${r.taxIds}->${l.taxIds}".takeIf { l.taxIds != r.taxIds },
                    "hsn".takeIf { l.hsnCode != r.hsnCode }, "categ ${r.categIds}->${l.categIds}".takeIf { l.categIds.toSet() != r.categIds.toSet() },
                ).takeIf { it.isNotEmpty() }?.let { "${r.id}: ${it.joinToString()}" }
            }
            println("DIFF count ${differing.size}: ${differing.take(8)}")
            println("ORDER same: ${fromReads.map { it.id } == fromLoader.map { it.id }}")
            assertEquals(emptyList<String>(), differing)
        }
    }
}
