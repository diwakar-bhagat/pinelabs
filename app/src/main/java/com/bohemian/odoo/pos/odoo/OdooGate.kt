package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.PosLog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import kotlin.coroutines.CoroutineContext

/**
 * Which queue an Odoo call waits in. Set around a block, `withContext(OdooLane.BACKGROUND) { … }`;
 * a call outside any lane is a [TAP], since a cashier is usually waiting on it.
 */
enum class OdooLane : CoroutineContext.Element {
    /** Money already taken, or a session or refund step: sync_from_ui, loyalty booking, session open/close. */
    SALE,
    /** The cashier is waiting: pricing, customer search, codes, ZZ Cash, the catalogue load. */
    TAP,
    /** Nobody is waiting: live sync, the SALES refresh, photos, outbox retries. */
    BACKGROUND;

    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<OdooLane> {
        suspend fun current(): OdooLane = currentCoroutineContext()[Key] ?: TAP
    }
}

/**
 * Odoo Online accepts about one call a second "with no parallel calls" (odoo.com/acceptable-use), and a
 * database that gets more answers 429. So a till sends at most [maxInFlight] calls at once, the highest
 * [OdooLane] first and in order within a lane. A background call runs alone, at most one every
 * [backgroundGapMs], and never while a sale or the cashier waits.
 */
class OdooGate(
    private val maxInFlight: Int = 2,
    private val backgroundGapMs: Long = 1_000,
    /**
     * Pacing: this till starts about [perSecond] calls a second, with up to [burst] at once after a quiet
     * spell, so it waits a moment itself instead of Odoo answering 429 and the backoff costing seconds.
     */
    private val perSecond: Double = 1.0,
    private val burst: Double = 2.0,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Waiter(val lane: OdooLane, val seq: Long)

    private val lock = Mutex()
    /** Bumped on every change, so waiters look again. */
    private val changed = MutableStateFlow(0L)
    private val waiting = ArrayList<Waiter>()
    private var seq = 0L
    private var inFlight = 0
    private var backgroundInFlight = 0
    private var lastBackgroundAt: Long? = null
    private var tokens = burst
    private var tokensAt: Long? = null

    suspend fun <T> run(lane: OdooLane, block: suspend () -> T): T {
        acquire(lane)
        try {
            return block()
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    inFlight--
                    if (lane == OdooLane.BACKGROUND) backgroundInFlight--
                    changed.value++
                }
            }
        }
    }

    private suspend fun acquire(lane: OdooLane) {
        val me = lock.withLock { Waiter(lane, seq++).also { waiting += it } }
        try {
            while (true) {
                val seen = changed.value
                val waitMs = lock.withLock { tryStart(me) }
                if (waitMs == 0L) return
                withTimeoutOrNull(if (waitMs > 0) waitMs else Long.MAX_VALUE) { changed.first { it != seen } }
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) { lock.withLock { if (waiting.remove(me)) changed.value++ } }
            throw e
        }
    }

    /** 0 = started; above 0 = a background call waits that long for its gap; -1 = wait for a change. */
    private fun tryStart(me: Waiter): Long {
        val ahead = waiting.any { it !== me && (it.lane < me.lane || (it.lane == me.lane && it.seq < me.seq)) }
        if (ahead || inFlight >= maxInFlight) return -1
        val t = now()
        tokens = minOf(burst, tokens + ((tokensAt?.let { t - it } ?: 0L) / 1000.0) * perSecond)
        tokensAt = t
        if (tokens < 1) return maxOf(1L, ((1 - tokens) / perSecond * 1000).toLong())
        if (me.lane == OdooLane.BACKGROUND) {
            if (backgroundInFlight > 0) return -1
            val gap = lastBackgroundAt?.let { it + backgroundGapMs - now() } ?: 0
            if (gap > 0) return gap
            backgroundInFlight++
            lastBackgroundAt = now()
        }
        tokens -= 1
        waiting.remove(me)
        inFlight++
        changed.value++
        return 0
    }
}

/**
 * What this till asked of Odoo: calls per lane over the last 10 minutes and the 429s of today, for
 * Setup and an `ODOO_RATE` log line every 10 minutes. Nothing leaves the device.
 */
class OdooStats(private val now: () -> Long = System::currentTimeMillis) {
    data class Snapshot(val perMinute: Double, val byLane: Map<OdooLane, Int>, val throttledToday: Int, val lastThrottledAt: Long?)

    private val calls = ArrayDeque<Pair<Long, OdooLane>>()
    private var throttledToday = 0
    private var lastThrottledAt: Long? = null
    private var day = -1
    private var loggedAt = 0L

    @Synchronized
    fun record(lane: OdooLane, httpStatus: Int) {
        val t = now()
        calls.addLast(t to lane)
        dropOld(t)
        val today = dayOf(t)
        if (today != day) { day = today; throttledToday = 0 }
        if (httpStatus == 429) { throttledToday++; lastThrottledAt = t }
        if (t - loggedAt >= WINDOW_MS) {
            loggedAt = t
            val s = snapshotAt(t)
            PosLog.i("ODOO_RATE", "per_min" to "%.1f".format(s.perMinute), "sale" to s.byLane[OdooLane.SALE],
                "tap" to s.byLane[OdooLane.TAP], "bg" to s.byLane[OdooLane.BACKGROUND], "throttled_today" to s.throttledToday)
        }
    }

    @Synchronized
    fun snapshot(): Snapshot = snapshotAt(now())

    private fun snapshotAt(t: Long): Snapshot {
        dropOld(t)
        if (dayOf(t) != day) { day = dayOf(t); throttledToday = 0 }
        val byLane = OdooLane.entries.associateWith { lane -> calls.count { it.second == lane } }
        return Snapshot(calls.size / (WINDOW_MS / 60_000.0), byLane, throttledToday, lastThrottledAt)
    }

    private fun dropOld(t: Long) { while (calls.isNotEmpty() && calls.first().first < t - WINDOW_MS) calls.removeFirst() }

    private fun dayOf(t: Long): Int = Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }

    companion object { const val WINDOW_MS = 10 * 60_000L }
}
