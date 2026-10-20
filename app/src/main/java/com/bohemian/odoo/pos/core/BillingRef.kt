package com.bohemian.odoo.pos.core

import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * HARD RULE 8: a unique billing_reference_id per payment attempt, never reused.
 * Format: <CFG4><yyMMddHHmmss><rand3>, alphanumeric, 19 chars.
 */
class BillingRefGenerator(
    val configCode: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val prefix: String = configCode.uppercase(Locale.ROOT)
        .filter { it.isLetterOrDigit() }
        .padEnd(4, 'X')
        .take(4)

    private var last: String = ""

    @Synchronized
    fun next(): String {
        val fmt = SimpleDateFormat("yyMMddHHmmss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }
        var candidate: String
        do {
            candidate = prefix + fmt.format(Date(clock())) + random.nextInt(1000).toString().padStart(3, '0')
        } while (candidate == last)
        last = candidate
        return candidate
    }
}
