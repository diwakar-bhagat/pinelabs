package com.bohemian.odoo.pos.core

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local crash reports (Phase 5). No third-party crash service: nothing leaves the device,
 * so the Pine infosec review has no new endpoint to assess. Reports live in the app's
 * private files dir, are redacted before they touch disk (HARD RULE 11), and only the
 * newest [KEEP] are kept. Payment safety does not depend on this: the Reconciler and the
 * journal already recover any payment interrupted by a crash.
 */
class CrashLog(
    private val dir: File,
    /** "0.4.0 (4) PROD": which build crashed, so a photo of the report is enough. */
    private val build: String = "",
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Chains to the platform handler so Android still restarts / reports as usual. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun write(threadName: String, error: Throwable): File {
        dir.mkdirs()
        val now = clock()
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date(now))
        val file = File(dir, "crash-$now.txt")
        file.writeText(redact("time: $stamp\napp: $build\nthread: $threadName\n\n$trace"))
        reports().drop(KEEP).forEach { it.delete() }
        return file
    }

    /** Newest first. */
    fun reports(): List<File> =
        dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: 0 }
            .orEmpty()

    fun clear() = reports().forEach { it.delete() }

    companion object {
        const val KEEP = 10

        private val BEARER = Regex("(?i)(bearer\\s+)[^\\s\"']+")
        private val API_KEY = Regex("(?i)(api[_-]?key\\s*[=:]\\s*)[^\\s,\"']+")
        /** 10+ digit runs: Indian mobiles (10/12 digits with 91) and card PANs (13–19). */
        private val LONG_DIGITS = Regex("\\d{10,}")

        /** HARD RULE 11: no keys, no phone numbers, no PANs in a crash report. */
        fun redact(text: String): String = text
            .replace(BEARER) { it.groupValues[1] + "***" }
            .replace(API_KEY) { it.groupValues[1] + "***" }
            .replace(LONG_DIGITS) { "*".repeat(it.value.length - 4) + it.value.takeLast(4) }
    }
}
