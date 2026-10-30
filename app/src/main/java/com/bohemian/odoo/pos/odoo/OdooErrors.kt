package com.bohemian.odoo.pos.odoo

/**
 * What an Odoo failure means for the till, so nothing is retried blindly forever. JSON-2 answers an
 * error with a status and a body whose `name` is the exception class (Odoo 19 `Json2Dispatcher`):
 * `UserError` / `ValidationError` 422, `AccessDenied` / `AccessError` 403, `MissingError` 404,
 * `LockError` 409, a Python error (`builtins.ValueError` for an unknown field, `TypeError` for a wrong
 * argument) 500. Probed on the live database, 2026-10-30.
 */
enum class OdooErrorKind {
    /** Network, 408, 429, 502–504, a lock or a serialization conflict: it passes by itself. */
    TRANSIENT,
    /** The API key is wrong, revoked or expired: nothing reaches Odoo until a supervisor fixes it. */
    AUTH,
    /** sync_from_ui: the store has no open session; the order goes in when one opens. */
    NO_SESSION,
    /** Odoo refused the request itself (rules, data, a field it no longer has): a person must look. */
    REJECTED,
}

object OdooErrors {

    fun kind(e: Throwable): OdooErrorKind = when (e) {
        is OdooException.Network -> OdooErrorKind.TRANSIENT
        is OdooException.Auth -> OdooErrorKind.AUTH
        is OdooException.Server -> server(e.status, e.errorName, e.message)
        else -> OdooErrorKind.TRANSIENT
    }

    fun server(status: Int, name: String?, message: String?): OdooErrorKind {
        val n = name.orEmpty()
        return when {
            n.endsWith(".AccessDenied") || status == 401 -> OdooErrorKind.AUTH
            message?.contains(NO_SESSION_TEXT, ignoreCase = true) == true -> OdooErrorKind.NO_SESSION
            // A 2xx that is not JSON is a Wi-Fi login or proxy page, not Odoo's answer.
            n == "BadResponse" -> OdooErrorKind.TRANSIENT
            n.endsWith(".LockError") || n.endsWith(".ConcurrencyError") || n.startsWith("psycopg2.errors.SerializationFailure") ||
                n.startsWith("psycopg2.errors.LockNotAvailable") || n.startsWith("psycopg2.errors.DeadlockDetected") -> OdooErrorKind.TRANSIENT
            n.startsWith("odoo.exceptions.") || n.startsWith("builtins.") || n.startsWith("psycopg2.") ||
                n == "werkzeug.exceptions.NotFound" -> OdooErrorKind.REJECTED
            status == 408 || status == 409 || status == 429 || status >= 500 -> OdooErrorKind.TRANSIENT
            status in 400..499 -> OdooErrorKind.REJECTED
            else -> OdooErrorKind.TRANSIENT
        }
    }

    /** Odoo 19 `pos.order._process_order` when the order's session is closed and none is open. */
    const val NO_SESSION_TEXT = "No open session available"
}
