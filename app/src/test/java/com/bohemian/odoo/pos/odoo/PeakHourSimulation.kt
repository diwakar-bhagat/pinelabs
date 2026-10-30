package com.bohemian.odoo.pos.odoo

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Rush hour against a FAKE Odoo Online (never the live database): tills of several stores share one
 * database whose limit is about one call a second (token bucket, burst 3) and answer in ~250 ms; past
 * the limit it answers 429. Each store runs one till with the real [OdooJson2Client] (its lanes, gate
 * and backoff) and gets 80 bills an hour. Off by default: run with PEAK_SIM=1. Report: PEAK_SIM_OUT.
 */
class PeakHourSimulation {

    /** Odoo Online stand-in: one bucket for the whole database (every till, every key). */
    private class FakeOdoo(val perSecond: Double, val burst: Double, val latencyMs: Long) : Dispatcher() {
        private var tokens = burst
        private var at = System.nanoTime()
        val served = AtomicInteger(); val refused = AtomicInteger()

        @Synchronized private fun take(): Boolean {
            val t = System.nanoTime()
            tokens = minOf(burst, tokens + (t - at) / 1e9 * perSecond); at = t
            return if (tokens >= 1) { tokens -= 1; true } else false
        }

        override fun dispatch(request: RecordedRequest): MockResponse {
            if (!take()) { refused.incrementAndGet(); return MockResponse().setResponseCode(429).setBody("""{"name":"TooManyRequests"}""") }
            Thread.sleep(latencyMs + Random.nextLong(-100, 150))
            served.incrementAndGet()
            return MockResponse().setBody("[]")
        }
    }

    private class Sample(val lane: OdooLane, val ms: Long, val ok: Boolean)

    private data class Result(val model: String, val stores: Int, val bills: Int, val billWaitsMs: List<Long>, val samples: List<Sample>, val served: Int, val refused: Int, val failedSales: Int)

    private suspend fun timed(client: OdooJson2Client, lane: OdooLane, method: String, out: MutableList<Sample>): Long {
        val t = System.currentTimeMillis()
        val ok = runCatching { withContext(lane) { client.call("pos.order", method, jsonOf()) } }.isSuccess
        val ms = System.currentTimeMillis() - t
        out += Sample(lane, ms, ok)
        return ms
    }

    private fun scenario(model: String, stores: Int, minutes: Int, billsPerHour: Int): Result = runBlocking {
        val odoo = FakeOdoo(perSecond = 1.0, burst = 3.0, latencyMs = 250)
        val server = MockWebServer().apply { dispatcher = odoo; start() }
        val samples = Collections.synchronizedList(ArrayList<Sample>())
        val waits = Collections.synchronizedList(ArrayList<Long>())
        val failedSales = AtomicInteger()
        val endAt = System.currentTimeMillis() + minutes * 60_000L
        coroutineScope {
            repeat(stores) { s ->
                val client = OdooJson2Client(server.url("/").toString().trimEnd('/'), "sim", apiKey = { "k" }, userAgent = { "PeakSim/$s" })
                // Background, a minute at a time. 0.7: live sync's stamps (2 product stamps every 5 min, 9 others
                // every 15) and a health report a minute. 0.8: Odoo's POS loader every 2 min (1 call), the sellable
                // product ids every 15 min and the bill template every hour.
                launch {
                    var tick = 0
                    while (isActive && System.currentTimeMillis() < endAt) {
                        val calls = if (model == "0.7") 1 + (if (tick % 5 == 0) 2 else 0) + (if (tick % 15 == 0) 9 else 0)
                            else (if (tick % 2 == 0) 1 else 0) + (if (tick % 15 == 0) 1 else 0) + (if (tick % 60 == 0) 1 else 0)
                        repeat(calls) { timed(client, OdooLane.BACKGROUND, if (model == "0.7") "search_read" else "load_data", samples) }
                        tick++; delay(60_000)
                    }
                }
                // Bills: random arrivals at [billsPerHour]; a cashier tap every ~4 s while the cart is built.
                launch {
                    delay(Random.nextLong(0, 20_000))
                    while (isActive && System.currentTimeMillis() < endAt) {
                        var wait = 0L
                        repeat(5) { delay(Random.nextLong(2_000, 6_000)); wait += timed(client, OdooLane.TAP, "compute_all", samples) }
                        wait += timed(client, OdooLane.TAP, "search_read", samples) // customer
                        wait += timed(client, OdooLane.TAP, "load_data", samples) // ZZ Cash / offers
                        val sync = System.currentTimeMillis()
                        val before = samples.size
                        timed(client, OdooLane.SALE, "sync_from_ui", samples)
                        timed(client, OdooLane.SALE, "confirm_coupon_programs", samples)
                        if (samples.subList(before, samples.size).any { !it.ok }) failedSales.incrementAndGet()
                        wait += System.currentTimeMillis() - sync
                        // Bill: 0.7.0 builds it from sync_from_ui's reply; a customer's bill reads its ZZ Cash text once.
                        // Every 10th falls back to the full read-back (order, lines, payments, ZZ Cash), one after another.
                        if (Random.nextBoolean()) wait += timed(client, OdooLane.TAP, "read", samples)
                        if (Random.nextInt(10) == 0) repeat(4) { wait += timed(client, OdooLane.TAP, "read", samples) }
                        waits += wait
                        val gap = (-Math.log(1 - Random.nextDouble()) * 3_600_000.0 / billsPerHour).toLong()
                        delay(maxOf(0, gap - 30_000))
                    }
                }
            }
            withTimeoutOrNull(minutes * 60_000L + 5_000) { while (true) delay(1_000) }
            coroutineContext.cancelChildrenSafe()
        }
        server.shutdown()
        Result(model, stores, waits.size, waits.toList(), samples.toList(), odoo.served.get(), odoo.refused.get(), failedSales.get())
    }

    private fun kotlin.coroutines.CoroutineContext.cancelChildrenSafe() = this[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }

    private fun pct(xs: List<Long>, p: Double) = if (xs.isEmpty()) 0 else xs.sorted()[((xs.size - 1) * p).toInt()]

    @Test fun peakHours() {
        assumeTrue(System.getenv("PEAK_SIM") == "1")
        val minutes = System.getenv("PEAK_SIM_MINUTES")?.toInt() ?: 5
        val storeCounts = (System.getenv("PEAK_SIM_STORES") ?: "1,3,5,8").split(",").map { it.trim().toInt() }
        val models = (System.getenv("PEAK_SIM_MODELS") ?: "0.8").split(",").map { it.trim() }
        val results = runBlocking {
            models.flatMap { m -> storeCounts.map { n -> async(kotlinx.coroutines.Dispatchers.IO) { scenario(m, n, minutes, 80) } } }.awaitAll()
        }
        val report = buildString {
            appendLine("model,stores,bills,bill_wait_p50_ms,bill_wait_p95_ms,bill_wait_max_ms,tap_p95_ms,sale_p95_ms,bg_p95_ms,calls_ok,http_429,failed_calls_tap,failed_calls_sale,failed_calls_bg,sales_not_sent_at_once")
            for (r in results) {
                fun lane(l: OdooLane) = r.samples.filter { it.lane == l }
                appendLine(listOf(r.model, r.stores, r.bills, pct(r.billWaitsMs, .5), pct(r.billWaitsMs, .95), r.billWaitsMs.maxOrNull() ?: 0,
                    pct(lane(OdooLane.TAP).map { it.ms }, .95), pct(lane(OdooLane.SALE).map { it.ms }, .95), pct(lane(OdooLane.BACKGROUND).map { it.ms }, .95),
                    r.served, r.refused, lane(OdooLane.TAP).count { !it.ok }, lane(OdooLane.SALE).count { !it.ok }, lane(OdooLane.BACKGROUND).count { !it.ok },
                    r.failedSales).joinToString(","))
            }
        }
        println(report)
        System.getenv("PEAK_SIM_OUT")?.let { File(it).writeText(report) }
    }
}
