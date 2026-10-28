package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/** Today's sales: KLJ (config 2) on 2026-10-28 at 13:21 IST, as the live DB answered. */
class SalesServiceTest {

    private lateinit var server: MockWebServer
    private val ist = TimeZone.getTimeZone("Asia/Calcutta")
    private fun at(utc: String) = SalesService.parseUtc(utc)!!

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun service() = SalesService(OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" }))

    /** The three reads run in parallel, so answers go by endpoint (and grouping), not by order. */
    private fun answer(summary: String, hours: String, methods: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.clone().readUtf8()
                return when (request.path!!.removePrefix("/json/2/")) {
                    "res.users/context_get" -> MockResponse().setBody("""{"lang": "en_IN", "tz": "Asia/Calcutta", "uid": 28}""")
                    "pos.order/formatted_read_group" -> MockResponse().setBody(if ("date_order:hour" in body) hours else summary)
                    "pos.payment/formatted_read_group" -> MockResponse().setBody(methods)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    private fun bodies() = (0 until server.requestCount).map { server.takeRequest().let { r -> r.path!! to r.body.readUtf8() } }

    @Test fun `from 6 AM the day is today, 6 AM to midnight IST`() {
        val d = SalesDay.of(at("2026-10-28 07:51:00"), ist, 6, 24) // 13:21 IST
        assertTrue(d.today)
        assertEquals("2026-10-28 00:30:00", SalesService.utc(d.fromMs))
        assertEquals("2026-10-28 18:30:00", SalesService.utc(d.toMs))
    }

    @Test fun `before 6 AM the screen still shows the day that just ended`() {
        val d = SalesDay.of(at("2026-10-27 21:30:00"), ist, 6, 24) // 03:00 IST on the 28th
        assertFalse(d.today)
        assertEquals("2026-10-27 00:30:00", SalesService.utc(d.fromMs))
        assertEquals("2026-10-27 18:30:00", SalesService.utc(d.toMs))
    }

    @Test fun `the hours set on Setup are used`() {
        val d = SalesDay.of(at("2026-10-28 07:51:00"), ist, 8, 22)
        assertEquals("2026-10-28 02:30:00", SalesService.utc(d.fromMs))
        assertEquals("2026-10-28 16:30:00", SalesService.utc(d.toMs))
    }

    @Test fun `Odoo's grouped figures become the screen's total, bills, first bill, methods and hours`() = runTest {
        answer(
            summary = """[{"__extra_domain": [[1, "=", 1]], "amount_total:sum": 1802.0, "__count": 23,
                "date_order:min": "2026-10-28 05:06:59", "date_order:max": "2026-10-28 07:48:08"}]""",
            hours = """[{"date_order:hour": ["2026-10-28 04:30:00", "10:00 28 Oct"], "amount_total:sum": 285.0, "__count": 3},
                {"date_order:hour": ["2026-10-28 05:30:00", "11:00 28 Oct"], "amount_total:sum": 733.0, "__count": 9},
                {"date_order:hour": ["2026-10-28 06:30:00", "12:00 28 Oct"], "amount_total:sum": 242.0, "__count": 7},
                {"date_order:hour": ["2026-10-28 07:30:00", "13:00 28 Oct"], "amount_total:sum": 542.0, "__count": 4}]""",
            methods = """[{"payment_method_id": [7, "UPI"], "__extra_domain": [["payment_method_id", "=", 7]], "amount:sum": 1802.0, "__count": 20}]""",
        )
        val s = service().storeSales(configId = 2, nowMs = at("2026-10-28 07:51:00"), startHour = 6, endHour = 24)
        assertEquals(Paise(180200), s.total)
        assertEquals(23, s.bills)
        assertEquals(Paise(7834), s.average)
        assertEquals(at("2026-10-28 05:06:59"), s.firstBillMs) // 10:36 AM IST
        assertEquals(at("2026-10-28 07:48:08"), s.lastBillMs) // 1:18 PM IST
        assertEquals(listOf(SalesByMethod("UPI", Paise(180200), 20)), s.byMethod)
        assertEquals(listOf(285L, 733L, 242L, 542L).map { Paise.ofRupees(it) }, s.hours.map { it.total })
        assertEquals(at("2026-10-28 04:30:00"), s.hours.first().startMs) // 10 AM IST
        assertEquals("Asia/Calcutta", s.tz.id)

        // Every read is the store's own, paid bills only, inside 06:00–24:00 IST; hours are grouped in IST.
        val reads = bodies().filter { "formatted_read_group" in it.first }
        assertEquals(3, reads.size)
        reads.forEach { (_, body) ->
            assertTrue(body, "config_id\",\"=\",2]" in body)
            assertTrue(body, "\"paid\",\"done\",\"invoiced\"" in body)
            assertTrue(body, "\">=\",\"2026-10-28 00:30:00\"" in body && "\"<\",\"2026-10-28 18:30:00\"" in body)
        }
        assertTrue(reads.single { "date_order:hour" in it.second }.second.contains("\"tz\":\"Asia/Calcutta\""))
    }

    @Test fun `before the first bill the screen shows zero, not an error`() = runTest {
        answer(
            summary = """[{"__extra_domain": [[1, "=", 1]], "amount_total:sum": false, "__count": 0, "date_order:min": false, "date_order:max": false}]""",
            hours = "[]",
            methods = "[]",
        )
        val s = service().storeSales(configId = 2, nowMs = at("2026-10-28 00:45:00"), startHour = 6, endHour = 24)
        assertEquals(Paise.ZERO, s.total)
        assertEquals(0, s.bills)
        assertNull(s.average)
        assertNull(s.firstBillMs)
        assertTrue(s.hours.isEmpty() && s.byMethod.isEmpty())
    }
}
