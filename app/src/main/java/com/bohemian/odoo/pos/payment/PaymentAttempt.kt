package com.bohemian.odoo.pos.payment

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.pine.PayMode
import com.bohemian.odoo.pos.pine.TxnDetails

enum class AttemptState(val isFinal: Boolean, val blocksNewCharge: Boolean) {
    STARTING(false, true),          // persisted, about to call the SDK (crash here = ambiguous)
    IN_FLIGHT(false, true),         // SDK accepted (eventId known)
    STATUS_CHECK(false, true),      // UPI/BQR: running GetStatus
    SUCCESS(true, false),           // money received
    DECLINED(true, false),          // terminal said not paid
    REJECTED(true, false),          // never reached the terminal
    UNKNOWN(false, true),           // status checks exhausted — can "check again"
    NEEDS_REVIEW(false, true),      // card ambiguity — supervisor must resolve
    RESOLVED_PAID(true, false),     // supervisor confirmed paid (charge slip / terminal report)
    RESOLVED_NOT_PAID(true, false); // supervisor confirmed not paid

    val isPaid: Boolean get() = this == SUCCESS || this == RESOLVED_PAID
}

data class PaymentAttempt(
    val billingRef: String,
    val orderUuid: String,
    val amount: Paise,
    val mode: PayMode,
    val state: AttemptState,
    val eventId: String? = null,
    val details: TxnDetails? = null,
    val statusChecks: Int = 0,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Durable record of every payment attempt. Room in the app, in-memory in tests. */
interface PaymentJournal {
    suspend fun insert(attempt: PaymentAttempt)
    suspend fun get(billingRef: String): PaymentAttempt?
    suspend fun forOrder(orderUuid: String): List<PaymentAttempt>
    suspend fun unresolved(): List<PaymentAttempt>
    suspend fun setEventId(billingRef: String, eventId: String, now: Long)
    suspend fun transition(
        billingRef: String,
        state: AttemptState,
        now: Long,
        details: TxnDetails? = null,
        note: String? = null,
        incrementStatusChecks: Boolean = false,
    )
}
