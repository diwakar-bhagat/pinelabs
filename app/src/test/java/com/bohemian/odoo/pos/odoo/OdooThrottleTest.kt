package com.bohemian.odoo.pos.odoo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Odoo Online's 429 (Too Many Requests): wait as told, in lanes, and never hammer it. */
class OdooThrottleTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    /** Short budgets and no randomness: jitter 0.5 makes our own backoff exactly 100 / 200 / 400 / 800 ms. */
    private fun client(tapBudgetMs: Long = 1_000) = OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" },
        throttle = OdooJson2Client.Throttle(tapBudgetMs = tapBudgetMs, saleBudgetMs = 1_000, backgroundBudgetMs = 1_000,
            firstBackoffMs = 100, jitter = { 0.5 }))

    @Test fun `a 429 is waited out for as long as Odoo asks and the call succeeds`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setBody("[7]"))
        val started = System.currentTimeMillis()
        assertEquals("[7]", client(tapBudgetMs = 5_000).call("pos.config", "search", jsonOf()).toString())
        assertEquals(2, server.requestCount)
        assertTrue(System.currentTimeMillis() - started >= 1_000)
    }

    @Test fun `Odoo still busy after the lane's budget gives a clear 429`() = runBlocking {
        repeat(6) { server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0")) }
        val e = runCatching { client().call("pos.config", "search", jsonOf()) }.exceptionOrNull()
        assertEquals(429, (e as OdooException.Server).status)
        assertEquals(4, server.requestCount) // waits 100, 200, 400; the next 800 would pass the 1 s budget
    }

    @Test fun `a cool-down longer than the lane's budget fails at once, without calling Odoo again`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
        val odoo = client()
        assertEquals(429, (runCatching { odoo.call("pos.config", "search", jsonOf()) }.exceptionOrNull() as OdooException.Server).status)
        assertEquals(429, (runCatching { odoo.call("res.partner", "search_read", jsonOf()) }.exceptionOrNull() as OdooException.Server).status)
        assertEquals(1, server.requestCount)
        assertEquals(1, odoo.stats.snapshot().throttledToday)
    }

    @Test fun `a 503 is repeated for a read but never for a create`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("[]"))
        assertEquals("[]", client().call("res.partner", "search_read", jsonOf()).toString())
        server.enqueue(MockResponse().setResponseCode(503))
        val e = runCatching { client().call("res.partner", "create", jsonOf()) }.exceptionOrNull()
        assertEquals(503, (e as OdooException.Server).status)
        assertEquals(3, server.requestCount)
    }

    @Test fun `Retry-After is read in seconds or as an HTTP date`() {
        assertEquals(120_000L, OdooJson2Client.retryAfterMs(" 120 ", 0))
        val at = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }.parse("Wed, 21 Oct 2026 07:28:00 GMT")!!.time
        assertEquals(5_000L, OdooJson2Client.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT", at - 5_000))
        assertEquals(0L, OdooJson2Client.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT", at + 5_000))
        assertNull(OdooJson2Client.retryAfterMs("soon", 0))
        assertNull(OdooJson2Client.retryAfterMs(null, 0))
    }

    @Test fun `a sale goes before queued background calls, and never more than two run at once`() = runBlocking {
        val gate = OdooGate(maxInFlight = 2, backgroundGapMs = 0)
        val hold = CompletableDeferred<Unit>()
        var running = 0
        var most = 0
        val order = mutableListOf<String>()
        suspend fun work(name: String, lane: OdooLane, until: CompletableDeferred<Unit>?) = gate.run(lane) {
            running++; most = maxOf(most, running); order += name
            until?.await(); delay(10)
            running--
        }
        val jobs = listOf(launch { work("tap1", OdooLane.TAP, hold) }, launch { work("tap2", OdooLane.TAP, hold) })
        delay(50)
        val later = listOf(launch { work("bg", OdooLane.BACKGROUND, null) }, launch { work("tap3", OdooLane.TAP, null) },
            launch { work("sale", OdooLane.SALE, null) })
        delay(50)
        assertEquals(listOf("tap1", "tap2"), order)
        hold.complete(Unit)
        (jobs + later).joinAll()
        assertEquals(listOf("tap1", "tap2", "sale", "tap3", "bg"), order)
        assertEquals(2, most)
    }

    @Test fun `background calls run one at a time, a gap apart`() = runBlocking {
        val gate = OdooGate(maxInFlight = 2, backgroundGapMs = 200)
        val starts = mutableListOf<Long>()
        listOf(launch { gate.run(OdooLane.BACKGROUND) { starts += System.currentTimeMillis() } },
            launch { gate.run(OdooLane.BACKGROUND) { starts += System.currentTimeMillis() } }).joinAll()
        assertTrue(starts[1] - starts[0] >= 190)
    }

    @Test fun `the lane of the caller's context is used`() = runBlocking {
        server.enqueue(MockResponse().setBody("1"))
        val odoo = client()
        withContext(OdooLane.BACKGROUND) { odoo.call("pos.config", "search_count", jsonOf()) }
        assertEquals(1, odoo.stats.snapshot().byLane[OdooLane.BACKGROUND])
    }
}
