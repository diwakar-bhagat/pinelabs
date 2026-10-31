package com.bohemian.odoo.pos.odoo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Read-only check of the customer index against the live database, run by hand only:
 * `LIVE_ODOO_ENV=/path/to/.env ./gradlew testUatDebugUnitTest --tests '*LiveCustomerIndexCheck*'`.
 * It loads the index as the till does, then for 4-digit starts and ends of real mobiles compares the
 * customers the index finds with what Odoo's own search finds (all pages). It prints counts, never
 * a name or a number.
 */
class LiveCustomerIndexCheck {
    @Test fun `the index finds the same customers as Odoo's search`() = runBlocking {
        val path = System.getenv("LIVE_ODOO_ENV")
        assumeTrue("set LIVE_ODOO_ENV to run", path != null)
        val env = File(path!!).readLines().filter { "=" in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().trim('"') }
        val odoo = OdooJson2Client(env.getValue("ODOO_URL").trimEnd('/'), env.getValue("ODOO_DB"), apiKey = { env.getValue("ODOO_API_KEY") },
            throttle = OdooJson2Client.Throttle(perSecond = 2.0, burst = 2.0))
        val svc = CustomerService(odoo)
        val started = System.currentTimeMillis()
        val (rows, at) = svc.indexAll()
        val idx = CustomerIndex().apply { replaceAll(rows, System.currentTimeMillis()) }
        println("index: ${idx.size} customers in ${System.currentTimeMillis() - started} ms, Odoo clock ${at != null}")
        val numbers = rows.map { it.phoneSanitized }.filter { it.startsWith("+91") && it.length == 13 }.map { it.removePrefix("+91") }
        val rnd = java.util.Random(31)
        val queries = (List(10) { numbers[rnd.nextInt(numbers.size)].take(4) } + List(10) { numbers[rnd.nextInt(numbers.size)].takeLast(4) }).distinct()
        var compared = 0
        for (q in queries) {
            val server = buildList {
                var offset = 0
                while (true) {
                    val page = svc.searchByPhone(q, offset, 80)
                    addAll(page.map { it.id }); offset += page.size
                    if (page.size < 80) break
                }
            }.toSet()
            val local = idx.byPhone(q, 0, 10_000).map { it.id }.toSet()
            compared += server.size
            if (server != local) println("query #${queries.indexOf(q)}: odoo ${server.size}, index ${local.size}, only odoo ${(server - local).size}, only index ${(local - server).size}")
            assertEquals("query #${queries.indexOf(q)}", server, local)
        }
        println("${queries.size} queries, $compared matches, all equal")
    }
}
