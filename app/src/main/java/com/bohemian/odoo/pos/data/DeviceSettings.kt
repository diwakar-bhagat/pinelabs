package com.bohemian.odoo.pos.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Per-terminal settings. The Odoo API key lives only here, encrypted at rest. */
class DeviceSettings(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "device_settings",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var odooApiKey: String?
        get() = prefs.getString("odoo_api_key", null)
        set(v) = prefs.edit().putString("odoo_api_key", v).apply()

    var posConfigId: Int
        get() = prefs.getInt("pos_config_id", 0)
        set(v) = prefs.edit().putInt("pos_config_id", v).apply()

    var posConfigName: String
        get() = prefs.getString("pos_config_name", "") ?: ""
        set(v) = prefs.edit().putString("pos_config_name", v).apply()

    /** Odoo pos.payment.method used for Card/UPI/BQR taken on the E700. */
    var terminalPaymentMethodId: Int
        get() = prefs.getInt("terminal_pm_id", 0)
        set(v) = prefs.edit().putInt("terminal_pm_id", v).apply()

    var cashPaymentMethodId: Int
        get() = prefs.getInt("cash_pm_id", 0)
        set(v) = prefs.edit().putInt("cash_pm_id", v).apply()

    /** Pine: printer line width is 24, 32 or 48 characters. */
    var printerWidth: Int
        get() = prefs.getInt("printer_width", 24) // Pine samples print E700 bills at 24 chars
        set(v) { require(v in setOf(24, 32, 48)); prefs.edit().putInt("printer_width", v).apply() }

    /** Debug builds only — ignored in release (see AppContainer). */
    var useFakeGateway: Boolean
        get() = prefs.getBoolean("use_fake_gateway", false)
        set(v) = prefs.edit().putBoolean("use_fake_gateway", v).apply()

    /**
     * Kiosk mode (Phase 5): start the till on boot and pin it with lock task when the
     * device policy allows it. Off by default; set on the Setup screen.
     */
    var kioskMode: Boolean
        get() = prefs.getBoolean("kiosk_mode", false)
        set(v) = prefs.edit().putBoolean("kiosk_mode", v).apply()

    val isConfigured: Boolean
        get() = !odooApiKey.isNullOrBlank() && posConfigId > 0 && terminalPaymentMethodId > 0
}
