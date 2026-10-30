package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.PosLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Each till's health in one Odoo list (F5, 0.7.0): a row per terminal in the custom model
 * [MODEL], written every 15 minutes, so nobody has to walk to 20 tills to see which one is stuck.
 * The model is created once in Odoo (`tools/odoo/create_till_status_model.py`); until it exists the
 * till skips this silently. Never blocks or slows a sale (background lane).
 */
class TillStatus(private val odoo: OdooJson2Client) {

    data class Report(
        val serial: String,
        val store: String,
        val version: String,
        /** Paid orders still on the till, and how many of them Odoo refused. */
        val outbox: Int,
        val rejected: Int,
        /** Payments to check on the yellow bar. */
        val unresolved: Int,
        /** Sales today that could not reach Odoo at once (offline or refused). */
        val notSentAtOnce: Int,
        val callsPerMinute: Double,
        val throttledToday: Int,
        val lastError: String?,
        /** The till's API key expiry (Odoo date), when known. */
        val keyExpires: String?,
    )

    @Volatile private var rowId: Int? = null
    @Volatile private var modelMissing = false

    /** Writes [r] to this till's row (created the first time). Returns whether Odoo has it now. */
    suspend fun send(r: Report, nowUtc: String): Boolean {
        if (modelMissing) return false
        val vals = mapOf(
            "x_name" to r.serial, "x_store" to r.store, "x_version" to r.version, "x_last_seen" to nowUtc,
            "x_outbox" to r.outbox, "x_rejected" to r.rejected, "x_unresolved" to r.unresolved,
            "x_offline_sales" to r.notSentAtOnce, "x_calls_per_min" to r.callsPerMinute, "x_throttled_today" to r.throttledToday,
            "x_last_error" to (r.lastError?.take(250) ?: false), "x_key_expires" to (r.keyExpires ?: false),
        )
        return try {
            val id = rowId ?: odoo.call(MODEL, "search", jsonOf("domain" to listOf(listOf("x_name", "=", r.serial)), "limit" to 1))
                .jsonArray.firstOrNull()?.jsonPrimitive?.intOrNull
            if (id == null) {
                // A lost answer to create is found by the search next time: never two rows for one till.
                rowId = odoo.call(MODEL, "create", jsonOf("vals_list" to listOf(vals))).let { el ->
                    (el as? JsonArray)?.firstOrNull()?.jsonPrimitive?.intOrNull ?: el.jsonPrimitive.intOrNull
                }
            } else {
                odoo.call(MODEL, "write", jsonOf("ids" to listOf(id), "vals" to vals))
                rowId = id
            }
            true
        } catch (e: OdooException.Server) {
            when {
                e.errorName == "werkzeug.exceptions.NotFound" -> { modelMissing = true; PosLog.i("TILL_STATUS_NO_MODEL") }
                e.errorName?.endsWith(".MissingError") == true -> rowId = null // the row was deleted: create it again
                else -> PosLog.w("TILL_STATUS_FAILED", "err" to e.message)
            }
            false
        } catch (e: OdooException) {
            false
        }
    }

    companion object {
        const val MODEL = "x_e700_till"
        const val EVERY_MS = 15 * 60_000L
    }
}
