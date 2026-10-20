package com.bohemian.odoo.pos.core

/** HARD RULE 11 helpers — use these whenever something sensitive might reach a log. */
object Redact {
    fun phone(p: String?): String = p?.filter { it.isDigit() }?.let { d ->
        if (d.length <= 4) "****" else "*".repeat(d.length - 4) + d.takeLast(4)
    } ?: "-"

    fun secret(s: String?): String = if (s.isNullOrEmpty()) "-" else "***(${s.length})"

    /** Keep only the last 4 digits of a PAN-like string. */
    fun last4(pan: String?): String? = pan?.filter { it.isDigit() }?.takeLast(4)?.takeIf { it.isNotEmpty() }
}
