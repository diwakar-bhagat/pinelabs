package com.bohemian.odoo.pos.payment

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.pine.TxnDetails

/**
 * Acquirer code sent as `bank_code` on a card Void. Not derived from the sale result:
 * Pine's docs give "01" as the example and Pine's own reference app hardcodes it
 * (docs/PINE_LABS_VERIFIED.md). Confirm for ZenZebra's acquirer at UAT case 8.
 */
const val PINE_VOID_BANK_CODE = "01"

/** [isOpen] = needs a person before the session may close (Invariant I9). */
enum class VoidState(val isOpen: Boolean) {
    STARTING(true),       // persisted, about to call the SDK (crash here = ambiguous)
    IN_FLIGHT(true),      // SDK accepted (eventId known)
    NEEDS_REVIEW(true),   // outcome unknown — supervisor checks the terminal report
    VOIDED(true),         // terminal reversed the money; the Odoo order is still paid
    ODOO_REFUNDED(false), // supervisor recorded the refund in Odoo
    FAILED(false);        // terminal refused or never received it — the sale stands
}

data class VoidAttempt(
    val billingRef: String,   // new ref for the void operation [flows/void]
    val saleRef: String,      // billingRef of the sale being voided
    val orderUuid: String,
    val amount: Paise,        // equals the sale amount
    val state: VoidState,
    val eventId: String? = null,
    val details: TxnDetails? = null,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Durable record of every void. Room in the app, in-memory in tests. */
interface VoidJournal {
    suspend fun insert(attempt: VoidAttempt)
    suspend fun get(billingRef: String): VoidAttempt?
    suspend fun forSale(saleRef: String): List<VoidAttempt>
    /** Voids with [VoidState.isOpen]. */
    suspend fun open(): List<VoidAttempt>
    suspend fun setEventId(billingRef: String, eventId: String, now: Long)
    suspend fun transition(billingRef: String, state: VoidState, now: Long, details: TxnDetails? = null, note: String? = null)
}

sealed interface VoidOutcome {
    /** Terminal reversed the card charge. The Odoo refund is still to be recorded. */
    data class Voided(val attempt: VoidAttempt) : VoidOutcome
    /** Terminal said no (e.g. already settled — use a refund instead). The sale stands. */
    data class NotVoided(val attempt: VoidAttempt, val reason: String) : VoidOutcome
    /** Never reached the terminal. Safe to retry. */
    data class NotSent(val attempt: VoidAttempt, val reason: String) : VoidOutcome
    /** Outcome unknown. Never replayed; a supervisor resolves it. */
    data class Unresolved(val attempt: VoidAttempt) : VoidOutcome
    /** Refused on the device before anything was persisted or sent. */
    data class Refused(val reason: String) : VoidOutcome
}
