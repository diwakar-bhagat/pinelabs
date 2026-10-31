package com.bohemian.odoo.pos.odoo

import org.junit.Assert.assertEquals
import org.junit.Test

/** Live sync slows down while the till sells briskly (0.9.0). */
class LivePaceTest {
    private val now = 10_000_000L
    private fun ago(min: Int) = now - min * 60_000L

    @Test fun `quiet outside the sales day, every 2 minutes in it`() {
        assertEquals(LivePace.QUIET_MS, LivePace.intervalMs(now, salesDay = false, saleTimes = listOf(ago(1), ago(2), ago(3)), throttledAt = null))
        assertEquals(LivePace.LIVE_MS, LivePace.intervalMs(now, salesDay = true, saleTimes = listOf(ago(1), ago(2)), throttledAt = null))
    }

    @Test fun `three bills within 10 minutes is a rush, older ones do not count`() {
        assertEquals(LivePace.RUSH_MS, LivePace.intervalMs(now, true, listOf(ago(1), ago(4), ago(9)), null))
        assertEquals(LivePace.LIVE_MS, LivePace.intervalMs(now, true, listOf(ago(1), ago(4), ago(11)), null))
    }

    @Test fun `a 429 within 10 minutes slows it too`() {
        assertEquals(LivePace.RUSH_MS, LivePace.intervalMs(now, true, emptyList(), throttledAt = ago(3)))
        assertEquals(LivePace.LIVE_MS, LivePace.intervalMs(now, true, emptyList(), throttledAt = ago(12)))
    }
}
