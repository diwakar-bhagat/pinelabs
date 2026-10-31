package com.bohemian.odoo.pos.odoo

import java.util.concurrent.ConcurrentHashMap

/**
 * The ZZ Cash card reads of the last [REUSE_MS] (0.9.1). A waiting bill reopened soon after (the cashier
 * switching between open bills) shows the card it showed a moment ago instead of asking Odoo again.
 * A customer's entry is forgotten when one of their bills goes to payment, since their points move then.
 * This only saves a display read: Odoo still checks a reward at payment (`validate_coupon_programs`).
 */
class RecentCards(private val now: () -> Long = System::currentTimeMillis) {

    /** A reusable read: [card] is null when the customer had no card. */
    class Hit(val card: ZzCard?)

    private class Entry(val programId: Int, val at: Long, val card: ZzCard?)

    private val byPartner = ConcurrentHashMap<Int, Entry>()

    fun put(partnerId: Int, programId: Int, card: ZzCard?) {
        byPartner[partnerId] = Entry(programId, now(), card)
    }

    /** The read of [partnerId]'s card in [programId] within [REUSE_MS], or null: Odoo is asked again. */
    fun fresh(partnerId: Int, programId: Int): Hit? {
        val e = byPartner[partnerId] ?: return null
        if (e.programId != programId || now() - e.at >= REUSE_MS) return null
        return Hit(e.card)
    }

    fun forget(partnerId: Int) {
        byPartner.remove(partnerId)
    }

    companion object {
        const val REUSE_MS = 120_000L
    }
}
