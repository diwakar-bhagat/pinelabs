package com.bohemian.odoo.pos.payment

import com.bohemian.odoo.pos.core.PosLog

/**
 * Runs at app start and after any ambiguous failure (Pine "Startup Reconciliation" recipe).
 * The SDK keeps no state across process death — our journal does. When an attempt turns
 * out to be paid, [onPaid] records the order in Odoo (via SaleFinalizer + outbox).
 */
class Reconciler(
    private val journal: PaymentJournal,
    private val coordinator: PaymentCoordinator,
    private val onPaid: suspend (PaymentAttempt) -> Unit,
) {
    suspend fun run(): List<PaymentOutcome> {
        val pending = journal.unresolved().filter { it.state != AttemptState.NEEDS_REVIEW }
        if (pending.isNotEmpty()) PosLog.w("RECONCILE_START", "count" to pending.size)
        return pending.map { a ->
            coordinator.reconcile(a).also { outcome ->
                if (outcome is PaymentOutcome.Paid) runCatching { onPaid(outcome.attempt) }
                    .onFailure { PosLog.e("RECONCILE_FINALIZE_FAILED", it, "ref" to a.billingRef) }
            }
        }
    }
}
