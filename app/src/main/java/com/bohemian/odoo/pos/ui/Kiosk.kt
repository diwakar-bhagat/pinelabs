package com.bohemian.odoo.pos.ui

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bohemian.odoo.pos.PosApp
import com.bohemian.odoo.pos.core.PosLog

/**
 * Kiosk mode (Phase 5). Lock task is entered only when the device policy controller
 * (PAXSTORE / device owner) has whitelisted this package — never the user-confirmed
 * screen-pinning prompt. The DPC whitelist must also include `com.pinelabs.masterapp`:
 * in lock task, launching a non-whitelisted package's activity is blocked, which would
 * stop every App-to-App payment (docs/INFOSEC.md).
 */
object Kiosk {
    fun permitted(context: Context): Boolean =
        (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            .isLockTaskPermitted(context.packageName)

    fun isLocked(context: Context): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).lockTaskModeState !=
            ActivityManager.LOCK_TASK_MODE_NONE

    /** Called from onResume: brings the activity in line with the setting. */
    fun apply(activity: Activity, enabled: Boolean) {
        val locked = isLocked(activity)
        try {
            when {
                enabled && !locked && permitted(activity) -> { activity.startLockTask(); PosLog.i("KIOSK_LOCKED") }
                !enabled && locked -> { activity.stopLockTask(); PosLog.i("KIOSK_UNLOCKED") }
            }
        } catch (e: RuntimeException) {
            PosLog.w("KIOSK_FAILED", "err" to e.message) // never block the till on kiosk trouble
        }
    }
}

/** Starts the till after a reboot when kiosk mode is on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = (context.applicationContext as PosApp).container.settings
        if (!settings.kioskMode) return
        PosLog.i("BOOT_START_TILL")
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
