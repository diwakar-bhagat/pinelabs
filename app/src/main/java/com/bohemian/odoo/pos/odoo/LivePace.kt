package com.bohemian.odoo.pos.odoo

/**
 * How often the till asks Odoo what changed (0.9.0). In a rush the till's own sales are what Odoo must
 * answer first: a live rush on one key (31 Oct) put a fifth of all calls in live sync. So while this till
 * is selling briskly, or Odoo answered 429 lately, a pass waits [RUSH_MS] instead of [LIVE_MS]; prices and
 * offers changed in Odoo then reach the till within 5 minutes instead of 2.
 */
object LivePace {
    /** In the sales day. */
    const val LIVE_MS = 120_000L

    /** In a rush. */
    const val RUSH_MS = 5 * 60_000L

    /** Outside the sales day (the config only). */
    const val QUIET_MS = 15 * 60_000L

    /** A rush: at least this many paid bills on this till within [RUSH_WINDOW_MS] (about 18 an hour). */
    const val RUSH_SALES = 3
    const val RUSH_WINDOW_MS = 10 * 60_000L

    /** [saleTimes]: when this till's recent bills were paid; [throttledAt]: Odoo's last 429 to this till. */
    fun intervalMs(now: Long, salesDay: Boolean, saleTimes: Collection<Long>, throttledAt: Long?): Long = when {
        !salesDay -> QUIET_MS
        rush(now, saleTimes, throttledAt) -> RUSH_MS
        else -> LIVE_MS
    }

    fun rush(now: Long, saleTimes: Collection<Long>, throttledAt: Long?): Boolean =
        saleTimes.count { now - it < RUSH_WINDOW_MS } >= RUSH_SALES || (throttledAt != null && now - throttledAt < RUSH_WINDOW_MS)
}
