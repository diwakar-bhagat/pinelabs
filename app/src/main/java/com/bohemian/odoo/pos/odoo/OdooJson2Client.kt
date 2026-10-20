package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.PosLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

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
            try {
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    PosLog.d("ODOO_CALL", "m" to "$model.$method", "http" to resp.code, "ms" to (System.currentTimeMillis() - started))
                    when {
                        resp.isSuccessful -> json.parseToJsonElement(text.ifEmpty { "null" })
                        resp.code == 401 || resp.code == 403 -> throw OdooException.Auth(errorMessage(text) ?: "Unauthorized")
                        else -> throw OdooException.Server(resp.code, errorName(text), errorMessage(text) ?: "HTTP ${resp.code}")
                    }
                }
            } catch (e: IOException) {
                throw OdooException.Network(e)
            }
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
            .retryOnConnectionFailure(false) // never auto-repeat a POST that may have been processed
            .build()
    }
}
