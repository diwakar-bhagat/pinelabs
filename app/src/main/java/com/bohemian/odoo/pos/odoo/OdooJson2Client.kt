package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.PosLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class OdooException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No network / DNS / TLS / timeout. Retryable. */
    class Network(cause: IOException) : OdooException("Network: ${cause.message}", cause)
    /** 401/403 — API key wrong, revoked, or user lacks rights. Not retryable. */
    class Auth(message: String) : OdooException(message)
    /** Odoo raised (UserError, ValidationError, …). Not retryable without a change. */
    class Server(val status: Int, val errorName: String?, message: String) : OdooException(message)
}

/**
 * Odoo 19 External JSON-2 API: POST /json/2/<model>/<method>, bearer API key.
 * (XML-RPC / JSON-RPC are deprecated in 19 and removed in 22 — do not use them.)
 */
class OdooJson2Client(
    private val baseUrl: String,
    private val database: String,
    private val apiKey: () -> String?,
    private val http: OkHttpClient = defaultHttp(),
    val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val throttle: Throttle = Throttle(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * How long each lane may wait out Odoo's 429 / 503 before it gives up, and the backoff when Odoo
     * gives no Retry-After. Tests shrink them.
     */
    data class Throttle(
        val saleBudgetMs: Long = 20_000,
        val tapBudgetMs: Long = 10_000,
        val backgroundBudgetMs: Long = 120_000,
        val firstBackoffMs: Long = 2_000,
        val maxBackoffMs: Long = 60_000,
        /** 0..1, for jitter; tests pin it. */
        val jitter: () -> Double = { Random.nextDouble() },
    ) {
        fun budgetMs(lane: OdooLane) = when (lane) {
            OdooLane.SALE -> saleBudgetMs
            OdooLane.TAP -> tapBudgetMs
            OdooLane.BACKGROUND -> backgroundBudgetMs
        }
    }

    /** At most 2 calls in flight from this till, sales first, background alone (Odoo: no parallel calls). */
    val gate = OdooGate(now = now)

    /** Calls per lane and 429s, for Setup and the `ODOO_RATE` log line. */
    val stats = OdooStats(now)

    /** Until when every call from this till waits: Odoo said 429 (Too Many Requests) or 503. */
    @Volatile private var coolUntil = 0L

    /**
     * One JSON-2 call, in the [OdooLane] of the caller's context. Odoo Online answers 429 (or 503)
     * when a database gets too many requests. A 429 is refused without running, so it is sent again
     * after Retry-After (in full, seconds or a date) or 2 / 4 / 8 … s, with jitter so 20 tills do not
     * come back together. Every other call from this till waits with it. A 503 is sent again only
     * when repeating it is harmless ([retrySafe]). A lane gives up after its budget: the cashier sees
     * "Odoo is busy", a sale goes to the outbox, live sync tries on its next pass.
     */
    suspend fun call(model: String, method: String, body: JsonObject = JsonObject(emptyMap())): JsonElement {
        val lane = OdooLane.current()
        val started = now()
        val budget = throttle.budgetMs(lane)
        var attempt = 0
        while (true) {
            val cool = coolUntil - now()
            if (cool > 0) {
                if (now() + cool - started > budget) throw busy()
                delay(cool)
            }
            val result = gate.run(lane) { send(model, method, body) }
            stats.record(lane, result.code)
            if (result.code != 429 && (result.code != 503 || !retrySafe(method))) return result.value()
            attempt++
            val wait = backoffMs(result.retryAfterMs, attempt)
            coolUntil = maxOf(coolUntil, now() + wait)
            if (now() + wait - started > budget) return result.value()
            PosLog.w("ODOO_THROTTLED", "m" to "$model.$method", "http" to result.code, "lane" to lane, "attempt" to attempt, "wait_ms" to wait)
        }
    }

    /** At least what Odoo asked (plus up to 25%), else 2 / 4 / 8 … s up to a minute, ±25%. */
    private fun backoffMs(retryAfterMs: Long?, attempt: Int): Long {
        val own = (throttle.firstBackoffMs shl (attempt - 1).coerceAtMost(20)).coerceAtMost(throttle.maxBackoffMs)
        val jittered = (own * (0.75 + throttle.jitter() * 0.5)).toLong()
        val asked = retryAfterMs?.let { (it * (1 + throttle.jitter() * 0.25)).toLong() } ?: 0L
        return maxOf(jittered, asked)
    }

    private fun busy() = OdooException.Server(429, "TooManyRequests", "Odoo is busy (too many requests). Wait a moment and retry.")

    /** An answer, or the HTTP error to throw; [retryAfterMs] when Odoo said how long to wait. */
    private class Sent(val code: Int, val retryAfterMs: Long?, val value: () -> JsonElement)

    private suspend fun send(model: String, method: String, body: JsonObject): Sent =
        withContext(Dispatchers.IO) {
            val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw OdooException.Auth("Odoo API key not set")
            val req = Request.Builder()
                .url("$baseUrl/json/2/$model/$method")
                .header("Authorization", "bearer $key")
                .header("X-Odoo-Database", database)
                .header("User-Agent", "BohemianOdooPOS/Android")
                .post(body.toString().toRequestBody(JSON))
                .build()
            val started = System.currentTimeMillis()
            val (code, text, retryAfter) = try {
                http.newCall(req).await()
            } catch (e: IOException) {
                throw OdooException.Network(e)
            }
            PosLog.d("ODOO_CALL", "m" to "$model.$method", "http" to code, "ms" to (System.currentTimeMillis() - started))
            Sent(code, if (code == 429 || code == 503) retryAfterMs(retryAfter, now()) else null) {
                when {
                    code in 200..299 -> parse(text, code)
                    code == 401 || code == 403 -> throw OdooException.Auth(errorMessage(text) ?: "Unauthorized")
                    code == 429 -> throw busy()
                    else -> throw OdooException.Server(code, errorName(text), errorMessage(text) ?: "HTTP $code")
                }
            }
        }

    /**
     * A 2xx that is not JSON (a Wi-Fi login page, a proxy error page) is an Odoo error like any
     * other, so every caller that handles [OdooException] (outbox, loyalty) handles it too.
     */
    private fun parse(text: String, code: Int): JsonElement = try {
        json.parseToJsonElement(text.ifEmpty { "null" })
    } catch (e: SerializationException) {
        throw OdooException.Server(code, "BadResponse", "Odoo sent an answer the till cannot read")
    }

    /**
     * Runs the request on OkHttp's own threads. Cancelling the coroutine cancels the HTTP call, so
     * a timeout or a screen that moved on really stops waiting (a blocking execute() cannot be
     * stopped). Re-sent writes are safe: orders and loyalty are deduplicated by Odoo (I7).
     */
    private suspend fun Call.await(): Triple<Int, String, String?> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val read = runCatching { response.use { Triple(it.code, it.body?.string().orEmpty(), it.header("Retry-After")) } }
                if (!cont.isActive) return
                read.fold(
                    { cont.resume(it) },
                    { e -> cont.resumeWithException(e as? IOException ?: IOException(e)) },
                )
            }
        })
    }

    private fun errorObj(text: String): JsonObject? = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    private fun errorMessage(text: String) = errorObj(text)?.get("message")?.jsonPrimitive?.contentOrNull
    private fun errorName(text: String) = errorObj(text)?.get("name")?.jsonPrimitive?.contentOrNull

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * Methods that may be sent again after a 503 (Odoo may have run them): reads, and the writes
         * Odoo deduplicates by the order uuid (I7). Never a create (`res.partner.create`,
         * `pos.session.create`), which would make a second record.
         */
        private val SAFE_TO_REPEAT = setOf(
            "search_read", "read", "search", "search_count", "formatted_read_group", "read_group", "context_get",
            "compute_all", "load_data", "get_closing_control_data", "validate_coupon_programs",
            "sync_from_ui", "confirm_coupon_programs",
        )

        fun retrySafe(method: String) = method in SAFE_TO_REPEAT

        /** Retry-After in seconds or as an HTTP date (RFC 7231); null when absent or unreadable. */
        fun retryAfterMs(header: String?, nowMs: Long): Long? {
            val h = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            h.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0) }
            return runCatching {
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
                    .parse(h)!!.time - nowMs
            }.getOrNull()?.coerceAtLeast(0)
        }

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS) // the whole call, so nothing waits on Odoo for minutes
            .retryOnConnectionFailure(false) // never auto-repeat a POST that may have been processed
            .build()
    }
}
