package com.bohemian.odoo.pos.di

import android.content.Context
import com.bohemian.odoo.pos.BuildConfig
import com.bohemian.odoo.pos.core.BillingRefGenerator
import com.bohemian.odoo.pos.core.PosLog
import com.bohemian.odoo.pos.data.PriceCacheEntity
import com.bohemian.odoo.pos.data.AppDatabase
import com.bohemian.odoo.pos.data.DeviceSettings
import com.bohemian.odoo.pos.data.RoomPaymentJournal
import com.bohemian.odoo.pos.data.RoomVoidJournal
import com.bohemian.odoo.pos.odoo.CatalogService
import com.bohemian.odoo.pos.odoo.CustomerService
import com.bohemian.odoo.pos.odoo.OdooContractCheck
import com.bohemian.odoo.pos.odoo.KeyRotation
import com.bohemian.odoo.pos.odoo.OdooJson2Client
import com.bohemian.odoo.pos.odoo.PosLoad
import kotlin.random.Random
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import com.bohemian.odoo.pos.odoo.OdooLane
import com.bohemian.odoo.pos.odoo.SalesService
import com.bohemian.odoo.pos.odoo.TillStatus
import com.bohemian.odoo.pos.odoo.OrderSyncService
import com.bohemian.odoo.pos.odoo.PendingWork
import com.bohemian.odoo.pos.odoo.RefundService
import com.bohemian.odoo.pos.odoo.PricingService
import com.bohemian.odoo.pos.odoo.SaleFinalizer
import com.bohemian.odoo.pos.odoo.SessionService
import com.bohemian.odoo.pos.payment.PaymentCoordinator
import com.bohemian.odoo.pos.payment.Reconciler
import com.bohemian.odoo.pos.pine.FakePaymentGateway
import com.bohemian.odoo.pos.pine.PaymentGateway
import com.bohemian.odoo.pos.pine.PineSdkGateway
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Manual DI (CLAUDE.md §2). One instance per process, owned by PosApp. */
class AppContainer(context: Context) {
    /**
     * Background work (Reconciler, outbox loop, SDK callbacks). A failure in one job is logged
     * and written as a local crash report; it never takes the till down.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
        PosLog.e("BACKGROUND_JOB_FAILED", e)
        runCatching { crashLog.write("background", e) }
    })

    val settings = DeviceSettings(context)
    /** Reads the reports PosApp's installed handler writes (same private dir). */
    val crashLog = com.bohemian.odoo.pos.core.CrashLog(java.io.File(context.filesDir, "crash"), build = com.bohemian.odoo.pos.BuildInfo.label)
    val db = AppDatabase.create(context)

    /** The E700's serial (PayDroid 7.1 still gives Build.SERIAL without a permission). */
    @Suppress("DEPRECATION")
    val terminalSerial: String = android.os.Build.SERIAL?.takeIf { it.isNotBlank() && it != android.os.Build.UNKNOWN } ?: "unknown"

    val odoo = OdooJson2Client(BuildConfig.ODOO_BASE_URL, BuildConfig.ODOO_DB, apiKey = { settings.odooApiKey },
        userAgent = { "ZenZebraTill/${BuildConfig.VERSION_NAME} (${settings.posConfigName.ifBlank { "no POS" }}; $terminalSerial)" })

    /** F4: this till's own Odoo key: enrolled once at Setup, renewed before it expires. */
    val keys = KeyRotation(
        clientFor = { key -> OdooJson2Client(BuildConfig.ODOO_BASE_URL, BuildConfig.ODOO_DB, apiKey = { key },
            userAgent = { "ZenZebraTill/${BuildConfig.VERSION_NAME} (${settings.posConfigName.ifBlank { "no POS" }}; $terminalSerial)" }) },
        store = object : KeyRotation.KeyStore {
            override var key: String? by settings::odooApiKey
            override var expiresOn: String? by settings::apiKeyExpiresOn
            override var pending: String? by settings::pendingApiKey
        },
    )

    /** The key's name in Odoo (Preferences > Account Security lists it), so a lost till's key is easy to find. */
    fun keyName(): String = "E700 ${settings.posConfigName.ifBlank { "till" }} $terminalSerial"

    /** Odoo's own POS loader (0.8.0): the catalogue and live sync in one call each while a session is open. */
    val posLoad = PosLoad(odoo)

    /** F2: Odoo's fields checked when its version changes; payments stop if one the till needs is gone. */
    val contract = OdooContractCheck(odoo)
    val catalog = CatalogService(odoo)
    val catalogCache = com.bohemian.odoo.pos.odoo.CatalogCache(java.io.File(context.filesDir, "catalog/catalog.json"))
    val productImages = com.bohemian.odoo.pos.ui.ProductImages(java.io.File(context.filesDir, "img"), appScope) { ids -> catalog.images(ids) }
    val fiscalPositions = com.bohemian.odoo.pos.odoo.FiscalPositionService(odoo)
    /** Odoo's price answers, kept in Room so a cart Odoo priced before still sells while Odoo is out of reach (F3). */
    val pricing = PricingService(odoo, fiscalPositions, store = object : PricingService.PriceStore {
        override suspend fun all() = db.priceCache().all().map {
            PricingService.PriceStore.Saved(it.taxKey, it.unitPaise, it.qty, it.fposId, it.inclPaise, it.exclPaise, it.taxStamp)
        }
        override suspend fun save(s: PricingService.PriceStore.Saved) = db.priceCache().upsert(
            PriceCacheEntity(s.taxKey, s.unitPaise, s.qty, s.fposId, s.incl, s.excl, s.stamp, System.currentTimeMillis()))
        override suspend fun keepOnly(stamp: String) = db.priceCache().keepOnly(stamp)
    })
    val sessions = SessionService(odoo, pendingWork = {
        PendingWork(
            unresolvedAttempts = journal.unresolved().size,
            unsyncedOrders = db.outbox().count(),
            openVoids = voidJournal.open().size,
            pendingLoyalty = db.loyalty().count(),
            partlyPaid = finalizer.partlyPaid().size,
        )
    })
    val customers = CustomerService(odoo)
    val bills = com.bohemian.odoo.pos.odoo.BillService(odoo)
    val sales = com.bohemian.odoo.pos.odoo.SalesService(odoo)
    val liveSync = com.bohemian.odoo.pos.odoo.LiveSync(odoo)
    val billText = com.bohemian.odoo.pos.odoo.BillTextService(odoo)
    val offers = com.bohemian.odoo.pos.odoo.OffersService(odoo)
    val pricelists = com.bohemian.odoo.pos.odoo.PricelistService(odoo)

    /** The Odoo methods this terminal books card, UPI and cash on (Setup; `PaymentRoles.defaults`). */
    fun paymentRoles() = com.bohemian.odoo.pos.odoo.PaymentRoles(settings.cardPaymentMethodId, settings.upiPaymentMethodId, settings.cashPaymentMethodId)
    val zzCash = com.bohemian.odoo.pos.odoo.ZzCashService(odoo)
    val orderSync = OrderSyncService(
        odoo = odoo,
        outbox = db.outbox(),
        loyalty = db.loyalty(),
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
    val refunds = RefundService(odoo, orderSync, cardPaymentMethodId = { settings.cardPaymentMethodId })
    val finalizer = SaleFinalizer(
        db.snapshots(), orderSync,
        terminalMethodFor = { mode -> paymentRoles().forMode(mode) },
        paidAttempts = { uuid -> journal.forOrder(uuid).filter { it.state.isPaid } },
        cashPaymentMethodId = { settings.cashPaymentMethodId },
    )

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
    val voidJournal = RoomVoidJournal(db.voids())
    val payments = PaymentCoordinator(
        gateway = { gateway },
        journal = journal,
        voids = voidJournal,
        refs = ::refs,
        backgroundScope = appScope,
    )
    val reconciler = Reconciler(journal, payments, onPaid = { finalizer.finalizeTerminal(it) }, recoverPaid = { finalizer.recoverPaid() })

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
        // F4: renew this till's key before it expires (once a day; nothing for a key entered by hand).
        appScope.launch {
            delay(5 * 60_000 + Random.nextLong(10 * 60_000))
            while (isActive) {
                if (settings.isConfigured) runCatching { withContext(OdooLane.BACKGROUND) { keys.rotateIfDue(keyName()) } }
                delay(24 * 60 * 60_000L)
            }
        }
        // F5: this till's row in Odoo's till list, every 15 minutes (first within 1–2 minutes of start).
        appScope.launch {
            delay(60_000 + Random.nextLong(60_000))
            while (isActive) {
                if (settings.isConfigured) runCatching {
                    withContext(OdooLane.BACKGROUND) { tillStatus.send(statusReport(), SalesService.utc(System.currentTimeMillis())) }
                }
                delay(TillStatus.EVERY_MS)
            }
        }
    }

    val tillStatus = TillStatus(odoo)

    private suspend fun statusReport(): TillStatus.Report {
        val problems = db.outbox().observeProblems().first()
        val refused = problems.firstOrNull { it.kind == "REJECTED" }
        val rate = odoo.stats.snapshot()
        return TillStatus.Report(
            serial = terminalSerial, store = settings.posConfigName, version = BuildConfig.VERSION_NAME,
            outbox = db.outbox().count(), rejected = refused?.count ?: 0,
            unresolved = db.attempts().observeUnresolved().first().size, notSentAtOnce = orderSync.notSentAtOnceToday,
            callsPerMinute = rate.perMinute, throttledToday = rate.throttledToday,
            lastError = refused?.lastError ?: problems.firstOrNull()?.lastError, keyExpires = settings.apiKeyExpiresOn,
        )
    }
}
