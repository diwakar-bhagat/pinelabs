package com.bohemian.odoo.pos.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * [onClick] that ignores taps within [windowMs] of the last one, so an accidental double tap on a
 * button that calls Odoo or the terminal acts once. The ViewModel guards each action too; this only
 * spares it the second call.
 */
@Composable
fun onceIn(windowMs: Long = 600, onClick: () -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onClick)
    return remember(windowMs) {
        var last = 0L
        val tap: () -> Unit = {
            val now = SystemClock.uptimeMillis()
            if (now - last >= windowMs) { last = now; latest() }
        }
        tap
    }
}
