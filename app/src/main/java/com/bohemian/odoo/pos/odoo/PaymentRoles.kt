package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.pine.PayMode

/**
 * The Odoo payment method this terminal books each tender on (0 = that tender is off here).
 *
 * Stores are set up differently in Odoo (live, 2026-10-28):
 * - **ZenZebra** has its own "Pine Labs E700 (A2A)" method and a Cash-type "Cash".
 * - **KLJ, SWN, HQ27GGN and Club 125** have neither. Their web POS books card and UPI taken outside
 *   Odoo's terminal links on "Cards" and "UPI", and cash on a Bank-type "Cash".
 *
 * [defaults] picks the same way at any store, so a new store needs no app change; Setup shows the
 * picks and a supervisor can change them.
 */
data class PaymentRoles(val card: Int = 0, val upi: Int = 0, val cash: Int = 0) {

    /** The method a terminal payment of [mode] is booked on (UPI also covers the old Bharat QR rows). */
    fun forMode(mode: PayMode): Int = if (mode == PayMode.CARD) card else upi

    val any: Boolean get() = card != 0 || upi != 0 || cash != 0

    val ids: Set<Int> get() = setOf(card, upi, cash) - 0

    /** Every method set here is one Setup offers for its tender, among a store's [methods]. */
    fun offeredIn(methods: List<PaymentMethod>): Boolean {
        val terminal = terminalChoices(methods).map { it.id }
        val cashIds = cashChoices(methods).map { it.id }
        return (card == 0 || card in terminal) && (upi == 0 || upi in terminal) && (cash == 0 || cash in cashIds)
    }

    /**
     * Why a method set here is one the till must not book on, or null ([methods]: at least the ones
     * set here). Builds before 0.5.0 could save one: SWN's till booked card and UPI on "Pine Labs SWN",
     * the web POS's own Pine link (2026-10-29). Odoo takes those sales, so the till warns, never stops.
     */
    fun unbookableWarning(methods: List<PaymentMethod>): String? {
        val byId = methods.associateBy { it.id }
        val wrong = listOf("Card" to card, "UPI" to upi, "Cash" to cash)
            .mapNotNull { (tender, id) -> byId[id]?.takeIf { !bookable(it) }?.let { tender to it } }
        if (wrong.isEmpty()) return null
        return wrong.groupBy({ it.second }, { it.first }).map { (m, tenders) ->
            val why = if (m.terminal != null || m.kind != "none") "which Odoo's web POS uses with its own device" else "which is not a till payment method"
            "${tenders.joinToString(" and ")} ${if (tenders.size > 1) "are" else "is"} booked on \"${m.name}\", $why"
        }.joinToString("; ") + ". Ask a supervisor to tap SETUP and choose again."
    }

    companion object {
        /**
         * A method the till may book on. Excluded:
         * - methods tied to a device Odoo drives itself (`use_payment_terminal`: the web POS's own Pine
         *   Labs link, Razorpay), and QR methods: their refunds call that device;
         * - online payments and customer accounts.
         */
        fun bookable(m: PaymentMethod): Boolean = m.terminal == null && m.kind == "none" && m.type in setOf("cash", "bank")

        /** Card / UPI choices: bank methods that are not the store's cash. */
        fun terminalChoices(methods: List<PaymentMethod>): List<PaymentMethod> =
            methods.filter { bookable(it) && it.type == "bank" && !it.isCash && !it.name.contains("cash", ignoreCase = true) }

        /** Cash choices: Odoo's cash-count methods first, then a Bank-type method named Cash (how four stores book cash). */
        fun cashChoices(methods: List<PaymentMethod>): List<PaymentMethod> =
            methods.filter { bookable(it) && (it.isCash || it.name.contains("cash", ignoreCase = true)) }.sortedByDescending { it.isCash }

        /**
         * The store's own E700 method for card and UPI when it has one, else its "Card…" and "UPI"
         * methods; its cash-count method, else the method named Cash.
         */
        fun defaults(methods: List<PaymentMethod>): PaymentRoles {
            val terminal = terminalChoices(methods)
            val e700 = terminal.firstOrNull { it.name.contains("E700", ignoreCase = true) }
            return PaymentRoles(
                card = (e700 ?: terminal.firstOrNull { it.name.contains("card", ignoreCase = true) })?.id ?: 0,
                upi = (e700 ?: terminal.firstOrNull { it.name.contains("upi", ignoreCase = true) })?.id ?: 0,
                cash = cashChoices(methods).firstOrNull()?.id ?: 0,
            )
        }
    }
}
