package com.bohemian.odoo.pos.pine

import android.content.Context
import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.core.PosLog

// Compiled only when the Pine SDK bundle is missing from app/libs (see app/build.gradle.kts).
// Lets the UI build and run with the Fake gateway; refuses every real payment.

object PineSdkBootstrap {
    fun init(context: Context, applicationId: String) {
        PosLog.w("PINE_STUB", "reason" to "SDK bundle not present at build time")
    }
}

class PineSdkGateway : PaymentGateway {
    override val name = "PINE_STUB"
    private val reject = GatewayResult.Rejected("SdkMissing", "Pine SDK not bundled in this build")
    override suspend fun sale(request: SaleRequest, onStarted: (String) -> Unit) = reject
    override suspend fun getStatus(billingRef: String, amount: Paise, mode: PayMode, onStarted: (String) -> Unit) = reject
    override suspend fun void(request: VoidRequest, onStarted: (String) -> Unit) = reject
    override suspend fun print(lines: List<PrintLine>, printRef: String): PrintResult = PrintResult.Failed("Pine SDK not bundled")
}
