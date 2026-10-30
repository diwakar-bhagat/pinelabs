package com.bohemian.odoo.pos.payment

import com.bohemian.odoo.pos.core.BillingRefGenerator
import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.core.PosLog
import com.bohemian.odoo.pos.pine.GatewayResult
import com.bohemian.odoo.pos.pine.PayMode
import com.bohemian.odoo.pos.pine.PaymentGateway
import com.bohemian.odoo.pos.pine.SaleRequest
import com.bohemian.odoo.pos.pine.VoidRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class StatusPolicy(val maxChecks: Int = 3, val intervalMs: Long = 5_000)

sealed interface PaymentOutcome {
    val attempt: PaymentAttempt?
    data class Paid(override val attempt: PaymentAttempt) : PaymentOutcome
    data class NotPaid(override val attempt: PaymentAttempt, val reason: String) : PaymentOutcome
    /** Never reached the terminal — cashier may simply retry. */
    data class NotSent(override val attempt: PaymentAttempt, val reason: String) : PaymentOutcome
    /** Outcome unknown; order is blocked until [PaymentCoordinator.checkAgain] or supervisor resolution. */
    data class Unresolved(override val attempt: PaymentAttempt) : PaymentOutcome
    /** A previous attempt for this order is still unresolved — no new charge allowed. */
    data class Blocked(override val attempt: PaymentAttempt) : PaymentOutcome
    /** A previous attempt for this order already succeeded — refuse to charge twice. */
    data class AlreadyPaid(override val attempt: PaymentAttempt) : PaymentOutcome
}

/**
 * The payment state machine (CLAUDE.md §3). Every rule that protects real money lives here.
 * Pure Kotlin: unit-tested with a scripted gateway and an in-memory journal.
 */
class PaymentCoordinator(
    private val gateway: () -> PaymentGateway,
    private val journal: PaymentJournal,
    private val voids: VoidJournal,
    private val refs: () -> BillingRefGenerator,
    private val policy: StatusPolicy = StatusPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val backgroundScope: CoroutineScope,
) {
    /**
     * HARD RULE 3: one terminal operation at a time. Held for each SDK call and journal step, but
     * never across the waits of a status check, so a supervisor decision or another order is not
     * stuck behind a 15 s+ GetStatus loop.
     */
    private val mutex = Mutex()

    /**
     * Attempts whose status loop is running, so a second "Check status" does not start another,
     * and the unresolved bar can say "Checking with the terminal…".
     */
    private val _checking = MutableStateFlow<Set<String>>(emptySet())
    val checking: StateFlow<Set<String>> = _checking.asStateFlow()

    /**
     * Charge [amount] for [orderUuid]. [orderTotal] is the bill; a split bill is paid in several
     * attempts, each its own `billing_reference_id` (HARD RULE 8), until they cover it. A single
     * payment has `orderTotal == amount`, so any paid attempt refuses a second charge as before.
     * [expectedPaid] is what the screen saw paid when the cashier asked for this part: if more is paid
     * by the time it runs (a second tap queued behind the first), it is refused, never charged again.
     */
    suspend fun pay(
        orderUuid: String,
        amount: Paise,
        mode: PayMode,
        customerMobile: String? = null,
        orderTotal: Paise = amount,
        expectedPaid: Paise? = null,
    ): PaymentOutcome =
        withContext(NonCancellable) { // a payment is never abandoned half-way because a screen closed
            val first = mutex.withLock {
                require(amount.isPositive) { "amount must be positive" }

                journal.forOrder(orderUuid).let { previous ->
                    val paid = previous.filter { it.state.isPaid }
                    val paidSum = paid.fold(Paise.ZERO) { acc, p -> acc + p.amount }
                    // Never more than the bill: a second charge only for what is still due.
                    paid.lastOrNull()?.takeIf { paidSum + amount > orderTotal }?.let { return@withLock Step.Done(PaymentOutcome.AlreadyPaid(it)) }
                    paid.lastOrNull()?.takeIf { expectedPaid != null && paidSum > expectedPaid }?.let { return@withLock Step.Done(PaymentOutcome.AlreadyPaid(it)) }
                    previous.firstOrNull { it.state.blocksNewCharge }?.let { return@withLock Step.Done(PaymentOutcome.Blocked(it)) }
                }

                val now = clock()
                val attempt = PaymentAttempt(
                    billingRef = refs().next(), orderUuid = orderUuid, amount = amount, mode = mode,
                    state = AttemptState.STARTING, createdAt = now, updatedAt = now,
                )
                journal.insert(attempt) // HARD RULE 4: persist before acting
                PosLog.i("PAYMENT_START", "ref" to attempt.billingRef, "mode" to mode, "amount" to amount, "orderTotal" to orderTotal)

                val result = gateway().sale(SaleRequest(attempt.billingRef, amount, mode, customerMobile)) { eventId ->
                    // Callback runs on the SDK worker thread: hand off, return immediately.
                    backgroundScope.launch { journal.setEventId(attempt.billingRef, eventId, clock()) }
                }
                PosLog.i("PAYMENT_RESPONSE", "ref" to attempt.billingRef, "result" to result::class.simpleName)
                settle(attempt, result)
            }
            finish(first)
        }

    /**
     * Re-run the status check for an UNKNOWN / IN_FLIGHT UPI attempt (button or Reconciler).
     * Null when no such attempt exists. Never throws on a state the attempt has already left.
     */
    suspend fun checkAgain(billingRef: String): PaymentOutcome? = withContext(NonCancellable) {
        val first = mutex.withLock {
            val a = journal.get(billingRef) ?: return@withLock null
            when {
                a.state.isFinal -> Step.Done(outcomeOf(a, a.note ?: ""))
                !a.mode.needsStatusCheckOnFailure -> {
                    journal.transition(a.billingRef, AttemptState.NEEDS_REVIEW, clock(), note = "Card outcome unknown — check terminal report")
                    Step.Done(PaymentOutcome.Unresolved(journal.get(billingRef) ?: a))
                }
                else -> Step.CheckStatus(a)
            }
        } ?: return@withContext null
        finish(first)
    }

    /**
     * Supervisor decision for NEEDS_REVIEW / UNKNOWN after checking the terminal / charge slip.
     * Idempotent: an attempt that is already final (the status check got there first, or a
     * double tap) keeps its state and its outcome is returned. Null when there is no such attempt.
     */
    suspend fun resolveManually(billingRef: String, paid: Boolean, note: String): PaymentOutcome? = mutex.withLock {
        val a = journal.get(billingRef) ?: return@withLock null
        if (a.state.isFinal) {
            PosLog.w("PAYMENT_MANUAL_RESOLVE_IGNORED", "ref" to billingRef, "state" to a.state, "asked" to paid)
            return@withLock outcomeOf(a, a.note ?: "")
        }
        val state = if (paid) AttemptState.RESOLVED_PAID else AttemptState.RESOLVED_NOT_PAID
        journal.transition(billingRef, state, clock(), note = "manual: $note")
        PosLog.w("PAYMENT_MANUAL_RESOLVE", "ref" to billingRef, "paid" to paid)
        outcomeOf(journal.get(billingRef) ?: a, note)
    }

    /** Used by Reconciler at startup; caller must not hold the mutex. */
    internal suspend fun reconcile(a: PaymentAttempt): PaymentOutcome = withContext(NonCancellable) {
        val first = mutex.withLock {
            val fresh = journal.get(a.billingRef) ?: return@withLock Step.Done(PaymentOutcome.Unresolved(a))
            when {
                fresh.state.isFinal -> Step.Done(outcomeOf(fresh, fresh.note ?: ""))
                fresh.state == AttemptState.NEEDS_REVIEW -> Step.Done(PaymentOutcome.Unresolved(fresh))
                fresh.mode.needsStatusCheckOnFailure -> Step.CheckStatus(fresh)
                else -> {
                    journal.transition(fresh.billingRef, AttemptState.NEEDS_REVIEW, clock(),
                        note = "App restarted during card payment — verify on terminal")
                    Step.Done(PaymentOutcome.Unresolved(journal.get(fresh.billingRef) ?: fresh))
                }
            }
        }
        finish(first)
    }

    /** What a locked step decided: an outcome, or a status check to run without holding the lock. */
    private sealed interface Step {
        data class Done(val outcome: PaymentOutcome) : Step
        data class CheckStatus(val attempt: PaymentAttempt) : Step
    }

    private suspend fun finish(step: Step): PaymentOutcome = when (step) {
        is Step.Done -> step.outcome
        is Step.CheckStatus -> statusLoop(step.attempt)
    }

    // ------------------------------------------------------------------ void (Phase 4b)

    /**
     * Void a confirmed card sale on the terminal. Same rules as a sale: one operation in
     * flight (shared mutex), persisted before the SDK call, amount must match (I6), and an
     * ambiguous outcome is never replayed (HARD RULE 5) — it goes to NEEDS_REVIEW.
     */
    suspend fun void(saleRef: String): VoidOutcome = withContext(NonCancellable) {
        mutex.withLock {
            val sale = journal.get(saleRef) ?: return@withLock VoidOutcome.Refused("Payment $saleRef not found")
            if (!sale.state.isPaid) return@withLock VoidOutcome.Refused("Only a paid sale can be voided")
            if (sale.mode != PayMode.CARD) return@withLock VoidOutcome.Refused("Only card sales can be voided on the terminal")
            // Only a sale the terminal itself confirmed (it returned a transaction id) is voided from the till.
            sale.details?.transactionId
                ?: return@withLock VoidOutcome.Refused("No terminal transaction id for $saleRef — void it from the terminal menu")
            voids.forSale(saleRef).firstOrNull { it.state != VoidState.FAILED }?.let {
                return@withLock VoidOutcome.Refused("Sale $saleRef already has a void (${it.billingRef}, ${it.state})")
            }

            val now = clock()
            val v = VoidAttempt(
                billingRef = refs().next(), saleRef = saleRef, orderUuid = sale.orderUuid, amount = sale.amount,
                state = VoidState.STARTING, createdAt = now, updatedAt = now,
            )
            voids.insert(v) // HARD RULE 4: persist before acting
            PosLog.i("VOID_START", "ref" to v.billingRef, "sale" to saleRef, "amount" to v.amount)

            val result = gateway().void(VoidRequest(v.billingRef, v.amount, PINE_VOID_BANK_CODE, invoiceNumber = null)) { eventId ->
                backgroundScope.launch { voids.setEventId(v.billingRef, eventId, clock()) }
            }
            PosLog.i("VOID_RESPONSE", "ref" to v.billingRef, "result" to result::class.simpleName)
            settleVoid(v, result)
        }
    }

    /** Supervisor decision for a void in NEEDS_REVIEW, after checking the terminal report. */
    suspend fun resolveVoid(billingRef: String, voided: Boolean): VoidOutcome = mutex.withLock {
        val v = voids.get(billingRef) ?: error("no void $billingRef")
        check(v.state == VoidState.NEEDS_REVIEW) { "void not in review: ${v.state}" }
        val state = if (voided) VoidState.VOIDED else VoidState.FAILED
        voids.transition(billingRef, state, clock(), note = if (voided) "manual: voided on terminal" else "manual: not voided")
        PosLog.w("VOID_MANUAL_RESOLVE", "ref" to billingRef, "voided" to voided)
        val fresh = voids.get(billingRef)!!
        if (voided) VoidOutcome.Voided(fresh) else VoidOutcome.NotVoided(fresh, "Confirmed not voided")
    }

    /**
     * The refund for a voided sale is in Odoo: sent / queued by the till (4c), or recorded by
     * a supervisor in the back office. Closes the void.
     */
    suspend fun confirmOdooRefund(billingRef: String, note: String = "refund recorded in Odoo") = mutex.withLock {
        val v = voids.get(billingRef) ?: error("no void $billingRef")
        check(v.state == VoidState.VOIDED) { "void not voided: ${v.state}" }
        voids.transition(billingRef, VoidState.ODOO_REFUNDED, clock(), note = note)
        PosLog.i("VOID_ODOO_REFUNDED", "ref" to billingRef)
    }

    /** App start: a void left STARTING/IN_FLIGHT by a dead process has an unknown outcome. */
    internal suspend fun reconcileVoids(): Int = withContext(NonCancellable) {
        mutex.withLock {
            val stuck = voids.open().filter { it.state == VoidState.STARTING || it.state == VoidState.IN_FLIGHT }
            stuck.forEach {
                voids.transition(it.billingRef, VoidState.NEEDS_REVIEW, clock(), note = "App restarted during void — verify on terminal")
            }
            stuck.size
        }
    }

    private suspend fun settleVoid(v: VoidAttempt, r: GatewayResult): VoidOutcome = when (r) {
        is GatewayResult.Approved -> {
            val observed = r.details.amountPaise
            if (observed != null && observed != v.amount.value) {
                // INVARIANT I6 applies to reversals too.
                PosLog.e("VOID_AMOUNT_MISMATCH", null, "ref" to v.billingRef, "expected" to v.amount.value, "observed" to observed)
                voids.transition(v.billingRef, VoidState.NEEDS_REVIEW, clock(), details = r.details,
                    note = "Amount mismatch! Sale: ${v.amount.value} paise, Terminal reported: $observed paise")
                VoidOutcome.Unresolved(voids.get(v.billingRef)!!)
            } else {
                voids.transition(v.billingRef, VoidState.VOIDED, clock(), details = r.details, note = "Odoo refund pending")
                PosLog.i("VOID_SUCCESS", "ref" to v.billingRef)
                VoidOutcome.Voided(voids.get(v.billingRef)!!)
            }
        }
        is GatewayResult.Declined -> {
            voids.transition(v.billingRef, VoidState.FAILED, clock(), details = r.details, note = r.message)
            VoidOutcome.NotVoided(voids.get(v.billingRef)!!, r.message ?: "Void declined by terminal")
        }
        is GatewayResult.Rejected -> {
            voids.transition(v.billingRef, VoidState.FAILED, clock(), note = "${r.kind}: ${r.detail}")
            VoidOutcome.NotSent(voids.get(v.billingRef)!!, friendly(r.kind, r.detail))
        }
        is GatewayResult.Ambiguous -> {
            voids.transition(v.billingRef, VoidState.NEEDS_REVIEW, clock(), note = "${r.kind}: ${r.detail}")
            PosLog.w("VOID_NEEDS_REVIEW", "ref" to v.billingRef, "kind" to r.kind)
            VoidOutcome.Unresolved(voids.get(v.billingRef)!!)
        }
    }

    // ------------------------------------------------------------------ internals

    /** Called with the mutex held. A UPI outcome that needs GetStatus is handed back as [Step.CheckStatus]. */
    private suspend fun settle(a: PaymentAttempt, r: GatewayResult): Step = when (r) {
        is GatewayResult.Approved -> {
            // INVARIANT I6: An attempt is marked paid ONLY if observed amount equals expected requested amount
            val observedPaise = r.details.amountPaise
            if (observedPaise != null && observedPaise != a.amount.value) {
                PosLog.e("PAYMENT_AMOUNT_MISMATCH", null, "ref" to a.billingRef, "expected" to a.amount.value, "observed" to observedPaise)
                journal.transition(
                    a.billingRef,
                    AttemptState.NEEDS_REVIEW,
                    clock(),
                    details = r.details,
                    note = "Amount mismatch! Requested: ${a.amount.value} paise, Terminal reported: $observedPaise paise",
                )
                Step.Done(PaymentOutcome.Unresolved(current(a)))
            } else {
                journal.transition(a.billingRef, AttemptState.SUCCESS, clock(), details = r.details)
                PosLog.i("PAYMENT_SUCCESS", "ref" to a.billingRef)
                Step.Done(PaymentOutcome.Paid(current(a)))
            }
        }
        is GatewayResult.Rejected -> {
            journal.transition(a.billingRef, AttemptState.REJECTED, clock(), note = "${r.kind}: ${r.detail}")
            Step.Done(PaymentOutcome.NotSent(current(a), friendly(r.kind, r.detail)))
        }
        is GatewayResult.Declined ->
            if (a.mode.needsStatusCheckOnFailure) {
                // Pine: for UPI, a "failed"/session-expired response must be confirmed via GetStatus.
                Step.CheckStatus(a)
            } else {
                journal.transition(a.billingRef, AttemptState.DECLINED, clock(), details = r.details, note = r.message)
                PosLog.i("PAYMENT_FAILED", "ref" to a.billingRef, "code" to r.code)
                Step.Done(PaymentOutcome.NotPaid(current(a), r.message ?: "Declined"))
            }
        is GatewayResult.Ambiguous ->
            if (a.mode.needsStatusCheckOnFailure) {
                PosLog.w("PAYMENT_TIMEOUT", "ref" to a.billingRef, "kind" to r.kind)
                Step.CheckStatus(a)
            } else {
                // HARD RULE 5 (card): never auto-retry an ambiguous card sale.
                journal.transition(a.billingRef, AttemptState.NEEDS_REVIEW, clock(), note = "${r.kind}: ${r.detail}")
                PosLog.w("PAYMENT_NEEDS_REVIEW", "ref" to a.billingRef, "kind" to r.kind)
                Step.Done(PaymentOutcome.Unresolved(current(a)))
            }
    }

    /** The journal row as it is now; [fallback] if it has gone (it never is deleted, but never crash on it). */
    private suspend fun current(fallback: PaymentAttempt): PaymentAttempt = journal.get(fallback.billingRef) ?: fallback

    /**
     * Bounded GetStatus polling. Never loops forever; never replays the sale. Runs WITHOUT the
     * mutex: it is taken for each GetStatus and its journal step only. Before every check the
     * attempt is read again, so a supervisor decision made in the meantime wins and ends the loop.
     */
    private suspend fun statusLoop(a: PaymentAttempt): PaymentOutcome {
        val mine = mutex.withLock {
            if (a.billingRef in _checking.value) return@withLock false
            _checking.update { it + a.billingRef }
            journal.get(a.billingRef)?.takeIf { !it.state.isFinal }?.let { journal.transition(a.billingRef, AttemptState.STATUS_CHECK, clock()) }
            true
        }
        // Another status check for this attempt is already running: report it as unresolved for now.
        if (!mine) return PaymentOutcome.Unresolved(current(a))
        try {
            repeat(policy.maxChecks) { i ->
                delay(policy.intervalMs)
                val outcome = mutex.withLock { checkOnce(a, i + 1) }
                if (outcome != null) return outcome
            }
            return mutex.withLock {
                val fresh = current(a)
                if (fresh.state.isFinal || fresh.state == AttemptState.NEEDS_REVIEW) outcomeOf(fresh, fresh.note ?: "")
                else {
                    journal.transition(a.billingRef, AttemptState.UNKNOWN, clock(), note = "status not confirmed after ${policy.maxChecks} checks")
                    PaymentOutcome.Unresolved(current(a))
                }
            }
        } finally {
            _checking.update { it - a.billingRef }
        }
    }

    /** One GetStatus, with the mutex held. Null = not settled yet, check again. */
    private suspend fun checkOnce(a: PaymentAttempt, attemptNo: Int): PaymentOutcome? {
        val fresh = current(a)
        // A supervisor (or another check) already decided: stop, never overwrite their decision.
        if (fresh.state.isFinal || fresh.state == AttemptState.NEEDS_REVIEW) return outcomeOf(fresh, fresh.note ?: "")
        PosLog.i("GET_STATUS_REQUEST", "ref" to a.billingRef, "try" to attemptNo)
        val r = gateway().getStatus(a.billingRef, a.amount, a.mode)
        journal.transition(a.billingRef, AttemptState.STATUS_CHECK, clock(), incrementStatusChecks = true)
        PosLog.i("GET_STATUS_RESPONSE", "ref" to a.billingRef, "result" to r::class.simpleName)
        return when (r) {
            is GatewayResult.Approved -> {
                val observedPaise = r.details.amountPaise
                if (observedPaise != null && observedPaise != a.amount.value) {
                    PosLog.e("PAYMENT_AMOUNT_MISMATCH_STATUS", null, "ref" to a.billingRef, "expected" to a.amount.value, "observed" to observedPaise)
                    journal.transition(
                        a.billingRef,
                        AttemptState.NEEDS_REVIEW,
                        clock(),
                        details = r.details,
                        note = "GetStatus amount mismatch! Requested: ${a.amount.value} paise, Terminal reported: $observedPaise paise",
                    )
                    PaymentOutcome.Unresolved(current(a))
                } else {
                    journal.transition(a.billingRef, AttemptState.SUCCESS, clock(), details = r.details, note = "confirmed by GetStatus")
                    PaymentOutcome.Paid(current(a))
                }
            }
            is GatewayResult.Declined -> {
                journal.transition(a.billingRef, AttemptState.DECLINED, clock(), details = r.details, note = r.message)
                PaymentOutcome.NotPaid(current(a), r.message ?: "Not paid")
            }
            is GatewayResult.Ambiguous, is GatewayResult.Rejected -> null // try again
        }
    }

    private fun outcomeOf(a: PaymentAttempt, reason: String): PaymentOutcome = when {
        a.state.isPaid -> PaymentOutcome.Paid(a)
        a.state == AttemptState.REJECTED -> PaymentOutcome.NotSent(a, reason)
        a.state.isFinal -> PaymentOutcome.NotPaid(a, reason)
        else -> PaymentOutcome.Unresolved(a)
    }

    /**
     * Cashier text plus the SDK's own reason on a second line, so a photo of the screen is
     * enough to diagnose (e.g. "bindService returned false" vs "MasterApp is not installed").
     * SDK reasons carry no card or customer data.
     */
    internal fun friendly(kind: String, detail: String?): String =
        friendly(kind) + (detail?.takeIf { it.isNotBlank() }?.let { "\n[$kind: ${it.take(200)}]" } ?: "\n[$kind]")

    private fun friendly(kind: String) = when (kind) {
        "OperationInProgress" -> "Terminal is busy with another operation. Wait and retry."
        "TransportUnavailable", "NotConnected" -> "Pine Labs payment app not reachable on this device."
        "SdkMissing" -> "This build has no Pine Labs SDK. Use a real build on the E700."
        "InvalidInput" -> "Payment request was invalid. Contact support."
        else -> "Payment could not be started ($kind)."
    }
}
