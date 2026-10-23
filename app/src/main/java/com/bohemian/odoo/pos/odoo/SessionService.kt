package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.core.sum
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Work on this device that must finish before the session may close (Invariant I9). */
data class PendingWork(val unresolvedAttempts: Int, val unsyncedOrders: Int, val openVoids: Int = 0) {
    val isClear: Boolean get() = unresolvedAttempts == 0 && unsyncedOrders == 0 && openVoids == 0
}

/** One line of pos.session.get_closing_control_data. */
data class MethodTotal(val id: Int, val name: String, val amount: Paise, val count: Int)

/** Cash drawer as Odoo expects it at close: opening + cash sales + cash in/out. */
data class CashDrawer(val methodId: Int, val name: String, val opening: Paise, val payments: Paise, val expected: Paise) {
    val moves: Paise get() = expected - opening - payments
}

data class ClosingSummary(
    val orderCount: Int,
    val orderTotal: Paise,
    val cash: CashDrawer?,
    val nonCash: List<MethodTotal>,
) {
    val paymentsTotal: Paise get() = (listOfNotNull(cash?.payments) + nonCash.map { it.amount }).sum()
}

sealed interface CloseResult {
    data object Closed : CloseResult
    /** I9: refused on the device; nothing was sent to Odoo. */
    data class Blocked(val pending: PendingWork) : CloseResult
    /** Odoo answered {'successful': False, ...}. The session stays open (or needs the back end). */
    data class Refused(val message: String, val redirect: Boolean) : CloseResult
}

/**
 * pos.session open/close, following the Odoo 19 web POS closing popup
 * (point_of_sale/static/src/app/components/popups/closing_popup/closing_popup.js):
 * post_closing_cash_details (cash control only) → update_closing_control_state_session
 * → close_session_from_ui.
 */
class SessionService(
    private val odoo: OdooJson2Client,
    private val pendingWork: suspend () -> PendingWork,
) {
    /** pos.session.create opens in 'opening_control'; set_opening_control moves it to 'opened'. */
    suspend fun open(configId: Int, openingCash: Int = 0): Int {
        val sessionId = odoo.call("pos.session", "create", jsonOf(
            "vals_list" to listOf(mapOf("config_id" to configId)),
        )).let { el ->
            // create returns a list of ids via JSON-2
            (el as? JsonArray)?.first()?.jsonPrimitive?.intOrNull
                ?: el.jsonPrimitive.intOrNull
                ?: error("unexpected create() result: $el")
        }
        odoo.call("pos.session", "set_opening_control", jsonOf(
            "ids" to listOf(sessionId), "cashbox_value" to openingCash, "notes" to "Opened from E700 till",
        ))
        return sessionId
    }

    /** Totals for the closing screen and the Z-report. Read-only. */
    suspend fun closingSummary(sessionId: Int): ClosingSummary =
        parseSummary(odoo.call("pos.session", "get_closing_control_data", jsonOf("ids" to listOf(sessionId))) as JsonObject)

    /**
     * Close the session. I9 is checked first: an unresolved payment or a paid order not yet
     * in Odoo blocks the close before any call is made.
     *
     * [countedCash] is required when the config has cash control. Bank methods (the E700)
     * are sent with no counted difference: the terminal settles what Odoo recorded.
     */
    suspend fun close(sessionId: Int, cashControl: Boolean, countedCash: Paise?, notes: String): CloseResult {
        val pending = pendingWork()
        if (!pending.isClear) return CloseResult.Blocked(pending)

        val ids = listOf(sessionId)
        if (cashControl) {
            val counted = requireNotNull(countedCash) { "counted cash is required with cash control" }
            parseResult(odoo.call("pos.session", "post_closing_cash_details", jsonOf(
                "ids" to ids, "counted_cash" to counted.toRupees(),
            )))?.let { return it }
        }
        try {
            odoo.call("pos.session", "update_closing_control_state_session", jsonOf("ids" to ids, "notes" to notes))
        } catch (e: OdooException.Server) {
            // Closed elsewhere: close_session_from_ui below returns Odoo's own explanation.
            if (e.message?.contains("already closed") != true) throw e
        }
        return parseResult(odoo.call("pos.session", "close_session_from_ui", jsonOf(
            "ids" to ids, "bank_payment_method_diff_pairs" to emptyList<Any>(),
        ))) ?: CloseResult.Closed
    }

    companion object {
        /** null when Odoo said successful; otherwise why it refused. */
        internal fun parseResult(el: JsonElement): CloseResult.Refused? {
            val o = el as? JsonObject ?: return null // methods that return nothing succeeded
            if (o.bool("successful")) return null
            return CloseResult.Refused(o.str("message") ?: "Odoo refused to close the session", o.bool("redirect"))
        }

        internal fun parseSummary(o: JsonObject): ClosingSummary {
            val orders = o["orders_details"] as? JsonObject
            val cash = (o["default_cash_details"] as? JsonObject)?.takeIf { it.int("id") != null }?.let {
                CashDrawer(
                    methodId = it.int("id")!!,
                    name = it.str("name") ?: "Cash",
                    opening = it.money("opening"),
                    payments = it.money("payment_amount"),
                    expected = it.money("amount"),
                )
            }
            val nonCash = (o["non_cash_payment_methods"] as? JsonArray).orEmpty().map { it as JsonObject }
                .map { MethodTotal(it.int("id")!!, it.str("name") ?: "?", it.money("amount"), it.int("number") ?: 0) }
            return ClosingSummary(
                orderCount = orders?.int("quantity") ?: 0,
                orderTotal = orders?.money("amount") ?: Paise.ZERO,
                cash = cash,
                nonCash = nonCash,
            )
        }
    }
}
