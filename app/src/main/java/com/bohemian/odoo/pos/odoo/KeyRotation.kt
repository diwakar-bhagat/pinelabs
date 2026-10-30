package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.PosLog
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Each till signs in with a key of its own (F4, 0.7.0), all keys of one bot user (one Odoo seat), so a
 * lost till is revoked alone and every till renews its key before it expires (Odoo 19 keys last at most
 * 3 months below administrator). Odoo's programmatic keys: `res.users.apikeys.generate` (authenticated
 * with an existing key, returns the new one, shown only once) and `revoke`. Needs the Settings right, or
 * the system parameter `base.enable_programmatic_api_keys`, and room under `base.programmatic_api_keys_limit`.
 *
 * Crash-safe: a new key is saved as *pending* before it is checked and used; the old one is revoked
 * only after the switch, with the new key. Whatever step fails, the till keeps a working key.
 */
class KeyRotation(
    /** A client authenticated with exactly this key. */
    private val clientFor: (String) -> OdooJson2Client,
    private val store: KeyStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    interface KeyStore {
        var key: String?
        /** "2027-01-23", Odoo's expiration_date of [key]; null for a key entered by hand (expiry unknown). */
        var expiresOn: String?
        /** A key generated but not yet switched to. */
        var pending: String?
    }

    sealed interface Outcome {
        data object NotDue : Outcome
        data class Rotated(val expiresOn: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** Setup: an enrollment key (the bot user's) becomes this till's own key, [name] "E700 SWN 1340055807". */
    suspend fun enroll(enrollmentKey: String, name: String): String {
        val expires = day(VALID_DAYS)
        val own = generate(enrollmentKey, name, expires)
        verify(own)
        store.key = own
        store.expiresOn = expires
        store.pending = null
        PosLog.i("KEY_ENROLLED", "expires" to expires)
        return expires
    }

    /** In the background, once a day: a new key [ROTATE_DAYS] before expiry. */
    suspend fun rotateIfDue(name: String): Outcome {
        val current = store.key ?: return Outcome.NotDue
        val expires = store.expiresOn ?: return Outcome.NotDue // entered by hand: Odoo did not tell us
        if (store.pending == null && expires > day(ROTATE_DAYS)) return Outcome.NotDue
        return try {
            val newExpiry = day(VALID_DAYS)
            val fresh = store.pending ?: generate(current, name, newExpiry).also { store.pending = it }
            verify(fresh)
            store.key = fresh
            store.expiresOn = newExpiry
            store.pending = null
            // The old key goes only now, with the new one; if this fails the old key simply expires.
            runCatching { clientFor(fresh).call("res.users.apikeys", "revoke", jsonOf("key" to current)) }
                .onFailure { PosLog.w("KEY_REVOKE_FAILED", "err" to it.message) }
            PosLog.i("KEY_ROTATED", "expires" to newExpiry)
            Outcome.Rotated(newExpiry)
        } catch (e: OdooException) {
            PosLog.w("KEY_ROTATE_FAILED", "err" to e.message)
            Outcome.Failed(e.message ?: "Odoo refused")
        }
    }

    private suspend fun generate(withKey: String, name: String, expires: String): String =
        clientFor(withKey).call("res.users.apikeys", "generate", jsonOf(
            "key" to withKey, "scope" to "rpc", "name" to name, "expiration_date" to expires,
        )).jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() } ?: throw OdooException.Server(200, "BadResponse", "Odoo returned no key")

    /** The new key works before the till depends on it. */
    private suspend fun verify(key: String) {
        clientFor(key).call("res.users", "context_get")
    }

    /** "yyyy-MM-dd", [days] from today on the till's clock. */
    private fun day(days: Int): String = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        .format(Calendar.getInstance().apply { timeInMillis = now(); add(Calendar.DAY_OF_YEAR, days) }.time)

    /** Days left on [store]'s key, or null when unknown. */
    fun daysLeft(): Int? {
        val expires = store.expiresOn ?: return null
        val end = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(expires)!!.time }.getOrNull() ?: return null
        return ((end - now()) / 86_400_000L).toInt()
    }

    companion object {
        /** Below Odoo's 3-month limit for non-administrators. */
        const val VALID_DAYS = 85
        const val ROTATE_DAYS = 14
        /** A gold bar on the till when the key is this close to expiry and could not be renewed. */
        const val WARN_DAYS = 7
    }
}
