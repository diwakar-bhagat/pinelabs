package com.bohemian.odoo.pos.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * On-screen diagnosis of the Pine payment app, for terminals where adb is not available.
 * Uses only Android's PackageManager — no Pine SDK call, so it cannot disturb a payment.
 * Package and action are the ones the Pine SDK 1.0.0 binds to (read from its classes.jar:
 * `com.pinelabs.masterapp` / `com.pinelabs.masterapp.SERVER`).
 */
object PineCheck {
    private const val MASTER_APP = "com.pinelabs.masterapp"
    private const val SERVER_ACTION = "com.pinelabs.masterapp.SERVER"

    fun report(context: Context, pineAppId: String, sdkPresent: Boolean): String = buildString {
        val pm = context.packageManager
        appendLine("Pine SDK in this build: " + if (sdkPresent) "yes" else "NO")
        appendLine("Pine App ID: " + pineAppId.ifBlank { "EMPTY" })

        val master = try { pm.getPackageInfo(MASTER_APP, 0) } catch (e: PackageManager.NameNotFoundException) { null }
        if (master == null) {
            appendLine("MasterApp ($MASTER_APP): NOT INSTALLED")
        } else {
            @Suppress("DEPRECATION")
            appendLine("MasterApp: installed, version ${master.versionName} (${master.versionCode})")
            val enabled = master.applicationInfo?.enabled ?: false
            appendLine("MasterApp enabled: " + if (enabled) "yes" else "NO")
            val services = pm.queryIntentServices(Intent(SERVER_ACTION).setPackage(MASTER_APP), 0)
            if (services.isEmpty()) {
                appendLine("Payment service ($SERVER_ACTION): NOT FOUND")
            } else services.forEach { r ->
                val s = r.serviceInfo
                appendLine("Payment service: ${s.name}")
                appendLine("  exported: ${s.exported}, enabled: ${s.enabled}, needs permission: ${s.permission ?: "none"}")
                s.permission?.let { perm ->
                    val granted = pm.checkPermission(perm, context.packageName) == PackageManager.PERMISSION_GRANTED
                    appendLine("  this app holds it: " + if (granted) "yes" else "NO")
                }
            }
        }

        @Suppress("DEPRECATION")
        val others = pm.getInstalledPackages(0).map { it.packageName }
            .filter { (it.contains("pinelabs") || it.contains("plutus")) && it != MASTER_APP }
        appendLine("Other Pine apps: " + others.ifEmpty { listOf("none") }.joinToString(", "))
    }
}
