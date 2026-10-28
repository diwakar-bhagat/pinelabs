package com.bohemian.odoo.pos.core

import java.security.MessageDigest

/** Where the Setup PIN lives (the encrypted [com.bohemian.odoo.pos.data.DeviceSettings] on the till). */
interface PinStore {
    /** Salted SHA-256 of the PIN; null = never changed, the default PIN applies. */
    var pinHash: String?
    /** Random per terminal, created on first use. */
    val pinSalt: String
    var pinFailures: Int
    /** Epoch ms until which no PIN is accepted; 0 = not locked. */
    var pinLockedUntil: Long
}

/**
 * The supervisor PIN in front of Setup (Odoo key, POS, payment methods, kiosk switch).
 * Default [DEFAULT_PIN] until a supervisor changes it in Setup. Only a salted hash is kept, and
 * [MAX_TRIES] wrong PINs lock it for [LOCK_MS]; the lock survives a restart (it is in the store).
 */
class SetupPin(private val store: PinStore, private val clock: () -> Long = System::currentTimeMillis) {

    sealed interface Check {
        data object Ok : Check
        data class Wrong(val triesLeft: Int) : Check
        data class Locked(val secondsLeft: Long) : Check
    }

    fun check(pin: String): Check {
        val now = clock()
        if (store.pinLockedUntil > now) return Check.Locked((store.pinLockedUntil - now + 999) / 1000)
        if (matches(pin)) {
            store.pinFailures = 0
            store.pinLockedUntil = 0
            return Check.Ok
        }
        val failures = store.pinFailures + 1
        if (failures >= MAX_TRIES) {
            store.pinFailures = 0
            store.pinLockedUntil = now + LOCK_MS
            PosLog.w("SETUP_PIN_LOCKED", "seconds" to LOCK_MS / 1000)
            return Check.Locked(LOCK_MS / 1000)
        }
        store.pinFailures = failures
        return Check.Wrong(MAX_TRIES - failures)
    }

    /** Null when the PIN was changed; otherwise why not. */
    fun change(current: String, new: String, confirm: String): String? {
        if (!isWellFormed(new)) return "The new PIN must be 4 to 6 digits."
        if (new != confirm) return "The two new PINs are not the same."
        when (val c = check(current)) {
            Check.Ok -> Unit
            is Check.Wrong -> return "The current PIN is wrong (${c.triesLeft} tries left)."
            is Check.Locked -> return "Too many wrong PINs. Try again in ${c.secondsLeft} s."
        }
        store.pinHash = hash(new, store.pinSalt)
        PosLog.i("SETUP_PIN_CHANGED")
        return null
    }

    private fun matches(pin: String): Boolean {
        val stored = store.pinHash ?: return pin == DEFAULT_PIN
        return MessageDigest.isEqual(stored.toByteArray(), hash(pin, store.pinSalt).toByteArray())
    }

    companion object {
        /** The PIN until a supervisor changes it (decided by ZenZebra, 2026-10-27). */
        const val DEFAULT_PIN = "1234"
        const val MAX_TRIES = 5
        const val LOCK_MS = 60_000L

        fun isWellFormed(pin: String) = pin.length in 4..6 && pin.all { it.isDigit() }

        internal fun hash(pin: String, salt: String): String =
            MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
