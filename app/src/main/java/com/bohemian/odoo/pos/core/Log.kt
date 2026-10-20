package com.bohemian.odoo.pos.core

import android.util.Log

/** Structured, tag-stable logging. Tags match the grep in docs/DEPLOY_E700.md. */
object PosLog {
    private const val TAG = "BohemianPOS"
    fun i(event: String, vararg kv: Pair<String, Any?>) = Log.i(TAG, line(event, kv))
    fun w(event: String, vararg kv: Pair<String, Any?>) = Log.w(TAG, line(event, kv))
    fun e(event: String, t: Throwable? = null, vararg kv: Pair<String, Any?>) = Log.e(TAG, line(event, kv), t)
    fun d(event: String, vararg kv: Pair<String, Any?>) = Log.d(TAG, line(event, kv))
    private fun line(event: String, kv: Array<out Pair<String, Any?>>) =
        "[$event] " + kv.joinToString(" ") { "${it.first}=${it.second}" }
}
