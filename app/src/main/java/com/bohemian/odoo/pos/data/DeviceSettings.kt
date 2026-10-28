package com.bohemian.odoo.pos.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bohemian.odoo.pos.core.PinStore
import java.util.concurrent.ConcurrentHashMap

/** Per-terminal settings. The Odoo API key lives only here, encrypted at rest. */
class DeviceSettings(context: Context) : PinStore {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "device_settings",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /**
     * Decrypted values kept in memory. EncryptedSharedPreferences decrypts on every read, and some
     * of these are read on every Odoo call (the API key) or every print. Writes go to disk at once.
     */
    private val memo = ConcurrentHashMap<String, Any>()
    private object None

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(key: String, load: () -> T): T =
        memo.getOrPut(key) { load() ?: None }.let { if (it === None) null else it } as T

    private fun write(key: String, value: Any?, put: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(put).apply()
        memo[key] = value ?: None
    }

    var odooApiKey: String?
        get() = read("odoo_api_key") { prefs.getString("odoo_api_key", null) }
        set(v) = write("odoo_api_key", v) { putString("odoo_api_key", v) }

    var posConfigId: Int
        get() = read("pos_config_id") { prefs.getInt("pos_config_id", 0) }
        set(v) = write("pos_config_id", v) { putInt("pos_config_id", v) }

    var posConfigName: String
        get() = read("pos_config_name") { prefs.getString("pos_config_name", "") ?: "" }
        set(v) = write("pos_config_name", v) { putString("pos_config_name", v) }

    /** Before 0.5.0: the one Odoo pos.payment.method for Card and UPI. Card and UPI fall back to it. */
    var terminalPaymentMethodId: Int
        get() = read("terminal_pm_id") { prefs.getInt("terminal_pm_id", 0) }
        set(v) = write("terminal_pm_id", v) { putInt("terminal_pm_id", v) }

    /** Odoo pos.payment.method for card taken on the E700 (0 = no card on this till). */
    var cardPaymentMethodId: Int
        get() = read("card_pm_id") { prefs.getInt("card_pm_id", terminalPaymentMethodId) }
        set(v) = write("card_pm_id", v) { putInt("card_pm_id", v) }

    /** Odoo pos.payment.method for UPI taken on the E700 (0 = no UPI on this till). */
    var upiPaymentMethodId: Int
        get() = read("upi_pm_id") { prefs.getInt("upi_pm_id", terminalPaymentMethodId) }
        set(v) = write("upi_pm_id", v) { putInt("upi_pm_id", v) }

    var cashPaymentMethodId: Int
        get() = read("cash_pm_id") { prefs.getInt("cash_pm_id", 0) }
        set(v) = write("cash_pm_id", v) { putInt("cash_pm_id", v) }

    /** Pine: printer line width is 24, 32 or 48 characters. */
    var printerWidth: Int
        get() = read("printer_width") { prefs.getInt("printer_width", 24) } // Pine samples print E700 bills at 24 chars
        set(v) { require(v in setOf(24, 32, 48)); write("printer_width", v) { putInt("printer_width", v) } }

    /** Debug builds only — ignored in release (see AppContainer). */
    var useFakeGateway: Boolean
        get() = read("use_fake_gateway") { prefs.getBoolean("use_fake_gateway", false) }
        set(v) = write("use_fake_gateway", v) { putBoolean("use_fake_gateway", v) }

    /**
     * Kiosk mode (Phase 5): start the till on boot and pin it with lock task when the
     * device policy allows it. Off by default; set on the Setup screen.
     */
    var kioskMode: Boolean
        get() = read("kiosk_mode") { prefs.getBoolean("kiosk_mode", false) }
        set(v) = write("kiosk_mode", v) { putBoolean("kiosk_mode", v) }

    /**
     * Print the company logo at the top of the bill, as on Odoo's PDF. Off by default: Pine's
     * image printing is not proven on the E700 yet (a refused image prints the bill without it).
     */
    var printLogo: Boolean
        get() = read("print_logo") { prefs.getBoolean("print_logo", false) }
        set(v) = write("print_logo", v) { putBoolean("print_logo", v) }

    // Setup PIN (core/SetupPin.kt): only a salted hash is kept, never the PIN.

    override var pinHash: String?
        get() = prefs.getString("setup_pin_hash", null)
        set(v) = prefs.edit().putString("setup_pin_hash", v).apply()

    override val pinSalt: String
        get() = prefs.getString("setup_pin_salt", null) ?: java.util.UUID.randomUUID().toString()
            .also { prefs.edit().putString("setup_pin_salt", it).apply() }

    override var pinFailures: Int
        get() = prefs.getInt("setup_pin_failures", 0)
        set(v) = prefs.edit().putInt("setup_pin_failures", v).apply()

    override var pinLockedUntil: Long
        get() = prefs.getLong("setup_pin_locked_until", 0L)
        set(v) = prefs.edit().putLong("setup_pin_locked_until", v).apply()

    /** Height of a keypad key in dp as the cashier last sized it; 0 = keypad folded away. */
    var keypadKeyDp: Int
        get() = read("keypad_key_dp") { prefs.getInt("keypad_key_dp", 36) }
        set(v) = write("keypad_key_dp", v) { putInt("keypad_key_dp", v) }

    /** The store's sales day on the SALES screen: from this hour (default 06:00)… */
    var salesDayStartHour: Int
        get() = read("sales_day_start") { prefs.getInt("sales_day_start", 6) }
        set(v) = write("sales_day_start", v) { putInt("sales_day_start", v) }

    /** …to this hour (24 = midnight). */
    var salesDayEndHour: Int
        get() = read("sales_day_end") { prefs.getInt("sales_day_end", 24) }
        set(v) = write("sales_day_end", v) { putInt("sales_day_end", v) }

    val isConfigured: Boolean
        get() = !odooApiKey.isNullOrBlank() && posConfigId > 0 &&
            (cardPaymentMethodId > 0 || upiPaymentMethodId > 0 || cashPaymentMethodId > 0)
}
