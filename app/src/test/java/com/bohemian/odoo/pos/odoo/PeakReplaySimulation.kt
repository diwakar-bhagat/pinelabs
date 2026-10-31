package com.bohemian.odoo.pos.odoo

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The busiest hour on record, replayed against a FAKE Odoo (never the live database) by tills running
 * the real [OdooJson2Client]: its lanes, gate, pacing and 429 backoff. Off by default: run with
 * PEAK_REPLAY=1 (report: PEAK_REPLAY_OUT).
 *
 * - **The bills:** 28 Oct, 14:07–15:07 IST, 69 bills at 5 stores, as timings and counts only
 *   (`resources/peak/`, made by `tools/odoo/peak_extract.py`). A scenario replays minutes 10–25 of
 *   that hour, which hold its busiest 10 minutes.
 * - **The stress:** k copies of the chain, each copy's bills shifted by up to ±5 minutes, so k copies
 *   are 5k tills at their peak together.
 * - **Each bill, as 0.9.0 calls Odoo:**
 *   - `compute_all` for each line Odoo had not priced on that till before;
 *   - one card read per customer;
 *   - for a new customer: the search, Find, the look-up before saving, and the save;
 *   - then `sync_from_ui`, and `confirm_coupon_programs` with a customer.
 *   Live sync runs at [LivePace]; the sellable ids, the till's status and the customer delta come every
 *   15 minutes.
 * - **The fake's limit:** the one measured live on 31 Oct (docs/API_ENGINEERING.md, "Live rush"). A bucket
 *   refills at REPLAY_RATE calls a second (3.5) and holds REPLAY_BURST (60). It answers a plain 429 with no
 *   Retry-After, as Odoo did. A stricter bucket (1 a second, bursts of 3, Odoo's published guidance) runs
 *   beside it.
 */
class PeakReplaySimulation {

    private data class Bill(val store: String, val t: Int, val lines: Int, val misses: Int, val customer: Boolean, val newCustomer: Boolean)

    private class FakeOdoo(val rate: Double, val burst: Double) : Dispatcher() {
        private var tokens = burst
        private var at = System.nanoTime()
        val served = AtomicInteger()
        val refused = AtomicInteger()
        val arrivals: MutableList<Long> = Collections.synchronizedList(ArrayList())

        @Synchronized private fun take(): Boolean {
            val t = System.nanoTime()
            tokens = minOf(burst, tokens + (t - at) / 1e9 * rate); at = t
            return if (tokens >= 1) { tokens -= 1; true } else false
        }

        override fun dispatch(request: RecordedRequest): MockResponse {
            arrivals += System.currentTimeMillis()
            if (!take()) {
                refused.incrementAndGet()
                return MockResponse().setResponseCode(429).setBody("<html><body><h1>429 Too Many Requests</h1></body></html>")
            }
            val method = request.path?.substringAfterLast('/') ?: ""
            // Live answers on 31 Oct: reads 35–110 ms, the loader ~230 ms. Writes were not measured; a sale is
            // taken as 600 ms (it creates the order, lines, payments and the picking).
            Thread.sleep(when (method) { "sync_from_ui" -> 600L; "create" -> 300L; "load_data" -> 230L; "compute_all" -> 100L; else -> 60L } + Random.nextLong(0, 100))
            served.incrementAndGet()
            return MockResponse().setBody(if (method == "web_search_read") """{"length":0,"records":[]}""" else "[]")
        }
    }

    private class Sample(val lane: OdooLane, val ms: Long, val ok: Boolean)

    private data class Result(
        val limit: String, val copies: Int, val tills: Int, val bills: Int, val waits: List<Long>, val samples: List<Sample>,
        val served: Int, val refused: Int, val salesLate: Int, val busiest60s: Int,
    )

    private suspend fun timed(client: OdooJson2Client, lane: OdooLane, model: String, method: String, out: MutableList<Sample>): Boolean {
        val t = System.currentTimeMillis()
        val ok = runCatching { withContext(lane) { client.call(model, method, jsonOf()) } }.isSuccess
        out += Sample(lane, System.currentTimeMillis() - t, ok)
        return ok
    }

    private suspend fun until(at: Long) { (at - System.currentTimeMillis()).takeIf { it > 0 }?.let { delay(it) } }

    private fun scenario(limit: String, rate: Double, burst: Double, copies: Int, bills: List<Bill>, from: Int, to: Int, http: OkHttpClient): Result = runBlocking {
        val odoo = FakeOdoo(rate, burst)
        val server = MockWebServer().apply { dispatcher = odoo; start() }
        val base = server.url("/").toString().trimEnd('/')
        val samples = Collections.synchronizedList(ArrayList<Sample>())
        val waits = Collections.synchronizedList(ArrayList<Long>())
        val salesLate = AtomicInteger()
        val stores = bills.map { it.store }.distinct()
        val t0 = System.currentTimeMillis() + 5_000
        val end = t0 + (to - from) * 1000L
        var tills = 0
        coroutineScope {
            for (copy in 0 until copies) for (store in stores) {
                tills++
                val shift = if (copy == 0) 0 else Random.nextInt(-300, 301)
                val mine = bills.filter { it.store == store }.map { it.copy(t = it.t + shift) }.filter { it.t in from until to }.sortedBy { it.t }
                val client = OdooJson2Client(base, "sim", apiKey = { "k" }, http = http, userAgent = { "Replay/$copy/$store" })
                val saleTimes = ConcurrentLinkedDeque<Long>()
                launch { // live sync and the 15-minute reads
                    delay(Random.nextLong(0, LivePace.LIVE_MS))
                    var sellable = System.currentTimeMillis() + Random.nextLong(0, 900_000)
                    var status = System.currentTimeMillis() + Random.nextLong(0, 900_000)
                    var customers = System.currentTimeMillis() + Random.nextLong(0, 900_000)
                    while (System.currentTimeMillis() < end) {
                        timed(client, OdooLane.BACKGROUND, "pos.session", "load_data", samples)
                        val now = System.currentTimeMillis()
                        if (now >= sellable) { timed(client, OdooLane.BACKGROUND, "product.product", "search", samples); sellable += 900_000 }
                        if (now >= status) { timed(client, OdooLane.BACKGROUND, "x_e700_till", "write", samples); status += 900_000 }
                        if (now >= customers) { timed(client, OdooLane.BACKGROUND, "res.partner", "search_read", samples); customers += 900_000 }
                        delay(LivePace.intervalMs(System.currentTimeMillis(), true, saleTimes, client.stats.snapshot().lastThrottledAt))
                    }
                }
                launch { // the bills, one after another, each at its recorded time
                    for (b in mine) {
                        val paidAt = t0 + (b.t - from) * 1000L
                        until(paidAt - 80_000)
                        repeat(b.misses) { i -> until(paidAt - 80_000 + i * 6_000L); timed(client, OdooLane.TAP, "account.tax", "compute_all", samples) }
                        if (b.newCustomer) {
                            until(paidAt - 70_000); timed(client, OdooLane.TAP, "res.partner", "search_read", samples) // typed: not in the index
                            until(paidAt - 62_000); timed(client, OdooLane.TAP, "res.partner", "search_read", samples) // Find
                            until(paidAt - 55_000); timed(client, OdooLane.TAP, "res.partner", "search_read", samples) // Save looks up first
                            timed(client, OdooLane.TAP, "res.partner", "create", samples)
                        }
                        if (b.customer) { until(paidAt - 45_000); timed(client, OdooLane.TAP, "loyalty.card", "web_search_read", samples) }
                        until(paidAt)
                        val started = System.currentTimeMillis()
                        val sent = timed(client, OdooLane.SALE, "pos.order", "sync_from_ui", samples)
                        if (b.customer) timed(client, OdooLane.SALE, "pos.order", "confirm_coupon_programs", samples)
                        waits += System.currentTimeMillis() - started
                        if (!sent) salesLate.incrementAndGet() // the outbox would send it later
                        saleTimes.addLast(System.currentTimeMillis())
                    }
                }
            }
            val scope = this
            launch { until(end + 90_000); scope.coroutineContext.cancelChildren() } // a bill still backing off by then is cut
        }
        server.shutdown()
        val arrivals = odoo.arrivals.toList().sorted()
        var busiest = 0
        var j = 0
        for (i in arrivals.indices) { while (arrivals[i] - arrivals[j] >= 60_000) j++; busiest = maxOf(busiest, i - j + 1) }
        Result(limit, copies, tills, waits.size, waits.toList(), samples.toList(), odoo.served.get(), odoo.refused.get(), salesLate.get(), busiest)
    }

    private fun pct(xs: List<Long>, p: Double) = if (xs.isEmpty()) 0 else xs.sorted()[((xs.size - 1) * p).toInt()]

    @Test fun peakReplay() {
        assumeTrue(System.getenv("PEAK_REPLAY") == "1")
        val fixture = System.getenv("PEAK_REPLAY_FIXTURE") ?: "src/test/resources/peak/peak-2026-10-28-1407.json"
        val bills = Json.parseToJsonElement(File(fixture).readText()).jsonObject["bills"]!!.jsonArray.map {
            val o = it.jsonObject
            Bill(o["store"]!!.jsonPrimitive.content, o["t"]!!.jsonPrimitive.int, o["lines"]!!.jsonPrimitive.int, o["misses"]!!.jsonPrimitive.int,
                o["customer"]!!.jsonPrimitive.boolean, o["new_customer"]!!.jsonPrimitive.boolean)
        }
        val from = System.getenv("PEAK_REPLAY_FROM")?.toInt() ?: 600
        val to = System.getenv("PEAK_REPLAY_TO")?.toInt() ?: 1500
        val rate = System.getenv("REPLAY_RATE")?.toDouble() ?: 3.5
        val burst = System.getenv("REPLAY_BURST")?.toDouble() ?: 60.0
        val measured = (System.getenv("PEAK_REPLAY_COPIES") ?: "1,5,10,15,20,25,30,40").split(",").map { it.trim().toInt() }
        val strict = (System.getenv("PEAK_REPLAY_STRICT_COPIES") ?: "1,2,4,6,8").split(",").map { it.trim().toInt() }
        // One HTTP client for every till: its pool is not what is measured, and it keeps the threads few.
        val http = OkHttpClient.Builder()
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 4096; maxRequestsPerHost = 4096 })
            .connectionPool(ConnectionPool(512, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build()
        val results = runBlocking {
            (measured.map { k -> async(kotlinx.coroutines.Dispatchers.IO) { scenario("measured $rate/s burst ${burst.toInt()}", rate, burst, k, bills, from, to, http) } } +
                strict.map { k -> async(kotlinx.coroutines.Dispatchers.IO) { scenario("guidance 1/s burst 3", 1.0, 3.0, k, bills, from, to, http) } }).awaitAll()
        }
        val report = buildString {
            appendLine("limit,copies,tills,bills,paid_wait_p50_ms,paid_wait_p95_ms,paid_wait_max_ms,tap_p95_ms,calls_ok,http_429,busiest_60s_calls,tap_failed,sales_to_outbox")
            for (r in results) {
                val tap = r.samples.filter { it.lane == OdooLane.TAP }
                appendLine(listOf(r.limit, r.copies, r.tills, r.bills, pct(r.waits, .5), pct(r.waits, .95), r.waits.maxOrNull() ?: 0,
                    pct(tap.map { it.ms }, .95), r.served, r.refused, r.busiest60s, tap.count { !it.ok }, r.salesLate).joinToString(","))
            }
        }
        println(report)
        System.getenv("PEAK_REPLAY_OUT")?.let { File(it).writeText(report) }
    }
}
