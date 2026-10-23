package com.bohemian.odoo.pos.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bohemian.odoo.pos.BuildConfig

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
        get() = prefs.getInt("printer_width", 32)
        set(v) { require(v in setOf(24, 32, 48)); prefs.edit().putInt("printer_width", v).apply() }

    /** Debug builds only — ignored in release (see AppContainer). */
    var useFakeGateway: Boolean
        get() = prefs.getBoolean("use_fake_gateway", false)
        set(v) = prefs.edit().putBoolean("use_fake_gateway", v).apply()

    /**
     * Business UPI collection IDs, entered on the Setup screen. Primary is used normally,
     * backup when the primary's bank is down. Business identifiers, not secrets: kept on the
     * device, never in the repo. Until Setup saves a value, the build's defaults apply
     * (BuildConfig, from the git-ignored upi.properties), so a reinstall comes back pre-filled.
     */
    var upiPrimaryVpa: String
        get() = prefs.getString("upi_primary_vpa", null) ?: BuildConfig.UPI_PRIMARY_DEFAULT
        set(v) = prefs.edit().putString("upi_primary_vpa", v.trim()).apply()

    var upiFallbackVpa: String
        get() = prefs.getString("upi_fallback_vpa", null) ?: BuildConfig.UPI_FALLBACK_DEFAULT
        set(v) = prefs.edit().putString("upi_fallback_vpa", v.trim()).apply()

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
