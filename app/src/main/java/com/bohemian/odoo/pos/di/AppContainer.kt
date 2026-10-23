package com.bohemian.odoo.pos.di

import android.content.Context
import com.bohemian.odoo.pos.BuildConfig
import com.bohemian.odoo.pos.core.BillingRefGenerator
import com.bohemian.odoo.pos.core.PosLog
import com.bohemian.odoo.pos.data.AppDatabase
import com.bohemian.odoo.pos.data.DeviceSettings
import com.bohemian.odoo.pos.data.RoomPaymentJournal
import com.bohemian.odoo.pos.odoo.CatalogService
import com.bohemian.odoo.pos.odoo.CustomerService
import com.bohemian.odoo.pos.odoo.OdooJson2Client
import com.bohemian.odoo.pos.odoo.OrderSyncService
import com.bohemian.odoo.pos.odoo.PendingWork
import com.bohemian.odoo.pos.odoo.PricingService
import com.bohemian.odoo.pos.odoo.SaleFinalizer
import com.bohemian.odoo.pos.odoo.SessionService
import com.bohemian.odoo.pos.payment.PaymentCoordinator
import com.bohemian.odoo.pos.payment.Reconciler
import com.bohemian.odoo.pos.pine.FakePaymentGateway
import com.bohemian.odoo.pos.pine.PaymentGateway
import com.bohemian.odoo.pos.pine.PineSdkGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Manual DI (CLAUDE.md §2). One instance per process, owned by PosApp. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = DeviceSettings(context)
    val db = AppDatabase.create(context)

    val odoo = OdooJson2Client(BuildConfig.ODOO_BASE_URL, BuildConfig.ODOO_DB, apiKey = { settings.odooApiKey })
    val catalog = CatalogService(odoo)
    val fiscalPositions = com.bohemian.odoo.pos.odoo.FiscalPositionService(odoo)
    val pricing = PricingService(odoo, fiscalPositions)
    val sessions = SessionService(odoo, pendingWork = {
        PendingWork(unresolvedAttempts = journal.unresolved().size, unsyncedOrders = db.outbox().count())
    })
    val customers = CustomerService(odoo)
    val zzCash = com.bohemian.odoo.pos.odoo.ZzCashService(odoo)
    val orderSync = OrderSyncService(
        odoo = odoo,
        outbox = db.outbox(),
        onPayloadGenerated = { uuid, payload ->
            if (BuildConfig.DEBUG) {
                runCatching {
                    val dir = context.getExternalFilesDir("payload")
                    if (dir != null) {
                        if (!dir.exists()) dir.mkdirs()
                        java.io.File(dir, "$uuid.json").writeText(payload.toString())
                        PosLog.i("PAYLOAD_DUMP", "uuid" to uuid, "path" to dir.absolutePath)
                    }
                }
            }
        }
    )
    val finalizer = SaleFinalizer(db.snapshots(), orderSync, terminalPaymentMethodId = { settings.terminalPaymentMethodId })

    private val realGateway: PaymentGateway by lazy { PineSdkGateway() }
    private val fakeGateway: PaymentGateway by lazy { FakePaymentGateway() }

    /** Release builds can never use the fake gateway. */
    val gateway: PaymentGateway
        get() = if (BuildConfig.DEBUG && settings.useFakeGateway) fakeGateway else realGateway

    // One generator per config code, so its duplicate guard spans all attempts.
    private var refGen: BillingRefGenerator? = null
    @Synchronized private fun refs(): BillingRefGenerator {
        val code = settings.posConfigName.ifBlank { "E700" }
        return refGen?.takeIf { it.configCode == code } ?: BillingRefGenerator(code).also { refGen = it }
    }

    val journal = RoomPaymentJournal(db.attempts())
    val payments = PaymentCoordinator(
        gateway = { gateway },
        journal = journal,
        refs = ::refs,
        backgroundScope = appScope,
    )
    val reconciler = Reconciler(journal, payments, onPaid = { finalizer.finalizeTerminal(it) })

    fun startBackgroundWork() {
        appScope.launch {
            runCatching { reconciler.run() }.onFailure { PosLog.e("RECONCILE_FAILED", it) }
        }
        appScope.launch {
            while (isActive) {
                if (settings.isConfigured) runCatching { orderSync.flushDue() }
                delay(30_000)
            }
        }
    }
}
