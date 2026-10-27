package com.bohemian.odoo.pos.core

import android.util.Log

/** Structured, tag-stable logging. Tags match the grep in docs/DEPLOY_E700.md. */
object PosLog {
    private const val TAG = "BohemianPOS"

    /**
     * Free-text values (server / terminal error messages) can echo customer data back, e.g. an
     * Odoo validation error quoting a phone number. They go through [CrashLog.redact]
     * (HARD RULE 11). Structured ids (ref, rrn, txnId) are left readable for reconciliation.
     */
    private val FREE_TEXT_KEYS = setOf("err", "detail", "msg")

    fun i(event: String, vararg kv: Pair<String, Any?>) = Log.i(TAG, line(event, kv))
    fun w(event: String, vararg kv: Pair<String, Any?>) = Log.w(TAG, line(event, kv))
    fun e(event: String, t: Throwable? = null, vararg kv: Pair<String, Any?>) =
        Log.e(TAG, line(event, kv) + (t?.let { "\n" + CrashLog.redact(Log.getStackTraceString(it)) } ?: ""))
    fun d(event: String, vararg kv: Pair<String, Any?>) = Log.d(TAG, line(event, kv))

    internal fun line(event: String, kv: Array<out Pair<String, Any?>>) =
        "[$event] " + kv.joinToString(" ") { (k, v) ->
            "$k=" + if (k in FREE_TEXT_KEYS && v != null) CrashLog.redact(v.toString()) else v
        }
}
