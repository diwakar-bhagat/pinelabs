package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.PosLog
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.TimeUnit
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
) {
    suspend fun call(model: String, method: String, body: JsonObject = JsonObject(emptyMap())): JsonElement =
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
            val (code, text) = try {
                http.newCall(req).await()
            } catch (e: IOException) {
                throw OdooException.Network(e)
            }
            PosLog.d("ODOO_CALL", "m" to "$model.$method", "http" to code, "ms" to (System.currentTimeMillis() - started))
            when {
                code in 200..299 -> parse(text, code)
                code == 401 || code == 403 -> throw OdooException.Auth(errorMessage(text) ?: "Unauthorized")
                else -> throw OdooException.Server(code, errorName(text), errorMessage(text) ?: "HTTP $code")
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
    private suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val read = runCatching { response.use { it.code to (it.body?.string().orEmpty()) } }
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
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS) // the whole call, so nothing waits on Odoo for minutes
            .retryOnConnectionFailure(false) // never auto-repeat a POST that may have been processed
            .build()
    }
}
