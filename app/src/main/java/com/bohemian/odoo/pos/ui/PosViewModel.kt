package com.bohemian.odoo.pos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bohemian.odoo.pos.BuildConfig
import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.core.PosLog
import com.bohemian.odoo.pos.core.Redact
import com.bohemian.odoo.pos.core.SetupPin
import com.bohemian.odoo.pos.receipt.TicketQr
import com.bohemian.odoo.pos.di.AppContainer
import com.bohemian.odoo.pos.odoo.CartLine
import com.bohemian.odoo.pos.odoo.CartTotals
import com.bohemian.odoo.pos.odoo.CatalogIncomplete
import com.bohemian.odoo.pos.odoo.AppliedOffers
import com.bohemian.odoo.pos.odoo.CardBalance
import com.bohemian.odoo.pos.odoo.CatalogService
import com.bohemian.odoo.pos.odoo.Claim
import com.bohemian.odoo.pos.odoo.Claimable
import com.bohemian.odoo.pos.odoo.CouponChange
import com.bohemian.odoo.pos.odoo.CouponCodeResult
import com.bohemian.odoo.pos.odoo.OfferInput
import com.bohemian.odoo.pos.odoo.OfferProgram
import com.bohemian.odoo.pos.odoo.OfferProgramInfo
import com.bohemian.odoo.pos.odoo.OfferState
import com.bohemian.odoo.pos.odoo.Offers
import com.bohemian.odoo.pos.odoo.PricedOffer
import com.bohemian.odoo.pos.odoo.RewardPart
import com.bohemian.odoo.pos.odoo.SalesService
import com.bohemian.odoo.pos.odoo.LiveArea
import com.bohemian.odoo.pos.odoo.LiveStamps
import com.bohemian.odoo.pos.odoo.LiveSync
import com.bohemian.odoo.pos.odoo.PosCatalog
import com.bohemian.odoo.pos.odoo.PosLoad
import com.bohemian.odoo.pos.odoo.ProductGrid
import com.bohemian.odoo.pos.odoo.Customer
import com.bohemian.odoo.pos.odoo.CustomerService
import com.bohemian.odoo.pos.odoo.ZzCashBalance
import com.bohemian.odoo.pos.odoo.ZzCashProgram
import com.bohemian.odoo.pos.odoo.ZzCashReward
import com.bohemian.odoo.pos.odoo.ZzCashRedemption
import com.bohemian.odoo.pos.odoo.ZzCashRules
import com.bohemian.odoo.pos.odoo.LoyaltyPoints
import com.bohemian.odoo.pos.odoo.LoyaltyRule
import com.bohemian.odoo.pos.odoo.KnownNames
import com.bohemian.odoo.pos.odoo.LineNote
import com.bohemian.odoo.pos.odoo.MenuTree
import com.bohemian.odoo.pos.odoo.PosCategory
import com.bohemian.odoo.pos.odoo.PosNote
import com.bohemian.odoo.pos.odoo.FiscalPosition
import com.bohemian.odoo.pos.odoo.OdooException
import com.bohemian.odoo.pos.odoo.OdooLane
import com.bohemian.odoo.pos.odoo.OdooErrorKind
import com.bohemian.odoo.pos.odoo.OdooErrors
import com.bohemian.odoo.pos.odoo.KeyRotation
import com.bohemian.odoo.pos.odoo.OdooContractCheck
import com.bohemian.odoo.pos.odoo.PaidSale
import com.bohemian.odoo.pos.odoo.ParkedBill
import com.bohemian.odoo.pos.odoo.ParkedLine
import com.bohemian.odoo.pos.data.ParkedBillEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import com.bohemian.odoo.pos.odoo.PaymentLineData
import com.bohemian.odoo.pos.odoo.PaymentMethod
import com.bohemian.odoo.pos.odoo.PaymentRoles
import com.bohemian.odoo.pos.odoo.PricelistHold
import com.bohemian.odoo.pos.odoo.PosConfig
import com.bohemian.odoo.pos.odoo.Product
import com.bohemian.odoo.pos.odoo.SaleSnapshot
import com.bohemian.odoo.pos.payment.PaymentAttempt
import com.bohemian.odoo.pos.odoo.PartlyPaid
import com.bohemian.odoo.pos.payment.PaymentSplit
import com.bohemian.odoo.pos.payment.PaymentOutcome
import com.bohemian.odoo.pos.payment.VoidOutcome
import com.bohemian.odoo.pos.pine.PayMode
import com.bohemian.odoo.pos.pine.PrintResult
import com.bohemian.odoo.pos.receipt.TerminalSlip
import com.bohemian.odoo.pos.receipt.ReceiptFormatter
import com.bohemian.odoo.pos.receipt.BillText
import com.bohemian.odoo.pos.odoo.ClosingSummary
import com.bohemian.odoo.pos.odoo.CloseResult
import com.bohemian.odoo.pos.odoo.RefundResult
import com.bohemian.odoo.pos.receipt.ZReportCash
import com.bohemian.odoo.pos.receipt.ZReportData
import com.bohemian.odoo.pos.receipt.ZReportFormatter
import com.bohemian.odoo.pos.receipt.ZReportMethod
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/** The close-session dialog. Figures come from Odoo; the cashier types only counted cash and notes. */
data class ClosingUi(
    val summary: ClosingSummary? = null,
    val counted: String = "",
    val notes: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val closed: Boolean = false,
    /** null until the Z-report print was tried. */
    val printed: Boolean? = null,
)

/** The void dialog: today's card sales, then a confirm step for the chosen one. */
data class VoidUi(
    val sales: List<PaymentAttempt> = emptyList(),
    val confirm: PaymentAttempt? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

data class SetupState(
    val apiKeyEntered: Boolean = false,
    val configs: List<PosConfig> = emptyList(),
    val selectedConfig: PosConfig? = null,
    val methods: List<PaymentMethod> = emptyList(),
    /** Odoo methods for card, UPI and cash on this terminal (0 = off); picked by `PaymentRoles.defaults`. */
    val cardMethodId: Int = 0,
    val upiMethodId: Int = 0,
    val cashMethodId: Int = 0,
    /** The methods saved on this terminal for this POS are not all offered any more, so they were picked again. */
    val methodsRepicked: Boolean = false,
    val printerWidth: Int = 24,
    val useFake: Boolean = false,
    val kioskMode: Boolean = false,
    /** Company logo at the top of the bill (Pine image printing still to be proven on the E700). */
    val printLogo: Boolean = false,
    /** Local crash reports, newest first, full redacted text (at most CrashLog.KEEP). */
    val crashReports: List<String> = emptyList(),
    /** This till's Odoo traffic and 429s ([OdooStats]), so a store visit shows whether it is within budget. */
    val odooRate: String? = null,
    /** Whose key this till uses and until when (F4). */
    val keyInfo: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    /** Result of "Change PIN" on the Setup screen. */
    val pinMessage: String? = null,
    val pinChanged: Boolean = false,
    /** The sales day shown on SALES: start and end hour (24 = midnight). */
    val salesDayStart: Int = 6,
    val salesDayEnd: Int = 24,
)

/** The payment tiles of the split screen. */
enum class SplitKind { CASH, CARD, UPI }

/** One Card / UPI part of a split bill, as the cashier sees it. */
sealed interface PartState {
    data object Charging : PartState
    data class Paid(val attempt: PaymentAttempt) : PartState
    data class Failed(val reason: String) : PartState
    /** The terminal did not confirm it: the yellow bar resolves it (Check status / supervisor). */
    data class Unconfirmed(val attempt: PaymentAttempt) : PartState
}

/** Odoo's payment screen for one bill: the lines ([PaymentSplit] rules) and what the terminal did with each. */
data class SplitUi(
    val orderUuid: String,
    val split: PaymentSplit,
    /** Digits typed on the numpad for the selected line. */
    val entry: String = "",
    val parts: Map<String, PartState> = emptyMap(),
    val message: String? = null,
    val busy: Boolean = false,
) {
    /** Charged, being charged, or waiting for the terminal: its amount can no longer change. */
    fun isLocked(uuid: String) = parts[uuid].let { it is PartState.Paid || it is PartState.Charging || it is PartState.Unconfirmed }

    val terminalLinesPaid: Boolean get() = split.lines.filter { it.mode != null }.all { parts[it.uuid] is PartState.Paid }
    val canValidate: Boolean get() = !busy && split.isComplete && terminalLinesPaid
}

/** The SALES screen: the store's figures for its sales day, refreshed every minute while open. */
data class SalesUi(
    val storeName: String,
    val sales: com.bohemian.odoo.pos.odoo.StoreSales? = null,
    /** When [sales] came from Odoo. */
    val updatedAt: Long? = null,
    val loading: Boolean = true,
    /** The last refresh failed; [sales] are still the figures of [updatedAt]. */
    val error: String? = null,
)

/** The PIN prompt in front of Setup: digits typed so far (shown as dots) and the last message. */
data class PinUi(val entered: String = "", val message: String? = null)

sealed interface PayUi {
    data object Idle : PayUi
    data class Working(val mode: String, val message: String) : PayUi
    data class Done(
        val outcome: PaymentOutcome?,          // null for cash
        val paidByCash: Boolean = false,
        /** "UPI ₹300.00 · Card ₹150.00 · Cash ₹50.00" for a split bill; null otherwise. */
        val splitSummary: String? = null,
        val cashChange: Paise? = null,
        /** Null until the bill has been tried; [printing] while it is being read from Odoo and printed. */
        val printed: Boolean? = null,
        val printing: Boolean = false,
        /** The order this result is for, when it may not be the bill on screen (resolved from the bar). */
        val orderUuid: String? = null,
    ) : PayUi {
        val isPaid: Boolean get() = paidByCash || splitSummary != null || outcome is PaymentOutcome.Paid
    }
    data class Error(val message: String) : PayUi
}

data class SellState(
    val config: PosConfig? = null,
    val products: List<Product> = emptyList(),
    val query: String = "",
    val orderUuid: String = UUID.randomUUID().toString(),
    val lines: List<CartLine> = emptyList(),
    val totals: CartTotals? = null,
    val pricing: Boolean = false,
    /** What is typed in the customer dialog's "Name or mobile" field: a mobile (digits) or part of a name. */
    val phone: String = "",
    val customerName: String = "",
    /** Attached customer. Set only by [PosViewModel.pickCustomer] (a match, a lookup or a saved new customer). */
    val customer: Customer? = null,
    /** Customers matching [phone] (last digits of the mobile, or the name), A→Z, the pages loaded so far. */
    val customerMatches: List<Customer> = emptyList(),
    /** Odoo has more matches than [customerMatches]: the next page loads when the list is scrolled to its end. */
    val customerHasMore: Boolean = false,
    /** The typed mobile is valid but no customer has it: the dialog offers to save a new one. */
    val customerNotFound: Boolean = false,
    /** Confirmation or error in the customer dialog ("Saved: …", "Enter the customer's name"). */
    val customerNote: String? = null,
    /** [customerNote] confirms a save rather than reporting a problem. */
    val customerNoteOk: Boolean = false,
    val customerBusy: Boolean = false,
    /** ZZ Cash balance of [customer], read live from Odoo. Null while loading or if none. */
    val zzCash: ZzCashBalance? = null,
    /** Where the balance lookup of [customer] stands, so "loading", "no card" and "failed" look different. */
    val zzLookup: ZzLookup = ZzLookup.NONE,
    /** Rewards the card can pay for, from loyalty.reward. Empty until the balance loads. */
    val zzRewards: List<ZzCashReward> = emptyList(),
    /** The ZZ Cash reward applied to this sale, priced by Odoo once the cart is priced. */
    val zzRedemption: ZzCashRedemption? = null,
    /** Menu tabs (pos.category) and note tags (pos.note) from Odoo. */
    val categories: List<PosCategory> = emptyList(),
    val notes: List<PosNote> = emptyList(),
    /** Selected tab; null = All (the whole catalogue), which is where the till opens. */
    val selectedCateg: Int? = null,
    /** The tiles to draw for [selectedCateg] / [query], from [PosViewModel]'s [ProductGrid]. */
    val filtered: List<Product> = emptyList(),
    /** Selected cart line (the keypad acts on it). */
    val selectedLine: String? = null,
    /** Digits typed on the keypad for the selected line. */
    val keypad: String = "",
    /** ZZ Cash earning rules (loyalty.rule of the config's program). Null until loaded. */
    val zzRules: List<LoyaltyRule>? = null,
    /**
     * The fiscal position the cart was priced with. It must be the one sent to Odoo,
     * otherwise our totals and Odoo's disagree on an inter-state B2B sale.
     */
    val fiscalPosition: FiscalPosition? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val pay: PayUi = PayUi.Idle,
    /**
     * Set when the last refresh failed (Odoo unreachable, or it sent fewer products than it counts) and
     * the till shows the last complete catalogue, loaded or saved at this time (Phase 5).
     */
    val offlineCatalogSavedAt: Long? = null,
    /** Why the refresh failed, for the amber bar. */
    val catalogStaleReason: String? = null,
    /** Keypad key height in dp (0 = folded away), resized by the cashier and kept per terminal. */
    val keypadKeyDp: Int = 36,
    /** Split bill: card / UPI parts of this bill already paid. While positive the cart is locked. */
    val partPaid: Paise = Paise.ZERO,
    /** Live sync changed this cart's prices ("Prices updated from Odoo at 1:21 PM"); not an error. */
    val notice: String? = null,
    /** Promotions, codes and free items on this bill (ZZ Cash aside), priced by Odoo. */
    val offerLines: List<PricedOffer> = emptyList(),
    /** Points each offer program gives on this bill, booked after sync. */
    val offerCoupons: List<CouponChange> = emptyList(),
    /** Offers this bill could still take, for the Reward dialog. */
    val offerClaimable: List<Claimable> = emptyList(),
    /** Codes entered, rewards chosen or taken off, coupons scanned on this bill. */
    val offers: OfferState = OfferState(),
    /** How the programs on this bill book their points, as they were when the bill was priced. */
    val offerPrograms: List<OfferProgramInfo> = emptyList(),
    /** Odoo's offers could not be loaded for this session: promotions and codes are off until they are. */
    val offersUnavailable: Boolean = false,
    /** A tender is set to a method the till must not book on (`PaymentRoles.unbookableWarning`); sales go on. */
    val methodWarning: String? = null,
    /** "Bill 3": this bill's number in the till's queue of open bills (the chip label without a customer). */
    val billNumber: Int = 1,
    /** F4: the till's Odoo key expires soon and could not renew itself. */
    val keyWarning: String? = null,
) {
    // Derived values are computed once per state, not on every read while the screen is drawn.

    /** ZZ Cash this cart earns, as the web POS shows it ("34.6 +1.2 = 35.8"). */
    val zzEarned: Paise by lazy { zzRules?.let { LoyaltyPoints.earned(it, lines) } ?: Paise.ZERO }

    /** The tab tree; the same instance while the categories are the same, so the tab row is not redrawn. */
    val menu: MenuTree by lazy { MenuTree.of(categories) }

    /** Units of each product in the cart, for the badge on its tile. */
    val qtyInCart: Map<Int, Int> by lazy { lines.groupBy { it.product.id }.mapValues { (_, l) -> l.sumOf { it.qty } } }

    /** A ZZ Cash reward can be applied to this bill now (the web POS lights up "Reward" then). */
    val rewardFits: Boolean by lazy {
        val bal = zzCash?.takeIf { it.partnerId == customer?.id }
        val total = totals?.totalIncl
        offerClaimable.isNotEmpty() ||
            (zzRedemption == null && bal != null && total != null && ZzCashRules.eligible(bal, zzEarned, zzRewards, total).isNotEmpty())
    }
}

/** The ZZ Cash card lookup of the attached customer. */
enum class ZzLookup { NONE, LOADING, NO_CARD, FAILED, LOADED }

class PosViewModel(private val c: AppContainer) : ViewModel() {

    private val _configured = MutableStateFlow(c.settings.isConfigured)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    private val _setup = MutableStateFlow(
        SetupState(
            apiKeyEntered = !c.settings.odooApiKey.isNullOrBlank(),
            printerWidth = c.settings.printerWidth,
            useFake = c.settings.useFakeGateway,
            kioskMode = c.settings.kioskMode,
            printLogo = c.settings.printLogo,
            salesDayStart = c.settings.salesDayStartHour,
            salesDayEnd = c.settings.salesDayEndHour,
        ).withCrashes()
    )

    /** Setup: the hours of the sales day on SALES (start 0–23, end after it, up to 24 = midnight). */
    fun setSalesDay(start: Int, end: Int) {
        if (start !in 0..23 || end !in start + 1..24) return
        c.settings.salesDayStartHour = start
        c.settings.salesDayEndHour = end
        _setup.update { it.copy(salesDayStart = start, salesDayEnd = end) }
    }

    private fun SetupState.withCrashes(): SetupState {
        val reports = c.crashLog.reports()
        return copy(crashReports = reports.mapNotNull { f -> runCatching { f.readText() }.getOrNull() }, odooRate = odooRateText(),
            keyInfo = c.settings.apiKeyExpiresOn?.let { "This till's own key (\"${c.keyName()}\"), valid until $it. It renews itself ${KeyRotation.ROTATE_DAYS} days before." }
                ?: "Key entered by hand: the till does not know when it expires and cannot renew it. Enroll to give the till its own key.")
    }

    /** "Odoo calls: 1.4 a minute (sale 3, cashier 9, background 2 in the last 10 min) · 429s today: 0". */
    private fun odooRateText(): String {
        val r = c.odoo.stats.snapshot()
        val last = r.lastThrottledAt?.let { " · last at " + SimpleDateFormat("h:mm a", Locale("en", "IN")).format(Date(it)) } ?: ""
        return "Odoo calls: %.1f a minute (sale %d, cashier %d, background %d in the last 10 min) · Odoo busy (429) today: %d%s".format(
            r.perMinute, r.byLane[OdooLane.SALE] ?: 0, r.byLane[OdooLane.TAP] ?: 0, r.byLane[OdooLane.BACKGROUND] ?: 0, r.throttledToday, last)
    }

    /** Takes effect at once; MainActivity applies lock task on resume, Setup applies it on toggle. */
    fun setKioskMode(v: Boolean) {
        c.settings.kioskMode = v
        _setup.update { it.copy(kioskMode = v) }
    }

    fun setPrintLogo(v: Boolean) {
        c.settings.printLogo = v
        _setup.update { it.copy(printLogo = v) }
    }

    fun clearCrashReports() {
        c.crashLog.clear()
        _setup.update { it.withCrashes() }
    }
    val setup: StateFlow<SetupState> = _setup.asStateFlow()

    private val _sell = MutableStateFlow(SellState(keypadKeyDp = c.settings.keypadKeyDp))
    val sell: StateFlow<SellState> = _sell.asStateFlow()

    /**
     * Every ViewModel coroutine starts through [launchSafe]. A bug in one becomes a local crash
     * report and a message on screen, never "ZenZebra has stopped". The handler changes no money
     * state: the payment journal, snapshot and outbox stay as the failed step left them, and the
     * Reconciler / unresolved bar take it from there.
     */
    private val safety = CoroutineExceptionHandler { _, e ->
        PosLog.e("UI_ACTION_FAILED", e)
        runCatching { c.crashLog.write("ui", e) }
        val msg = "Something went wrong (${e::class.simpleName}). Nothing was charged twice; check the payment bar."
        _sell.update { it.copy(pay = if (it.pay is PayUi.Working) PayUi.Error(msg) else it.pay, error = msg, loading = false, pricing = false) }
    }

    private fun launchSafe(
        context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> Unit,
    ) = viewModelScope.launch(safety + context, block = block)

    val outboxCount: StateFlow<Int> = c.db.outbox().observeCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Outbox orders by how they failed (refused by Odoo, waiting for a session, key rejected), for the bars. */
    val outboxProblems: StateFlow<List<com.bohemian.odoo.pos.data.OutboxProblem>> =
        c.db.outbox().observeProblems().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Odoo refused this till's API key: nothing reaches Odoo until a supervisor fixes it in Setup. */
    val odooKeyRejected: StateFlow<Boolean> = c.odoo.keyRejected

    private val _contractProblem = MutableStateFlow<String?>(null)

    /** F2: Odoo was upgraded and lost a field the till needs. Payments are refused until it is fixed. */
    val contractProblem: StateFlow<String?> = _contractProblem.asStateFlow()

    private var contractCheckedAt = 0L

    /**
     * Odoo's version, once a day and after the first full load: when it changed since the last pass,
     * every field the till sends or relies on is checked (`OdooContractCheck`). Fail closed: if one is
     * gone, no money is taken that Odoo could not record; browsing goes on.
     */
    private fun checkContractIfDue() {
        if (System.currentTimeMillis() - contractCheckedAt < CONTRACT_EVERY_MS) return
        contractCheckedAt = System.currentTimeMillis()
        launchSafe {
            when (val r = withContext(OdooLane.BACKGROUND) { c.contract.run(c.settings.odooVerifiedVersion) }) {
                is OdooContractCheck.Result.Ok -> {
                    if (c.settings.odooVerifiedVersion != r.version) PosLog.i("ODOO_CONTRACT_OK", "version" to r.version)
                    c.settings.odooVerifiedVersion = r.version
                    _contractProblem.value = null
                }
                is OdooContractCheck.Result.Broken -> {
                    PosLog.w("ODOO_CONTRACT_BROKEN", "version" to r.version, "missing" to r.missing.joinToString(","))
                    _contractProblem.value = "Odoo was upgraded (${r.version}) and no longer has " +
                        r.missing.take(3).joinToString() + (if (r.missing.size > 3) " and ${r.missing.size - 3} more" else "") +
                        ". Payments are paused so no money is taken that Odoo could not record. Browsing still works; call support."
                }
                OdooContractCheck.Result.Unknown -> contractCheckedAt = 0L // try again on the next pass
            }
        }
    }

    /** "Retry now" on the refused-orders bar: after a supervisor fixed the cause in Odoo. */
    fun retryRefusedOrders() = launchSafe {
        c.orderSync.retryNow(OdooErrorKind.REJECTED)
        withContext(OdooLane.SALE) { c.orderSync.flushDue() }
    }
    val unresolved: StateFlow<List<com.bohemian.odoo.pos.data.PaymentAttemptEntity>> =
        c.db.attempts().observeUnresolved().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val envLabel = BuildConfig.ENV_LABEL + if (BuildConfig.PINE_SDK_PRESENT) "" else " · NO PINE SDK"
    val isDebug = BuildConfig.DEBUG

    // ------------------------------------------------------------------ setup

    fun saveApiKey(key: String) = launchSetup {
        c.settings.odooApiKey = key.trim()
        // A key entered by hand: Odoo did not tell the till when it expires, so it is not renewed here.
        c.settings.apiKeyExpiresOn = null
        c.settings.pendingApiKey = null
        val configs = c.catalog.configs() // also validates the key
        _setup.update { it.copy(apiKeyEntered = true, configs = configs).withCrashes() }
    }

    /**
     * F4: the pasted key is the bot user's enrollment key; the till makes its own key from it (named for
     * this till, renewed by itself before it expires) and keeps only that one.
     */
    fun enrollKey(enrollmentKey: String) = launchSetup {
        c.keys.enroll(enrollmentKey.trim(), c.keyName())
        val configs = c.catalog.configs()
        _setup.update { it.copy(apiKeyEntered = true, configs = configs).withCrashes() }
    }

    fun loadConfigs() = launchSetup { _setup.update { it.copy(configs = c.catalog.configs()) } }

    fun selectConfig(cfg: PosConfig) = launchSetup {
        val methods = c.catalog.paymentMethods(cfg.paymentMethodIds)
        // The same store as before keeps the methods chosen for it, but only while Setup still offers each
        // one: a method it does not show (an Odoo-driven Pine link saved by an older build) would otherwise
        // be saved again unseen. Another store, or a stale choice, starts from Odoo's.
        val previous = c.paymentRoles().takeIf { cfg.id == c.settings.posConfigId && it.any }
        val saved = previous?.takeIf { it.offeredIn(methods) }
        val roles = saved ?: PaymentRoles.defaults(methods)
        _setup.update {
            it.copy(selectedConfig = cfg, methods = methods, cardMethodId = roles.card, upiMethodId = roles.upi, cashMethodId = roles.cash,
                methodsRepicked = previous != null && saved == null)
        }
    }

    /** Tap a method chip: choose it, or tap the chosen one again to take that tender off this till. */
    fun setCardMethod(id: Int) = _setup.update { it.copy(cardMethodId = if (it.cardMethodId == id) 0 else id) }
    fun setUpiMethod(id: Int) = _setup.update { it.copy(upiMethodId = if (it.upiMethodId == id) 0 else id) }
    fun setCashMethod(id: Int) = _setup.update { it.copy(cashMethodId = if (it.cashMethodId == id) 0 else id) }
    fun setPrinterWidth(w: Int) = _setup.update { it.copy(printerWidth = w) }
    fun setUseFake(v: Boolean) = _setup.update { it.copy(useFake = v) }

    fun finishSetup() {
        if (_configured.value) return // a second tap on Start selling: the first already started the load
        val s = _setup.value
        val cfg = s.selectedConfig ?: return _setup.update { it.copy(error = "Choose a POS") }
        if (s.cardMethodId == 0 && s.upiMethodId == 0 && s.cashMethodId == 0) {
            return _setup.update { it.copy(error = "Choose at least one Odoo payment method: card, UPI or cash") }
        }
        c.settings.posConfigId = cfg.id
        c.settings.posConfigName = cfg.name
        c.settings.cardPaymentMethodId = s.cardMethodId
        c.settings.upiPaymentMethodId = s.upiMethodId
        // Read by builds before 0.5.0, should this terminal ever go back to one.
        c.settings.terminalPaymentMethodId = if (s.cardMethodId != 0) s.cardMethodId else s.upiMethodId
        c.settings.cashPaymentMethodId = s.cashMethodId
        c.settings.printerWidth = s.printerWidth
        c.settings.useFakeGateway = BuildConfig.DEBUG && s.useFake
        _configured.value = true
        loadCatalog()
        startLiveSync()
    }

    fun reopenSetup() { _configured.value = false; _setup.update { it.copy(odooRate = odooRateText()) }; loadConfigs() }

    // ------------------------------------------------------------------ Setup PIN

    private val setupPin = SetupPin(c.settings)

    private val _pinPrompt = MutableStateFlow<PinUi?>(null)
    val pinPrompt: StateFlow<PinUi?> = _pinPrompt.asStateFlow()

    /** SETUP tapped. A till already set up asks for the supervisor PIN first; a new one opens Setup. */
    fun requestSetup() {
        if (!_configured.value) reopenSetup() else _pinPrompt.value = PinUi()
    }

    fun pinDigit(d: Char) = _pinPrompt.update { it?.copy(entered = (it.entered + d).take(6), message = null) }
    fun pinBackspace() = _pinPrompt.update { it?.copy(entered = it.entered.dropLast(1), message = null) }
    fun dismissPin() { _pinPrompt.value = null }

    fun submitPin() {
        val entered = _pinPrompt.value?.entered ?: return
        when (val r = setupPin.check(entered)) {
            SetupPin.Check.Ok -> { _pinPrompt.value = null; reopenSetup() }
            is SetupPin.Check.Wrong -> _pinPrompt.update { it?.copy(entered = "", message = "Wrong PIN. ${r.triesLeft} tries left.") }
            is SetupPin.Check.Locked -> _pinPrompt.update { it?.copy(entered = "", message = "Too many wrong PINs. Try again in ${r.secondsLeft} s.") }
        }
    }

    fun changePin(current: String, new: String, confirm: String) {
        val problem = setupPin.change(current, new, confirm)
        _setup.update { it.copy(pinMessage = problem ?: "PIN changed. Use the new PIN to open Setup.", pinChanged = problem == null) }
    }

    /** "0.3.0 (3)": shown on Setup and the top bar, so the installed build is never a guess. */
    val versionLabel = com.bohemian.odoo.pos.BuildInfo.version

    private fun launchSetup(block: suspend () -> Unit) = launchSafe {
        _setup.update { it.copy(busy = true, error = null) }
        try { block() } catch (e: Exception) { _setup.update { it.copy(error = friendly(e)) } }
        _setup.update { it.copy(busy = false) }
    }

    // ------------------------------------------------------------------ catalogue & cart

    private var catalogLoadedAt = 0L

    /** Full loads that failed in a row, and when live sync may try the next one. */
    private var loadFailures = 0
    private var nextReloadAt = 0L

    /** 1, 2, 4, 8, 15 minutes after the 1st … 5th failure in a row, ±25%, so 20 tills do not retry together. */
    private fun reloadBackoffMs(failures: Int): Long =
        ((60_000L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(15 * 60_000L) * (0.75 + Math.random() * 0.5)).toLong()

    /**
     * On resume: a full load only when Odoo has not been heard from for 30 minutes (no full load and no
     * live-sync pass). Live sync keeps a running till current, so a resume costs no Odoo calls.
     */
    fun refreshIfStale() {
        if (_configured.value && System.currentTimeMillis() - maxOf(catalogLoadedAt, lastLiveOkAt) > 30 * 60_000L && !_sell.value.loading
            && _sell.value.pay == PayUi.Idle) loadCatalog()
    }

    /** The reload icon and Retry: ignored while a load runs, so a double tap is one load, not two. */
    fun reloadFromOdoo() {
        if (_sell.value.loading) return
        loadCatalog()
    }

    val images get() = c.productImages

    /** Built once per catalogue load; [SellState.filtered] is read from it. */
    private var grid = ProductGrid.EMPTY

    /** When the products on screen were last loaded complete from Odoo. */
    private var completeCatalogAt = 0L

    /** Recompute the tiles after the catalogue, the tab or the search changed. */
    private fun SellState.regrid(): SellState {
        val shown = grid.show(selectedCateg, query)
        if (shown.size != filtered.size) {
            PosLog.d("GRID_FILTER", "tab" to (selectedCateg ?: "all"), "query_len" to query.trim().length, "shown" to shown.size)
        }
        return copy(filtered = shown)
    }

    /** Builds the search index off the screen thread (2,400+ names) and logs what is shown. */
    private suspend fun useCatalog(products: List<Product>, categories: List<PosCategory>) {
        grid = withContext(Dispatchers.Default) { ProductGrid(products, MenuTree.of(categories)) }
        PosLog.i("CATALOG_SHOWN", "products" to products.size, "tabs" to categories.size,
            "unknown_categ_refs" to grid.unknownCategoryRefs())
    }

    /** account.tax id → name, for the tax line under each item on the bill (read with the catalogue). */
    private var taxNames: Map<Int, String> = emptyMap()

    /** pos.payment.method id → name of this till's methods (read with the catalogue), for "Paid via" on the bill. */
    private var paymentMethodNames: Map<Int, String> = emptyMap()

    /**
     * Start of the day: show the last complete catalogue at once (read off the screen thread) while
     * Odoo is asked. Browsing only: without a fresh config nothing can be sold until the refresh
     * answers, so a session closed in the meantime can never take a payment.
     */
    private suspend fun showSavedCatalogFirst() {
        if (_sell.value.products.isNotEmpty()) return
        val cached = withContext(Dispatchers.IO) { c.catalogCache.load() }?.takeIf { it.config.id == c.settings.posConfigId } ?: return
        useCatalog(cached.products, cached.categories)
        _sell.update { if (it.products.isNotEmpty()) it else it.copy(products = cached.products, categories = cached.categories, notes = cached.notes).regrid() }
    }

    fun loadCatalog() = launchSafe {
        catalogLoadedAt = System.currentTimeMillis() // also stops refreshIfStale from loading twice
        val generation = ++catalogGeneration // a live-sync pass still running for the old catalogue must not apply
        _sell.update { it.copy(loading = true, error = null) }
        showSavedCatalogFirst()
        // Just after the E700 boots (every till of a chain is often switched on at once): wait 0–20 s
        // first, with the saved catalogue on screen, so 20 tills do not load from Odoo in the same second.
        if (generation == 1L && android.os.SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) delay(Random.nextLong(BOOT_SPREAD_MS))
        try {
            val cfg = c.catalog.config(c.settings.posConfigId) ?: run {
                _sell.update { it.copy(config = null, loading = false, error =
                    "POS \"${c.settings.posConfigName}\" is archived, deleted or not open to this Odoo user. Open Setup and choose a POS.") }
                return@launchSafe
            }
            // No config in the state = no selling (prepareSnapshot), until Setup fixes it.
            cfg.paymentMethodProblem(c.paymentRoles())?.let { problem ->
                _sell.update { it.copy(config = null, loading = false, error = problem) }
                return@launchSafe
            }
            // 0.8.0: with a session open, Odoo's own POS loader gives everything in one call (as the web
            // POS loads). Anything but a network error, a rejected key or a busy Odoo falls back to the
            // separate reads, which then serve for [LOADER_OFF_MS].
            if (cfg.sessionOpen && cfg.currentSessionId != null && System.currentTimeMillis() >= loaderOffUntil) {
                val loaded = try {
                    loadWithLoader(cfg.currentSessionId, generation)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is OdooException && passesThroughLoader(e)) throw e
                    loaderRefused(e); false
                }
                if (loaded) return@launchSafe
            }
            posCatalog = null
            // Two steps, so the till sells as soon as it can (0.7.1): what selling needs first (prices,
            // taxes, offers, pricelist, methods), then the menu tabs, notes, bill text and ZZ Cash rules
            // in the background. At about one Odoo call a second, waiting for everything took ~35 s.
            val fposIds = (cfg.fiscalPositionIds + listOfNotNull(cfg.defaultFiscalPositionId)).distinct()
            val a = coroutineScope {
                // Live sync's starting point is read before the data it guards, so a change made while it
                // loads is seen on the next pass instead of being missed.
                val stampsJob = async { runCatching { c.liveSync.read(LiveSync.SELLING) }.onFailure { PosLog.w("LIVE_STAMPS_FAILED", "err" to it.message) }.getOrNull() }
                // Mapping and sorting 2,400+ products runs off the screen thread.
                val productsJob = async(Dispatchers.Default) { stampsJob.await(); c.catalog.products(cfg) }
                val fposJob = async { c.fiscalPositions.load(fposIds) }
                // The store's offers as Odoo's POS loader gives them to this session; ZZ Cash is one of them
                // where Odoo gives it to this store. A bill is priced with them, so they come first.
                val offersJob = async {
                    odooTz = runCatching { c.sales.timeZone() }.onFailure { PosLog.w("ODOO_TZ_FAILED", "err" to it.message) }.getOrNull() ?: odooTz
                    loadOffers(cfg)
                }
                // Products the store's pricelist prices differently from the list price: not sold here.
                // Like the products, a failed read fails the load (never sell without knowing).
                val pricelistJob = async { c.pricelists.hold(cfg, SalesService.utc(System.currentTimeMillis())) }
                // The methods this terminal books on, for the warning when one is Odoo-driven; never blocks.
                val methodsJob = async {
                    runCatching { c.catalog.paymentMethods(c.paymentRoles().ids.toList()) }
                        .onFailure { PosLog.w("PAYMENT_METHODS_READ_FAILED", "err" to it.message) }.getOrNull()
                }
                val products = productsJob.await()
                val primeJob = async { c.fiscalPositions.primeTaxFiscalPositions(products.flatMap { it.taxIds }.distinct()) }
                val positions = fposJob.await()
                primeJob.await()
                SellNeeds(products, positions, stampsJob.await(), offersJob.await(), pricelistJob.await(), methodsJob.await())
            }
            if (generation != catalogGeneration) return@launchSafe // a newer load owns the screen
            val products = a.products
            liveStamps = a.stamps
            a.stamps?.let { st -> val at = System.currentTimeMillis(); st.byModel.keys.forEach { stampsReadAt[it] = at } }
            usePricelistHold(a.pricelist)
            a.methods?.let { m -> paymentMethodNames = m.associate { it.id to it.name } }
            useOffers(a.offers)
            fiscalPositionsById = a.positions
            // ZZ Cash comes with the offers; only without them is it looked up (in the background step).
            zzProgram = if (a.offers != null) zzProgramOf(a.offers) else if (offersLoaded) zzProgramOf(offerPrograms) else zzProgram
            useCatalog(products, _sell.value.categories)
            // Saved prices stay while Odoo's taxes are unchanged (a new price is a new key anyway); a tax
            // change empties them. Unknown stamps keep them, since Odoo refuses a wrong total later anyway.
            a.stamps?.taxStamp?.let { stamp -> c.pricing.useTaxStamp(stamp); c.settings.taxStampConfirmedAt = System.currentTimeMillis() }
            rewardsCache = null
            completeCatalogAt = System.currentTimeMillis()
            checkContractIfDue()
            loadFailures = 0
            nextReloadAt = 0L
            _sell.update {
                it.copy(
                    config = cfg, products = products, loading = false, offlineCatalogSavedAt = null, catalogStaleReason = null,
                    fiscalPosition = fiscalPositionsById[cfg.defaultFiscalPositionId],
                    methodWarning = if (a.methods != null) c.paymentRoles().unbookableWarning(a.methods) else it.methodWarning,
                    keyWarning = c.keys.daysLeft()?.takeIf { d -> d <= KeyRotation.WARN_DAYS }?.let { d ->
                        "This till's Odoo key expires in ${d.coerceAtLeast(0)} day(s) and could not renew itself. A supervisor should enroll it again in SETUP."
                    },
                ).regrid()
            }
            _sell.value.methodWarning?.let { PosLog.w("PAYMENT_METHOD_UNBOOKABLE", "roles" to c.paymentRoles().ids) }
            catalogLoadedAt = System.currentTimeMillis()
            // New prices, taxes or offers: an open cart is priced again (a bill with a paid part keeps its own).
            if (_sell.value.lines.isNotEmpty() && _sell.value.pay == PayUi.Idle) reprice()
            loadTheRest(cfg, generation)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = when {
                e is OdooException.Network -> "No connection to Odoo"
                e is OdooException.Server && e.status == 429 -> "Odoo is busy (too many requests)"
                e is OdooException.Server && e.status >= 500 -> "Odoo is not answering (HTTP ${e.status})"
                e is CatalogIncomplete -> "Odoo sent ${e.got} of ${e.expected} products"
                else -> null
            }
            if (reason == null) { _sell.update { it.copy(loading = false, error = friendly(e)) }; return@launchSafe }
            PosLog.w("CATALOG_REFRESH_FAILED", "reason" to e::class.simpleName, "err" to e.message)
            loadFailures++
            nextReloadAt = System.currentTimeMillis() + reloadBackoffMs(loadFailures)
            val posId = c.settings.posConfigId
            val onScreen = _sell.value.products.isNotEmpty() && completeCatalogAt > 0 && _sell.value.config?.id == posId
            // Odoo busy (429) is not offline: the till keeps selling from what it has, and live sync
            // carries on after the cool-down. Marking it offline made live sync reload all every minute.
            if (onScreen && e is OdooException.Server && e.status == 429) {
                _sell.update { it.copy(loading = false, notice = "Odoo is busy, so the menu was not refreshed. The till keeps the one it has and catches up by itself.") }
                return@launchSafe
            }
            // Phase 5: keep the last complete catalogue so the till is usable for browsing; selling
            // still needs Odoo pricing (I2), which fails with a friendly error until it is back.
            // Only the catalogue of the POS this terminal is set to: never another POS's session.
            val cached = if (onScreen) null else withContext(Dispatchers.IO) { c.catalogCache.load() }?.takeIf { it.config.id == posId }
            if (cached != null) useCatalog(cached.products, cached.categories)
            _sell.update {
                when {
                    onScreen -> it.copy(loading = false, offlineCatalogSavedAt = completeCatalogAt, catalogStaleReason = reason, error = null)
                    cached == null -> it.copy(loading = false, error = friendly(e))
                    else -> it.copy(config = cached.config, products = cached.products, loading = false,
                        categories = cached.categories, notes = cached.notes,
                        selectedCateg = it.selectedCateg?.takeIf { id -> cached.categories.any { c -> c.id == id } },
                        offlineCatalogSavedAt = cached.savedAt, catalogStaleReason = reason, error = null).regrid()
                }
            }
        }
    }

    // ------------------------------------------------------------------ Odoo's POS loader (0.8.0)

    /** Odoo's POS loader as the till holds it; null on the separate-reads path (no open session). */
    private var posCatalog: PosCatalog? = null

    /** Odoo's clock less the overlap at the last loader answer: the next incremental asks from here. */
    private var loaderSince: String? = null

    /** When the sellable product ids were last checked (products archived or taken out of the POS). */
    private var sellableCheckedAt = 0L

    /** When the bill template's stamp was last looked at, and what it was. */
    private var billTextCheckedAt = 0L
    private var billTextStamp: String? = null

    /** After Odoo's loader refused a call, the separate reads serve until then. */
    private var loaderOffUntil = 0L

    /** Errors the separate reads would meet as well: they stop the load, never switch the path. */
    private fun passesThroughLoader(e: OdooException) =
        e is OdooException.Network || e is OdooException.Auth || (e is OdooException.Server && e.status == 429)

    private fun loaderRefused(e: Exception) {
        PosLog.w("POS_LOADER_FAILED", "reason" to e::class.simpleName, "err" to e.message)
        loaderOffUntil = System.currentTimeMillis() + LOADER_OFF_MS
    }

    /**
     * A full catalogue load through Odoo's POS loader: config, products, taxes, fiscal positions, offers,
     * ZZ Cash rules, menu, notes and payment methods in one call (plus the pricelist hold when the store
     * has one). Returns false when the answer is not usable, so the separate reads take over.
     */
    private suspend fun loadWithLoader(sessionId: Int, generation: Long): Boolean {
        val reply = c.posLoad.full(sessionId)
        val cat = PosCatalog()
        cat.merge(reply.data, full = true)
        cat.missingVariants().takeIf { it.isNotEmpty() }?.let { cat.addVariants(c.posLoad.variants(it)) }
        val cfg = cat.config() ?: return false
        cfg.paymentMethodProblem(c.paymentRoles())?.let { problem ->
            _sell.update { it.copy(config = null, loading = false, error = problem) }
            return true
        }
        val products = withContext(Dispatchers.Default) { cat.products() }
        if (products.isEmpty()) return false // the separate reads check the catalogue against Odoo's count
        val positions = c.fiscalPositions.fromRecords(cat.fiscalPositionRecords())
        c.fiscalPositions.useTaxFiscalPositions(cat.taxesWithFiscalPosition())
        odooTz = runCatching { c.sales.timeZone() }.onFailure { PosLog.w("ODOO_TZ_FAILED", "err" to it.message) }.getOrNull() ?: odooTz
        val offers = runCatching { c.offers.fromReply(cat.loyalty()) }.onFailure { PosLog.w("OFFERS_LOAD_FAILED", "err" to it.message) }.getOrNull()
        // Like the products, a failed pricelist read fails the load (never sell without knowing).
        val hold = c.pricelists.hold(cfg, SalesService.utc(System.currentTimeMillis()))
        if (generation != catalogGeneration) return true // a newer load owns the screen
        posCatalog = cat
        loaderSince = PosLoad.nextSince(null, reply.serverTimeMs)
        sellableCheckedAt = System.currentTimeMillis()
        liveStamps = null // the separate-reads path reads its own stamps if it takes over
        stampsReadAt.clear()
        usePricelistHold(hold)
        val methods = cat.paymentMethods().filter { it.id in c.paymentRoles().ids }
        paymentMethodNames = methods.associate { it.id to it.name }
        if (offers != null) PosLog.i("OFFERS_LOADED", "session" to sessionId, "programs" to offers.size, "supported" to offers.count { it.supported })
        useOffers(offers)
        fiscalPositionsById = positions
        zzProgram = if (offers != null) zzProgramOf(offers) else if (offersLoaded) zzProgramOf(offerPrograms) else zzProgram
        val rules = zzProgram?.let { cat.loyaltyRules(it.id) }
        taxNames = cat.taxNames()
        val categories = cat.categories(cfg)
        val notes = cat.notes()
        useCatalog(products, categories)
        c.pricing.useTaxStamp(cat.taxFingerprint())
        c.settings.taxStampConfirmedAt = System.currentTimeMillis()
        rewardsCache = null
        completeCatalogAt = System.currentTimeMillis()
        checkContractIfDue()
        loadFailures = 0
        nextReloadAt = 0L
        _sell.update {
            it.copy(
                config = cfg, products = products, loading = false, offlineCatalogSavedAt = null, catalogStaleReason = null,
                zzRules = rules ?: it.zzRules, categories = categories, notes = notes,
                selectedCateg = it.selectedCateg?.takeIf { id -> categories.any { c -> c.id == id } },
                fiscalPosition = fiscalPositionsById[cfg.defaultFiscalPositionId],
                methodWarning = c.paymentRoles().unbookableWarning(methods),
                keyWarning = c.keys.daysLeft()?.takeIf { d -> d <= KeyRotation.WARN_DAYS }?.let { d ->
                    "This till's Odoo key expires in ${d.coerceAtLeast(0)} day(s) and could not renew itself. A supervisor should enroll it again in SETUP."
                },
            ).regrid()
        }
        _sell.value.methodWarning?.let { PosLog.w("PAYMENT_METHOD_UNBOOKABLE", "roles" to c.paymentRoles().ids) }
        catalogLoadedAt = System.currentTimeMillis()
        PosLog.i("POS_LOADER_FULL", "products" to products.size, "tabs" to categories.size, "offers" to (offers?.size ?: -1))
        if (_sell.value.lines.isNotEmpty() && _sell.value.pay == PayUi.Idle) reprice()
        loaderRest(cfg, generation)
        return true
    }

    /**
     * After a loader load, in the background: what the loader does not carry (the company's second address
     * line, the bill template), then the complete catalogue is saved on the till and the menu photos load.
     */
    private fun loaderRest(cfg: PosConfig, generation: Long) = launchSafe {
        withContext(OdooLane.BACKGROUND) {
            val address = cfg.companyId?.let { id ->
                runCatching { c.bills.companyAddress(id) }.onFailure { PosLog.w("BILL_ADDRESS_FAILED", "err" to it.message) }.getOrNull()
            }
            val stamp = runCatching { c.liveSync.read(listOf("ir.ui.view")).byModel["ir.ui.view"] }.getOrNull()
            val text = runCatching { c.billText.load() }.onFailure { PosLog.w("BILL_TEXT_FAILED", "err" to it.message) }.getOrNull()
            if (generation != catalogGeneration) return@withContext
            cfg.companyId?.let { id ->
                if (address != null) companyAddress = id to address
                else if (companyAddress?.first != id) companyAddress = null
            }
            text?.let { billText = it; billTextStamp = stamp }
            billTextCheckedAt = System.currentTimeMillis()
            val st = _sell.value
            runCatching { withContext(Dispatchers.IO) { c.catalogCache.save(cfg, st.products, categories = st.categories, notes = st.notes) } }
                .onFailure { PosLog.w("CATALOG_CACHE_SAVE_FAILED", "err" to it.message) }
        }
        val products = _sell.value.products
        c.productImages.prefetch(products.filter { it.categIds.isNotEmpty() }, products)
    }

    /**
     * One live-sync pass through the loader: only what changed in Odoo since the last pass, in one call
     * (it also carries the config and session). Products taken out of the POS are checked every 15
     * minutes and the bill template every hour, since the loader never sends those.
     */
    private suspend fun liveSyncByLoader(cat: PosCatalog, generation: Long) {
        val current = _sell.value.config ?: return reloadAll()
        val sessionId = current.currentSessionId ?: return reloadAll()
        val since = loaderSince ?: return reloadAll()
        // A new Odoo day brings today's combos (programs start by date, not by a change): load it all again.
        if (offersDay != null && offersDay != odooToday()) return reloadAll()
        val reply = try {
            c.posLoad.since(sessionId, since)
        } catch (e: OdooException) {
            if (passesThroughLoader(e)) throw e // nothing advanced: the next pass asks again
            loaderRefused(e)
            return reloadAll() // the separate reads take over, so a refused loader is never asked every pass
        }
        if (catalogGeneration != generation || posCatalog !== cat) return
        val changes = cat.merge(reply.data, full = false)
        val cfg = cat.config() ?: return reloadAll()
        if (cfg.paymentMethodProblem(c.paymentRoles()) != null) return reloadAll() // shows why selling stopped
        if (cfg != current) {
            // A session closed or opened here or elsewhere, or the POS's settings changed: load again
            // (the separate reads take over when there is no session).
            PosLog.i("LIVE_CONFIG_CHANGED", "session" to cfg.currentSessionId, "open" to cfg.sessionOpen)
            if (cfg.sessionOpen) c.orderSync.retryNow(OdooErrorKind.NO_SESSION)
            return reloadAll()
        }
        loaderSince = PosLoad.nextSince(since, reply.serverTimeMs)
        c.settings.taxStampConfirmedAt = System.currentTimeMillis() // Odoo just said what changed in its taxes
        var products = changes.products
        if (products) cat.missingVariants().takeIf { it.isNotEmpty() }?.let { cat.addVariants(c.posLoad.variants(it)) }
        val now = System.currentTimeMillis()
        if (inSalesDay() && now - sellableCheckedAt >= SELLABLE_EVERY_MS) {
            val removed = cat.keepOnlyProducts(c.catalog.productIds(cfg).toSet())
            sellableCheckedAt = now
            if (removed > 0) { products = true; PosLog.i("LIVE_PRODUCTS_REMOVED", "count" to removed) }
        }
        if (changes.any || products) PosLog.i("LIVE_CHANGES", "models" to changes.models.joinToString(","))
        var cartChanged = false
        if (products) cartChanged = applyProducts(withContext(Dispatchers.Default) { cat.products() }, cfg)
        if (changes.menu) {
            val categories = cat.categories(cfg)
            useCatalog(_sell.value.products, categories)
            _sell.update { st -> st.copy(categories = categories, selectedCateg = st.selectedCateg?.takeIf { id -> categories.any { it.id == id } }).regrid() }
        }
        if (changes.notes) _sell.update { it.copy(notes = cat.notes()) }
        if (changes.company) {
            cfg.companyId?.let { id -> runCatching { c.bills.companyAddress(id) }.getOrNull()?.let { companyAddress = id to it } }
            printerLogo = null // read again at the next bill
        }
        if (changes.taxes) {
            c.pricing.useTaxStamp(cat.taxFingerprint())
            fiscalPositionsById = c.fiscalPositions.fromRecords(cat.fiscalPositionRecords())
            c.fiscalPositions.useTaxFiscalPositions(cat.taxesWithFiscalPosition())
            taxNames = cat.taxNames()
            _sell.update { it.copy(fiscalPosition = it.fiscalPosition?.id?.let { id -> fiscalPositionsById[id] } ?: it.fiscalPosition) }
            cartChanged = true
        }
        if (changes.loyalty || !offersLoaded) {
            val offers = runCatching { c.offers.fromReply(cat.loyalty()) }.onFailure { PosLog.w("OFFERS_LOAD_FAILED", "err" to it.message) }.getOrNull()
            useOffers(offers)
            zzProgram = if (offersLoaded) zzProgramOf(offerPrograms) else zzProgram
            rewardsCache = null
            _sell.update { it.copy(zzRules = zzProgram?.let { p -> cat.loyaltyRules(p.id) } ?: it.zzRules) }
            _sell.value.customer?.let { refreshZzCash(it) }
            cartChanged = true // any offer may now apply, or stop applying, to the open cart
        }
        if (changes.methods) {
            val methods = cat.paymentMethods().filter { it.id in c.paymentRoles().ids }
            paymentMethodNames = methods.associate { it.id to it.name }
            _sell.update { it.copy(methodWarning = c.paymentRoles().unbookableWarning(methods)) }
        }
        // A new rule, or a new product in a category a rule covers.
        if (changes.pricelist || products) usePricelistHold(c.pricelists.hold(cfg, SalesService.utc(System.currentTimeMillis())))
        if (inSalesDay() && now - billTextCheckedAt >= BILL_TEXT_EVERY_MS) {
            billTextCheckedAt = now
            val stamp = c.liveSync.read(listOf("ir.ui.view")).byModel["ir.ui.view"]
            if (stamp != billTextStamp) c.billText.load()?.let { billText = it; billTextStamp = stamp }
        }
        if (cartChanged) repriceAfterLiveChange()
    }

    /**
     * New products from Odoo on screen and in the open cart (a bill with a paid part keeps what it was
     * charged with); the catalogue is saved on the till. Returns whether the open cart changed.
     */
    private suspend fun applyProducts(products: List<Product>, cfg: PosConfig): Boolean {
        useCatalog(products, _sell.value.categories)
        val byId = products.associateBy { it.id }
        val before = _sell.value.lines
        _sell.update { st ->
            val lines = if (st.partPaid.isPositive) st.lines else st.lines.map { l ->
                byId[l.product.id]?.takeIf { it != l.product }?.let { p -> l.copy(product = p, totalIncl = null, totalExcl = null) } ?: l
            }
            st.copy(products = products, lines = lines).regrid()
        }
        PosLog.i("LIVE_PRODUCTS", "total" to products.size)
        launchSafe(Dispatchers.IO) {
            runCatching { c.catalogCache.save(cfg, products, categories = _sell.value.categories, notes = _sell.value.notes) }
                .onFailure { PosLog.w("CATALOG_CACHE_SAVE_FAILED", "err" to it.message) }
        }
        return _sell.value.lines != before
    }

    /** What selling needs from one catalogue load (its first step). */
    private data class SellNeeds(
        val products: List<Product>,
        val positions: Map<Int, FiscalPosition>,
        /** Null when they could not be read: the first live-sync pass reads them instead. */
        val stamps: LiveStamps?,
        /** Null when Odoo's loader could not be read: no offers, ZZ Cash found the old way. */
        val offers: List<OfferProgram>?,
        val pricelist: PricelistHold,
        /** The methods this terminal books on; null when they could not be read (the last warning stays). */
        val methods: List<PaymentMethod>?,
    )

    /**
     * The second step of a catalogue load, in the background once the till can sell: the menu tabs,
     * note tags, company address, bill text, tax names and ZZ Cash rules, each after its change stamp.
     * One after another, never ahead of the cashier. Then the complete catalogue is saved on the till.
     */
    private fun loadTheRest(cfg: PosConfig, generation: Long) = launchSafe {
        withContext(OdooLane.BACKGROUND) {
            val stamps = runCatching { c.liveSync.read(LiveSync.WATCHED.keys - LiveSync.SELLING) }
                .onFailure { PosLog.w("LIVE_STAMPS_FAILED", "err" to it.message) }.getOrNull()
            val categories = runCatching { c.catalog.categories(cfg) }.onFailure { PosLog.w("MENU_LOAD_FAILED", "err" to it.message) }.getOrNull()
            val notes = runCatching { c.catalog.notes(cfg.noteIds) }.getOrNull()
            val address = cfg.companyId?.let { id ->
                runCatching { c.bills.companyAddress(id) }.onFailure { PosLog.w("BILL_ADDRESS_FAILED", "err" to it.message) }.getOrNull()
            }
            val text = runCatching { c.billText.load() }.onFailure { PosLog.w("BILL_TEXT_FAILED", "err" to it.message) }.getOrNull()
            val taxIds = _sell.value.products.flatMap { it.taxIds }.toSet()
            val names = runCatching { c.bills.taxNames(taxIds) }.getOrDefault(emptyMap())
            // ZZ Cash is optional: a failed lookup hides the balance, it never blocks a sale here.
            val program = zzProgram ?: if (!offersLoaded) runCatching { c.zzCash.program(cfg) }
                .onFailure { PosLog.w("ZZ_CASH_PROGRAM_LOOKUP_FAILED", "err" to it.message) }.getOrNull() else null
            val rules = program?.let { p -> runCatching { c.zzCash.rules(p.id) }.onFailure { PosLog.w("ZZ_RULES_LOAD_FAILED", "err" to it.message) }.getOrNull() }
            if (generation != catalogGeneration) return@withContext // a newer load owns the screen
            stamps?.let { st ->
                liveStamps = liveStamps?.let { st.over(it) } ?: st
                val at = System.currentTimeMillis()
                st.byModel.keys.forEach { stampsReadAt[it] = at }
            }
            text?.let { billText = it }
            if (names.isNotEmpty()) taxNames = names
            zzProgram = program
            cfg.companyId?.let { id ->
                // Keep the last address only for the same company; another company's never prints.
                if (address != null) companyAddress = id to address
                else if (companyAddress?.first != id) companyAddress = null
            }
            val shownCategories = categories ?: _sell.value.categories
            useCatalog(_sell.value.products, shownCategories)
            _sell.update {
                it.copy(
                    categories = shownCategories, notes = notes ?: it.notes, zzRules = rules ?: it.zzRules,
                    // The cashier's tab survives a reload; All when it is gone or none was chosen.
                    selectedCateg = it.selectedCateg?.takeIf { id -> shownCategories.any { c -> c.id == id } },
                ).regrid()
            }
            val products = _sell.value.products
            // Only a complete catalogue is cached, so a later failure never falls back to a partial one.
            runCatching { withContext(Dispatchers.IO) { c.catalogCache.save(cfg, products, categories = shownCategories, notes = _sell.value.notes) } }
                .onFailure { PosLog.w("CATALOG_CACHE_SAVE_FAILED", "err" to it.message) }
            PosLog.i("CATALOG_REST_LOADED", "tabs" to shownCategories.size, "zz_rules" to (rules?.size ?: -1))
        }
        // Photos of the menu tabs in the background; the rest load as their tiles appear.
        val products = _sell.value.products
        c.productImages.prefetch(products.filter { it.categIds.isNotEmpty() }, products)
    }

    // ------------------------------------------------------------------ live Odoo data

    /** Bumped by every full catalogue load; a live-sync pass applies only to the catalogue it started on. */
    private var catalogGeneration = 0L

    /** Odoo's change stamps when the till last caught up; null until first read. */
    private var liveStamps: LiveStamps? = null

    /** The bill's fixed words from Odoo's template; the bundled copy until it is read. */
    private var billText: BillText = BillText.BUNDLED

    private var liveJob: Job? = null

    /** When each watched model was last read (live sync looks at prices every 5 min, the rest every 15). */
    private val stampsReadAt = mutableMapOf<String, Long>()

    /** The last live-sync pass that reached Odoo. */
    private var lastLiveOkAt = 0L

    /** Within the store's sales day (Setup hours, 06:00–24:00 by default), on the till's clock. */
    private fun inSalesDay(): Boolean =
        Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in c.settings.salesDayStartHour until c.settings.salesDayEndHour

    /**
     * Every [LIVE_SYNC_MS] in the sales day, every [QUIET_SYNC_MS] outside it: what changed in Odoo
     * reaches the till by itself. Each till starts at its own random moment, so tills do not align.
     */
    private fun startLiveSync() {
        if (liveJob?.isActive == true || !_configured.value) return
        liveJob = launchSafe {
            delay(Random.nextLong(LIVE_SYNC_MS))
            var lastPassAt = 0L
            while (true) {
                delay(LIVE_SYNC_MS)
                if (!inSalesDay() && System.currentTimeMillis() - lastPassAt < QUIET_SYNC_MS) continue
                lastPassAt = System.currentTimeMillis()
                try {
                    withContext(OdooLane.BACKGROUND) { liveSyncOnce() }
                    lastLiveOkAt = System.currentTimeMillis()
                    checkContractIfDue()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nothing is advanced: the next pass applies the same changes again.
                    PosLog.w("LIVE_SYNC_FAILED", "reason" to e::class.simpleName, "err" to e.message)
                }
            }
        }
    }

    /**
     * One pass: the till's own config, then Odoo's change stamps, then only the parts that changed.
     * Never while a payment, a split bill or a full load runs; a bill with a paid part keeps its prices.
     */
    private suspend fun liveSyncOnce() {
        val s = _sell.value
        if (!_configured.value || s.loading || s.pay != PayUi.Idle || payStarting || _split.value != null) return
        // Offline, or the first load never completed: the full load is the retry (and the way back online),
        // 1, 2, 4 … 15 minutes apart after failures, never every minute.
        if (s.offlineCatalogSavedAt != null || completeCatalogAt == 0L) {
            if (System.currentTimeMillis() >= nextReloadAt) reloadAll()
            return
        }
        val generation = catalogGeneration
        posCatalog?.let { return liveSyncByLoader(it, generation) }
        val old = liveStamps ?: return run {
            val at = System.currentTimeMillis()
            liveStamps = c.liveSync.read().also { st -> st.byModel.keys.forEach { stampsReadAt[it] = at } }
        }
        val current = s.config ?: return reloadAll()
        val cfg = c.catalog.config(c.settings.posConfigId)
        if (cfg == null || cfg.paymentMethodProblem(c.paymentRoles()) != null) {
            return reloadAll() // shows why selling stopped
        }
        if (cfg != current) {
            // A session opened or closed elsewhere only changes the session; anything else reloads all.
            val sessionOnly = cfg.copy(currentSessionId = current.currentSessionId, sessionOpen = current.sessionOpen) == current
            PosLog.i("LIVE_CONFIG_CHANGED", "session_only" to sessionOnly, "session" to cfg.currentSessionId, "open" to cfg.sessionOpen)
            if (!sessionOnly) return reloadAll()
            _sell.update { if (catalogGeneration == generation) it.copy(config = cfg) else it }
            if (cfg.sessionOpen) c.orderSync.retryNow(OdooErrorKind.NO_SESSION) // orders waiting for a session go now
            reloadLoyalty(cfg) // offers come per session
        } else if ((offersDay != null && offersDay != odooToday()) || (!offersLoaded && cfg.sessionOpen)) {
            reloadLoyalty(cfg) // a new day (today's combos in, yesterday's out), or the offers never loaded
        }
        // Outside the sales day only the config and session are looked at; the stamps wait for the morning.
        if (!inSalesDay()) return
        val readAt = System.currentTimeMillis()
        val due = LiveSync.due(stampsReadAt, readAt).takeIf { it.isNotEmpty() } ?: return
        val now = c.liveSync.read(due)
        // Odoo just confirmed (or changed) its taxes: the saved prices are valid under this stamp (F3).
        if ("account.tax" in due) now.over(old).taxStamp?.let { stamp ->
            c.pricing.useTaxStamp(stamp)
            c.settings.taxStampConfirmedAt = System.currentTimeMillis()
        }
        val changed = now.changedSince(old)
        if (changed.isNotEmpty()) {
            PosLog.i("LIVE_CHANGES", "areas" to changed.joinToString(","))
            // Not marked read when the change did not apply: the next pass looks at those models again.
            val applied = applyLiveChanges(cfg, old, changed, generation)
            if (applied == null || catalogGeneration != generation) return
            liveStamps = now.over(old)
            due.forEach { stampsReadAt[it] = readAt }
            if (applied) repriceAfterLiveChange()
            return
        }
        liveStamps = now.over(old)
        due.forEach { stampsReadAt[it] = readAt }
    }

    /** A live change touched the open cart's prices: price it again and say so (a bill with a paid part keeps its own). */
    private fun repriceAfterLiveChange() {
        if (_sell.value.lines.isNotEmpty() && !_sell.value.partPaid.isPositive && _sell.value.pay == PayUi.Idle) {
            val at = SimpleDateFormat("h:mm a", Locale("en", "IN")).format(Date())
            _sell.update { it.copy(totals = null, notice = "Prices updated from Odoo at $at. Check the total.") }
            reprice()
        }
    }

    /**
     * Refresh what [changed] names. Returns whether the open cart must be repriced, or null when a
     * full load took over (too many products changed, or some could not be read).
     */
    private suspend fun applyLiveChanges(cfg: PosConfig, old: LiveStamps, changed: Set<LiveArea>, generation: Long): Boolean? {
        var cartChanged = false
        if (LiveArea.PRODUCTS in changed) {
            val since = old.productsSince ?: return null.also { loadCatalog() }
            val delta = c.catalog.productsChangedSince(cfg, since) ?: return null.also { loadCatalog() }
            val ids = c.catalog.productIds(cfg)
            var merge = CatalogService.merge(_sell.value.products, delta, ids)
            if (merge.missing.isNotEmpty()) merge = CatalogService.merge(merge.products, c.catalog.productsById(merge.missing), ids)
            if (merge.missing.isNotEmpty()) return null.also { loadCatalog() } // never keep a catalogue with holes
            if (catalogGeneration != generation) return null
            PosLog.i("LIVE_PRODUCTS_DELTA", "changed" to delta.size, "removed" to merge.removed)
            cartChanged = applyProducts(merge.products, cfg)
        }
        if (LiveArea.MENU in changed) {
            val categories = c.catalog.categories(cfg)
            if (catalogGeneration != generation) return null
            useCatalog(_sell.value.products, categories)
            _sell.update { st ->
                st.copy(categories = categories, selectedCateg = st.selectedCateg?.takeIf { id -> categories.any { it.id == id } }).regrid()
            }
        }
        if (LiveArea.NOTES in changed) {
            val notes = c.catalog.notes(cfg.noteIds)
            _sell.update { it.copy(notes = notes) }
        }
        if (LiveArea.COMPANY in changed) {
            cfg.companyId?.let { id -> c.bills.companyAddress(id)?.let { companyAddress = id to it } }
            printerLogo = null // read again at the next bill
        }
        if (LiveArea.TAXES in changed) {
            // The saved prices were emptied by the new tax stamp (liveSyncOnce); read the positions again.
            fiscalPositionsById = c.fiscalPositions.load((cfg.fiscalPositionIds + listOfNotNull(cfg.defaultFiscalPositionId)).distinct())
            val taxIds = _sell.value.products.flatMap { it.taxIds }.toSet()
            c.fiscalPositions.primeTaxFiscalPositions(taxIds.toList())
            taxNames = c.bills.taxNames(taxIds)
            _sell.update { it.copy(fiscalPosition = it.fiscalPosition?.id?.let { id -> fiscalPositionsById[id] } ?: it.fiscalPosition) }
            cartChanged = true
        }
        if (LiveArea.LOYALTY in changed) {
            reloadLoyalty(cfg)
            cartChanged = true // any offer may now apply, or stop applying, to the open cart
        }
        if (LiveArea.BILL_TEXT in changed) c.billText.load()?.let { billText = it }
        // A new rule, or a new product in a category a rule covers.
        if (LiveArea.PRICELIST in changed || LiveArea.PRODUCTS in changed) {
            usePricelistHold(c.pricelists.hold(cfg, SalesService.utc(System.currentTimeMillis())))
        }
        return cartChanged
    }

    /** A full catalogue load in place of a live-sync pass (offline, config changed, too much changed). */
    private fun reloadAll() { loadCatalog() }

    /** Loyalty programs, rules or rewards changed in Odoo (or the session or day did): read them again. */
    private suspend fun reloadLoyalty(cfg: PosConfig) {
        val offers = loadOffers(cfg)
        useOffers(offers)
        val program = if (offersLoaded) zzProgramOf(offerPrograms) else c.zzCash.program(cfg)
        val rules = program?.let { c.zzCash.rules(it.id) }
        zzProgram = program
        rewardsCache = null
        _sell.update { it.copy(zzRules = rules) }
        _sell.value.customer?.let { refreshZzCash(it) }
        if (_sell.value.lines.isNotEmpty() && !_sell.value.partPaid.isPositive && _sell.value.pay == PayUi.Idle) reprice()
    }

    // ------------------------------------------------------------------ offers (Odoo pos_loyalty)

    /** The store's offer programs for this session, from Odoo's own POS loader. */
    /** Products whose price the store's Odoo pricelist sets: the till charges list prices only (`PricelistService`). */
    private var pricelistHold = PricelistHold.NONE

    /** The pricelist hold was read from Odoo in this run (offline, an unread hold could let a held product through). */
    private var pricelistHoldKnown = false

    private fun usePricelistHold(hold: PricelistHold) {
        if (hold != pricelistHold) PosLog.i("PRICELIST_HOLD", "pricelist" to hold.pricelistId, "all" to hold.everything, "products" to hold.productIds.size)
        pricelistHold = hold
        pricelistHoldKnown = true
    }

    /** Odoo has not answered this till for a while (or the catalogue on screen is the saved one). */
    private fun odooOutOfReach(): Boolean =
        _sell.value.offlineCatalogSavedAt != null || System.currentTimeMillis() - c.odoo.lastOkAt > OFFLINE_AFTER_MS

    /**
     * F3: why a sale cannot be taken from Odoo's saved prices now, or null. Only while Odoo is out of
     * reach: saved prices are trusted for a day after Odoo last confirmed its taxes, and never for a
     * store whose pricelist hold was not read.
     */
    private fun offlineRefusal(cfg: PosConfig): String? {
        if (!odooOutOfReach()) return null
        return when {
            System.currentTimeMillis() - c.settings.taxStampConfirmedAt > OFFLINE_TRUST_MS ->
                "Odoo has been out of reach for over a day, so its saved prices are too old to charge. Sales can be taken again once Odoo is back."
            cfg.pricelistId != null && !pricelistHoldKnown ->
                "This store's pricelist has not been read from Odoo since the till started, so nothing can be charged offline yet."
            else -> null
        }
    }

    private fun pricelistRefusal(p: Product): String? =
        if (!pricelistHold.holds(p.id)) null
        else "${p.name}: Odoo's pricelist for this store sets its price, which this till cannot charge yet. Sell it on the web POS."

    private var offerPrograms: List<OfferProgram> = emptyList()

    /** Odoo's loader answered for this session (an empty list is a store without offers). */
    private var offersLoaded = false

    /** The Odoo date the offers were loaded for; the next day reloads them (daily combos). */
    private var offersDay: String? = null

    private suspend fun loadOffers(cfg: PosConfig): List<OfferProgram>? {
        val session = cfg.currentSessionId ?: return emptyList() // nothing is sold without a session
        return runCatching { c.offers.load(session) }
            .onSuccess { PosLog.i("OFFERS_LOADED", "session" to session, "programs" to it.size, "supported" to it.count { p -> p.supported }) }
            .onFailure { PosLog.w("OFFERS_LOAD_FAILED", "err" to it.message) }.getOrNull()
    }

    /**
     * A failed reload keeps the last good list (same store; programs out of their dates still stop by
     * themselves), so a bill never loses its offers to a timeout. Never loaded: the bar says so and
     * the live sync retries every minute.
     */
    private fun useOffers(programs: List<OfferProgram>?) {
        if (programs == null) {
            if (offersLoaded) PosLog.w("OFFERS_KEPT_LAST", "programs" to offerPrograms.size)
            _sell.update { it.copy(offersUnavailable = !offersLoaded) }
            return
        }
        offerPrograms = programs
        offersLoaded = true
        offersDay = odooToday()
        _sell.update { it.copy(offersUnavailable = false) }
    }

    /** ZZ Cash found without Odoo's loader: only while the loader never answered (it has dates and limits). */
    private suspend fun zzProgramFallback(cfg: PosConfig): ZzCashProgram? = if (offersLoaded) null else c.zzCash.program(cfg)

    /** A sale used a program with a usage limit: count it here as the web POS does, until Odoo's count is read again. */
    private fun countOfferUse(sale: PaidSale?) {
        val used = sale?.offers?.lines?.map { it.line.programId }?.toSet().orEmpty()
        if (used.isEmpty()) return
        offerPrograms = offerPrograms.map { p -> if (p.id in used && p.limitUsage) p.copy(totalOrderCount = p.totalOrderCount + 1) else p }
    }

    /** ZZ Cash: the one customer-card loyalty program Odoo gives this store; none, or two, means none. */
    private fun zzProgramOf(programs: List<OfferProgram>): ZzCashProgram? =
        programs.filter { it.type == "loyalty" && it.nominative && it.supported }.singleOrNull()?.let { ZzCashProgram(it.id, it.name) }

    /** The Odoo user's time zone, read with the catalogue; the device's until then. */
    private var odooTz: java.util.TimeZone? = null

    /** Today in the Odoo user's time zone: offers' dates are Odoo dates. Never waits on Odoo. */
    private fun odooToday(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = odooTz ?: java.util.TimeZone.getDefault() }.format(Date())

    /**
     * ⋮ → Enter Code (or a scanned code that is no product): a program's code is matched here as the web
     * POS does (exactly, then in any case); any other code is a coupon Odoo checks. The reward follows
     * on the reprice, by itself when its program has one, else in Reward.
     */
    fun enterCode(raw: String) = launchSafe {
        val code = raw.trim()
        val s = _sell.value
        if (code.isEmpty() || s.pay is PayUi.Working || lockedByPartPayment() || checkingCode) return@launchSafe
        if (!offersLoaded) return@launchSafe _sell.update { it.copy(error = "Offers are not loaded from Odoo yet. Check the connection, then try the code again.") }
        when (val check = Offers.ruleForCode(offerPrograms, code, odooToday(), s.offers)) {
            is Offers.CodeCheck.Rule -> {
                PosLog.i("CODE_ACCEPTED", "program" to check.program.id)
                _sell.update { it.copy(offers = it.offers.copy(codes = it.offers.codes + check.rule.id), totals = null, error = null,
                    notice = "Code accepted: ${check.program.name}. Its reward shows on the bill once the bill qualifies.") }
                reprice()
            }
            is Offers.CodeCheck.Refused -> _sell.update { it.copy(error = check.message) }
            Offers.CodeCheck.NoRule -> {
                if (s.offers.scanned.any { it.code == code }) return@launchSafe _sell.update { it.copy(error = "That coupon code has already been scanned and activated.") }
                val cfg = s.config ?: return@launchSafe
                checkingCode = true // Apply, Done and a scan of the same code wait for this answer
                val answer = try { c.offers.useCouponCode(cfg.id, code, SalesService.utc(System.currentTimeMillis()), s.customer?.id) }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { return@launchSafe _sell.update { it.copy(error = "Could not check the code: ${friendly(e)}") } }
                    finally { checkingCode = false }
                when (answer) {
                    is CouponCodeResult.Refused -> _sell.update { it.copy(error = answer.message) }
                    is CouponCodeResult.Accepted -> {
                        val program = offerPrograms.firstOrNull { it.id == answer.coupon.programId }
                        if (program == null || !program.supported) {
                            return@launchSafe _sell.update { it.copy(error = "${program?.name ?: "This coupon"} is not supported on this till yet (gift cards and eWallets: use the web POS).") }
                        }
                        PosLog.i("COUPON_ACCEPTED", "program" to program.id, "card" to answer.coupon.cardId)
                        if (_sell.value.offers.scanned.any { it.code == code }) return@launchSafe // already on the bill
                        if (_sell.value.orderUuid != s.orderUuid) return@launchSafe // another bill is on screen now
                        _parked.value.firstOrNull { b -> b.offers.scanned.any { it.code == code } }?.let { other ->
                            return@launchSafe _sell.update { it.copy(error = "That coupon is already on ${other.label}.") }
                        }
                        _sell.update { it.copy(offers = it.offers.copy(scanned = it.offers.scanned + answer.coupon), totals = null, error = null,
                            notice = "Coupon accepted: ${program.name}. Pick its reward under Reward if it does not apply by itself.") }
                        reprice()
                    }
                }
            }
        }
    }

    /** A coupon code is with Odoo now: Enter Code waits for its answer before taking another. */
    private var checkingCode = false

    /** Reward dialog: put this offer on the bill ([productId]: the free item picked). */
    fun claimOffer(offer: Claimable, productId: Int? = null) {
        if (_sell.value.pay is PayUi.Working || lockedByPartPayment()) return
        _sell.update {
            it.copy(offers = it.offers.copy(claims = it.offers.claims + Claim(offer.reward.id, offer.couponKey, productId ?: offer.productId, ZzCashRules.newRewardCode()),
                disabled = it.offers.disabled - offer.reward.id), totals = null, error = null)
        }
        reprice()
    }

    /** ✕ on an offer line: off the bill, and it stays off unless picked again under Reward. */
    fun removeOffer(code: String) {
        if (_sell.value.pay is PayUi.Working || lockedByPartPayment()) return
        val claim = _sell.value.offers.claims.firstOrNull { it.code == code } ?: return
        _sell.update {
            // As in the web POS, a reward taken off is not put back by itself (Reward can add it again).
            it.copy(offers = it.offers.copy(claims = it.offers.claims - claim, disabled = it.offers.disabled + claim.rewardId), totals = null)
        }
        reprice()
    }

    /** What the engine gave for one reprice, priced by Odoo. */
    private class Evaluated(
        val lines: List<PricedOffer>, val zz: ZzCashRedemption?, val coupons: List<CouponChange>,
        val claimable: List<Claimable>, val claims: List<Claim>, val messages: List<String>,
        val programs: List<OfferProgramInfo>,
    )

    /**
     * Run Odoo's offer rules on the priced cart, ZZ Cash included so they interact as in the web POS
     * (one whole-bill discount, discounts on what is left), then price every reward line with Odoo.
     */
    private suspend fun evaluateOffers(priced: List<CartLine>, s: SellState, position: FiscalPosition?): Evaluated {
        val zzId = zzProgram?.id?.takeIf { id -> offerPrograms.any { it.id == id } }
        val bal = s.zzCash?.takeIf { it.partnerId == s.customer?.id }
        val zzClaim = s.zzRedemption?.takeIf { bal != null && it.cardId == bal.cardId && zzId != null }
            ?.let { Claim(it.reward.rewardId, it.cardId, code = it.rewardCode) }
        val cards = if (bal != null && zzId != null) listOf(CardBalance(bal.cardId, zzId, bal.points.toRupees())) else emptyList()
        val input = OfferInput(offerPrograms, priced, s.customer?.id, odooToday(), cards, s.lines.firstOrNull { it.uuid == s.selectedLine }?.product?.id)
        val state = s.offers.copy(claims = s.offers.claims + listOfNotNull(zzClaim))
        val result = withContext(Dispatchers.Default) { Offers.evaluate(input, state) { ZzCashRules.newRewardCode() } }
        val zzLines = result.lines.filter { it.programId == zzId }
        val zz = s.zzRedemption?.takeIf { zzLines.isNotEmpty() }?.let { r ->
            r.copy(parts = zzLines.mapIndexed { i, l ->
                val line = c.pricing.price(CartLine("zz-${r.rewardCode}-$i", Product(l.productId, l.name, l.unitPrice, null, null, l.taxIds), 1), position)
                RewardPart(l.taxIds, Paise(-l.unitPrice.value), line.totalIncl, line.totalExcl)
            })
        }
        val promo = result.lines.filter { it.programId != zzId }.mapIndexed { i, l ->
            val line = c.pricing.price(CartLine("offer-${l.code}-$i", Product(l.productId, l.name, l.unitPrice, null, null, l.taxIds), l.qty), position)
            PricedOffer(l, line.totalIncl!!, line.totalExcl!!)
        }
        val coupons = result.coupons.filter { it.programId != zzId }
        val used = (promo.map { it.line.programId } + coupons.map { it.programId }).toSet()
        return Evaluated(promo, zz, coupons, result.claimable.filter { it.program.id != zzId },
            result.claims.filter { it.code != zzClaim?.code }, result.messages,
            offerPrograms.filter { it.id in used }.map { OfferProgramInfo.of(it) })
    }

    fun openSession() = launchSafe {
        if (_sell.value.loading) return@launchSafe // a second tap must not create a second pos.session
        _sell.update { it.copy(loading = true, error = null) }
        try {
            withContext(OdooLane.SALE) { c.sessions.open(c.settings.posConfigId) }
            val cfg = c.catalog.config(c.settings.posConfigId)
            _sell.update { it.copy(config = cfg ?: it.config, loading = false) }
            c.orderSync.retryNow(OdooErrorKind.NO_SESSION)
            // Offers and ZZ Cash come per session, and with a session Odoo's POS loader takes over: load again.
            if (cfg?.sessionOpen == true) loadCatalog() else cfg?.let { reloadLoyalty(it) }
        } catch (e: Exception) {
            _sell.update { it.copy(loading = false, error = friendly(e)) }
        }
    }

    // ------------------------------------------------------------------ session close (Phase 4)

    private val _closing = MutableStateFlow<ClosingUi?>(null)
    val closing: StateFlow<ClosingUi?> = _closing.asStateFlow()

    /** Sync the outbox like the web POS does, then load Odoo's closing figures. */
    fun startClose() = launchSafe {
        if (_closing.value != null) return@launchSafe // dialog already open
        if (_parked.value.isNotEmpty()) {
            return@launchSafe _sell.update { it.copy(error = "${_parked.value.size} open bill(s) waiting on this till: pay or remove them before closing.") }
        }
        val sessionId = _sell.value.config?.currentSessionId ?: return@launchSafe
        _closing.value = ClosingUi(busy = true)
        runCatching { c.orderSync.flushAll() }.onFailure { PosLog.w("CLOSE_FLUSH_FAILED", "err" to it.message) }
        try {
            val summary = c.sessions.closingSummary(sessionId)
            _closing.update { it?.copy(summary = summary, busy = false) }
        } catch (e: Exception) {
            _closing.update { it?.copy(busy = false, error = friendly(e)) }
        }
    }

    fun setCountedCash(v: String) = _closing.update { it?.copy(counted = v, error = null) }
    fun setClosingNotes(v: String) = _closing.update { it?.copy(notes = v) }

    fun confirmClose() = launchSafe {
        val ui = _closing.value ?: return@launchSafe
        // One close at a time: runs on Main.immediate, so this check and busy=true below happen
        // before the first suspension and a second tap sees busy. Closed is final.
        if (ui.busy || ui.closed) return@launchSafe
        val cfg = _sell.value.config ?: return@launchSafe
        val sessionId = cfg.currentSessionId ?: return@launchSafe
        val counted = if (cfg.cashControl) {
            Paise.parse(ui.counted) ?: return@launchSafe _closing.update { it?.copy(error = "Enter the cash counted in the drawer") }
        } else null
        _closing.update { it?.copy(busy = true, error = null) }
        try {
            when (val r = withContext(OdooLane.SALE) { c.sessions.close(sessionId, cfg.cashControl, counted, ui.notes.trim()) }) {
                CloseResult.Closed -> {
                    PosLog.i("SESSION_CLOSED", "session" to sessionId)
                    _closing.update { it?.copy(busy = false, closed = true) }
                    printZReport()
                }
                is CloseResult.Blocked -> _closing.update {
                    it?.copy(busy = false, error = buildList {
                        if (r.pending.unresolvedAttempts > 0) add("${r.pending.unresolvedAttempts} payment(s) still to check")
                        if (r.pending.unsyncedOrders > 0) add("${r.pending.unsyncedOrders} paid bill(s) not yet in Odoo")
                        if (r.pending.openVoids > 0) add("${r.pending.openVoids} void(s) to finish")
                        if (r.pending.pendingLoyalty > 0) add("${r.pending.pendingLoyalty} bill(s) with ZZ Cash not yet booked in Odoo")
                    }.joinToString(" and ") + ". Resolve before closing.")
                }
                is CloseResult.Refused -> _closing.update {
                    it?.copy(busy = false, error = r.message + if (r.redirect) "\nFinish closing in the Odoo back office." else "")
                }
            }
        } catch (e: Exception) {
            _closing.update { it?.copy(busy = false, error = friendly(e)) }
        }
    }

    /** Prints from the figures shown at close. A print failure never reopens or blocks anything (I8). */
    fun printZReport() = launchSafe {
        val ui = _closing.value ?: return@launchSafe
        val summary = ui.summary ?: return@launchSafe
        val cfg = _sell.value.config
        val data = ZReportData(
            storeName = cfg?.companyName?.ifBlank { null } ?: "ZenZebra",
            configName = cfg?.name ?: "",
            sessionName = "#${cfg?.currentSessionId ?: "?"}",
            printedAt = SimpleDateFormat("dd-MMM-yyyy HH:mm", Locale("en", "IN")).format(Date()),
            orderCount = summary.orderCount,
            orderTotal = summary.orderTotal,
            cash = summary.cash?.let { ZReportCash(it.name, it.opening, it.payments, it.moves, it.expected, Paise.parse(ui.counted)) },
            methods = summary.nonCash.map { ZReportMethod(it.name, it.count, it.amount) },
            notes = ui.notes.trim().ifBlank { null },
        )
        val printed = when (val r = c.gateway.print(ZReportFormatter(c.settings.printerWidth).format(data), "Z${cfg?.currentSessionId ?: 0}", c.settings.printerWidth)) {
            PrintResult.Printed -> true
            is PrintResult.Failed -> { PosLog.w("ZREPORT_PRINT_FAILED", "reason" to r.reason); false }
        }
        _closing.update { it?.copy(printed = printed) }
    }

    fun dismissClosing() {
        val closed = _closing.value?.closed == true
        _closing.value = null
        if (closed) loadCatalog() // config now shows no open session
    }

    // ------------------------------------------------------------------ today's sales

    private val _sales = MutableStateFlow<SalesUi?>(null)
    val sales: StateFlow<SalesUi?> = _sales.asStateFlow()
    private var salesJob: Job? = null

    /** SALES in the top bar: the store's day so far, refreshed every minute while the screen is open. */
    fun openSales() {
        if (_sales.value != null) return
        _sales.value = SalesUi(storeName = _sell.value.config?.name ?: c.settings.posConfigName)
        salesJob = launchSafe {
            var first = true
            while (true) {
                // Never competes with a payment for the network; the minute refreshes wait behind the cashier.
                if (_sell.value.pay !is PayUi.Working) {
                    if (first) loadSales() else withContext(OdooLane.BACKGROUND) { loadSales() }
                    first = false
                }
                delay(SALES_REFRESH_MS)
            }
        }
    }

    fun refreshSales() { if (_sales.value?.loading == false) launchSafe { loadSales() } }

    fun closeSales() {
        salesJob?.cancel()
        salesJob = null
        _sales.value = null
    }

    /** A failed refresh keeps the last figures on screen, marked with their time. */
    private suspend fun loadSales() {
        _sales.update { it?.copy(loading = true) }
        val result = try {
            withTimeoutOrNull(SALES_TIMEOUT_MS) {
                c.sales.storeSales(c.settings.posConfigId, System.currentTimeMillis(), c.settings.salesDayStartHour, c.settings.salesDayEndHour)
            } ?: return _sales.update { it?.copy(loading = false, error = "Odoo is slow to answer") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PosLog.w("SALES_LOAD_FAILED", "err" to e.message)
            return _sales.update { it?.copy(loading = false, error = friendly(e)) }
        }
        PosLog.i("SALES_LOADED", "bills" to result.bills, "hours" to result.hours.size)
        _sales.update { it?.copy(sales = result, updatedAt = System.currentTimeMillis(), loading = false, error = null) }
    }

    // ------------------------------------------------------------------ void (Phase 4b)

    val openVoids: StateFlow<List<com.bohemian.odoo.pos.data.VoidAttemptEntity>> =
        c.db.voids().observeOpen().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _voiding = MutableStateFlow<VoidUi?>(null)
    val voiding: StateFlow<VoidUi?> = _voiding.asStateFlow()

    /** Today's card sales (a void only works before the terminal settles the batch). */
    fun startVoid() = launchSafe {
        _voiding.value = VoidUi(busy = true)
        val since = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        _voiding.update { it?.copy(sales = c.journal.paidCardSince(since), busy = false) }
    }

    fun chooseVoid(sale: PaymentAttempt?) = _voiding.update { it?.copy(confirm = sale, message = null) }

    fun confirmVoid() = launchSafe {
        if (_voiding.value?.busy == true) return@launchSafe // a second tap: the first void is running
        val sale = _voiding.value?.confirm ?: return@launchSafe
        _voiding.update { it?.copy(busy = true, message = null) }
        val msg = when (val o = c.payments.void(sale.billingRef)) {
            is VoidOutcome.Voided -> "✓ Voided ${o.attempt.amount.format()} on the terminal.\n" + recordRefund(o.attempt)
            is VoidOutcome.NotVoided -> "✕ Not voided: ${o.reason}\nIf the batch is settled, refund instead."
            is VoidOutcome.NotSent -> "Void not sent: ${o.reason}"
            is VoidOutcome.Unresolved ->
                "⚠ Void status not confirmed. Do NOT void again — a supervisor must check the terminal report.\nRef ${o.attempt.billingRef}"
            is VoidOutcome.Refused -> o.reason
        }
        _voiding.update { it?.copy(busy = false, confirm = null, message = msg) }
    }

    fun dismissVoid() { _voiding.value = null }

    fun resolveVoid(ref: String, voided: Boolean) = launchSafe {
        runCatching { c.payments.resolveVoid(ref, voided) }.onFailure { _sell.update { s -> s.copy(error = it.message) } }
    }

    fun confirmOdooRefund(ref: String) = launchSafe {
        runCatching { c.payments.confirmOdooRefund(ref, "manual: supervisor recorded the refund in Odoo") }
            .onFailure { _sell.update { s -> s.copy(error = it.message) } }
    }

    /** Red-bar retry for a voided sale whose Odoo refund was not created yet. */
    /** Voids whose Odoo refund is being created now, so a second tap does not create it again. */
    private val refunding = mutableSetOf<String>()

    fun retryOdooRefund(ref: String) = launchSafe {
        if (!refunding.add(ref)) return@launchSafe
        try {
            val v = c.voidJournal.get(ref) ?: return@launchSafe
            _sell.update { it.copy(error = recordRefund(v)) }
        } finally {
            refunding.remove(ref)
        }
    }

    /**
     * Phase 4c: the Odoo refund order for a voided sale. Once it is in Odoo or durably in the
     * outbox, the void is closed. Anything else leaves the void open for a retry or a supervisor.
     */
    private suspend fun recordRefund(v: com.bohemian.odoo.pos.payment.VoidAttempt): String = try {
        val last4 = c.journal.get(v.saleRef)?.details?.last4
        when (val r = c.refunds.refundVoidedSale(v, _sell.value.config?.currentSessionId, last4)) {
            is RefundResult.Recorded -> {
                c.payments.confirmOdooRefund(v.billingRef,
                    "refund order ${r.refundUuid} " + if (r.syncedNow) "synced to Odoo" else "queued in outbox")
                if (r.syncedNow) "Refund recorded in Odoo." else "Refund queued — it reaches Odoo when the connection is back."
            }
            is RefundResult.NotEligible -> "Odoo refund not created: ${r.reason}"
        }
    } catch (e: Exception) {
        PosLog.w("REFUND_BUILD_FAILED", "void" to v.billingRef, "err" to e.message)
        "Odoo refund not created (${friendly(e)}). Retry from the red bar."
    }

    private var searchJob: Job? = null

    /**
     * The field updates at once; the grid is filtered when typing pauses ([SEARCH_DEBOUNCE_MS]),
     * not on every key over 2,400 products. Clearing the search shows the tab again at once.
     */
    fun setQuery(q: String) {
        searchJob?.cancel()
        if (q.isBlank()) { _sell.update { it.copy(query = q).regrid() }; return }
        _sell.update { it.copy(query = q) }
        searchJob = launchSafe { delay(SEARCH_DEBOUNCE_MS); _sell.update { it.regrid() } }
    }

    /** Keyboard-wedge scanners type the barcode then Enter. */
    fun submitQuery() {
        val s = _sell.value
        val q = s.query.trim()
        val exact = s.products.firstOrNull { it.barcode == q }
        if (exact != null) { add(exact); _sell.update { it.copy(query = "").regrid() }; return }
        // A scanned coupon or a typed code (one word, no product shown for it) goes to Enter Code.
        if (q.length >= 4 && ' ' !in q && grid.show(null, q).isEmpty() && offersLoaded) {
            _sell.update { it.copy(query = "").regrid() }
            enterCode(q)
        }
    }

    fun add(p: Product) {
        pricelistRefusal(p)?.let { msg -> _sell.update { it.copy(error = msg) }; return }
        var selected: String? = null
        editCart { lines ->
            val i = lines.indexOfFirst { it.product.id == p.id }
            if (i >= 0) lines.toMutableList().also { it[i] = it[i].copy(qty = it[i].qty + 1, totalIncl = null, totalExcl = null); selected = it[i].uuid }
            else (lines + CartLine(UUID.randomUUID().toString(), p, 1)).also { selected = it.last().uuid }
        }
        _sell.update { it.copy(selectedLine = selected, keypad = "") }
    }

    // ------------------------------------------------------------------ menu, line selection, keypad, notes

    /**
     * Tap a tab: select it, or go back to All when it is already selected (Odoo web POS). Filters the
     * catalogue already in memory; never fetches.
     */
    fun selectCategory(id: Int?) =
        _sell.update { it.copy(selectedCateg = if (id != null && it.selectedCateg == id) null else id, query = "").regrid() }

    /** Fold, shrink or grow the keypad (key height in dp, 0 = folded); kept for this terminal. */
    fun setKeypadKeyDp(dp: Int) {
        val v = dp.coerceIn(0, MAX_KEY_DP)
        c.settings.keypadKeyDp = v
        _sell.update { it.copy(keypadKeyDp = v) }
    }

    fun selectLine(uuid: String) = _sell.update { it.copy(selectedLine = uuid, keypad = "") }

    /**
     * Qty keypad, as in the Odoo numpad: digits replace the selected line's quantity; ⌫ removes the
     * last digit, and on an empty entry removes the line. Whole units only (the menu is sold by piece).
     */
    fun keypadDigit(d: Char) {
        val s = _sell.value
        val uuid = s.selectedLine ?: return
        val entry = (s.keypad + d).trimStart('0').take(3)
        _sell.update { it.copy(keypad = entry) }
        val qty = entry.toIntOrNull() ?: return
        editCart { lines -> lines.map { if (it.uuid == uuid) it.copy(qty = qty, totalIncl = null, totalExcl = null) else it } }
    }

    fun keypadBackspace() {
        val s = _sell.value
        val uuid = s.selectedLine ?: return
        if (s.keypad.isEmpty()) {
            remove(uuid)
            _sell.update { it.copy(selectedLine = it.lines.lastOrNull()?.uuid) }
            return
        }
        val entry = s.keypad.dropLast(1)
        _sell.update { it.copy(keypad = entry) }
        val qty = entry.toIntOrNull() ?: 1
        editCart { lines -> lines.map { if (it.uuid == uuid) it.copy(qty = qty, totalIncl = null, totalExcl = null) else it } }
    }

    /** Note tags and free text on the selected line, stored as Odoo's pos.order.line.note JSON. */
    fun setLineNote(uuid: String, tags: List<LineNote>) {
        if (_sell.value.pay is PayUi.Working) return
        _sell.update { s -> s.copy(lines = s.lines.map { if (it.uuid == uuid) it.copy(note = tags) else it }) }
    }

    fun changeQty(uuid: String, delta: Int) = editCart { lines ->
        lines.mapNotNull { l ->
            if (l.uuid != uuid) l else (l.qty + delta).takeIf { it > 0 }?.let { l.copy(qty = it, totalIncl = null, totalExcl = null) }
        }
    }

    fun remove(uuid: String) = editCart { lines -> lines.filterNot { it.uuid == uuid } }

    /** "Name or mobile": the last digits of a mobile, or part of a name or email. */
    fun setCustomerQuery(v: String) {
        val cleaned = v.filter { ch -> ch.isLetterOrDigit() || ch in " +.-@'" }.take(40)
        _sell.update { it.copy(phone = cleaned, customerNotFound = false, customerNote = null) }
        searchCustomers(cleaned)
    }

    fun setCustomerName(v: String) = _sell.update { it.copy(customerName = v.take(60), customerNote = null) }

    /**
     * Done on the mobile field: attach the customer with exactly this number, or offer the
     * new-customer form when nobody has it.
     */
    fun lookupCustomer() = launchSafe {
        if (_sell.value.customerBusy) return@launchSafe // Find and the keyboard's Search together, or a double tap
        // Find on a name searches at once instead of after the typing pause.
        if (!CustomerService.isPhoneQuery(_sell.value.phone)) return@launchSafe searchCustomers(_sell.value.phone, debounce = false)
        val e164 = c.customers.normalizeIndianMobile(_sell.value.phone)
            ?: return@launchSafe _sell.update { it.copy(customerNote = "Enter the 10-digit mobile number.") }
        val typed = _sell.value.phone
        _sell.update { it.copy(customerBusy = true, customerNote = null, customerNoteOk = false) }
        try {
            val found = c.customers.findByPhone(e164)
            // The cashier typed another number meanwhile: this answer is for the old one, drop it.
            if (_sell.value.phone != typed) return@launchSafe _sell.update { it.copy(customerBusy = false) }
            if (found != null) pickCustomer(found)
            _sell.update { it.copy(customerBusy = false, customerNotFound = found == null) }
        } catch (e: Exception) {
            PosLog.w("CUSTOMER_LOOKUP_FAILED", "phone" to Redact.phone(e164), "err" to e.message)
            _sell.update { it.copy(customerBusy = false, customerNote = "Could not search Odoo: ${friendly(e)}") }
        }
    }

    /**
     * Save the typed name and mobile as a new Odoo customer (res.partner) and attach them. The
     * number is looked up again first, so a customer is never created twice.
     */
    fun saveNewCustomer() = launchSafe {
        val s = _sell.value
        if (s.customerBusy) return@launchSafe
        val e164 = c.customers.normalizeIndianMobile(s.phone)
            ?: return@launchSafe _sell.update { it.copy(customerNote = "Type the new customer's 10-digit mobile in Name or mobile.") }
        val name = s.customerName.trim()
        if (name.isEmpty()) return@launchSafe _sell.update { it.copy(customerNote = "Enter the customer's name.") }
        _sell.update { it.copy(customerBusy = true, customerNote = null, customerNoteOk = false) }
        try {
            val existing = c.customers.findByPhone(e164)
            val saved = existing ?: c.customers.create(name, e164)
            PosLog.i("CUSTOMER_SAVED", "partner" to saved.id, "new" to (existing == null))
            // The form is locked while saving; if the number changed anyway, do not attach this one.
            if (c.customers.normalizeIndianMobile(_sell.value.phone) != e164) {
                return@launchSafe _sell.update {
                    it.copy(customerBusy = false, customerNoteOk = false,
                        customerNote = "Saved ${saved.name} (••••${e164.takeLast(4)}), but the number was changed, so it was not attached.")
                }
            }
            pickCustomer(saved)
            _sell.update {
                it.copy(customerBusy = false, customerNoteOk = true, customerNote =
                    if (existing == null) "Saved: ${saved.name} · ••••${e164.takeLast(4)}"
                    else "Already in Odoo as ${saved.name}: attached.")
            }
        } catch (e: Exception) {
            PosLog.w("CUSTOMER_SAVE_FAILED", "phone" to Redact.phone(e164), "err" to e.message)
            _sell.update { it.copy(customerBusy = false, customerNoteOk = false, customerNote = "Customer not saved: ${friendly(e)}") }
        }
    }

    /** Pick a customer from the search results, an exact lookup, or a just-saved new customer. */
    /** The last customer picked and when: the same row tapped twice within a second is one pick. */
    private var lastPick: Pair<Int, Long>? = null

    fun pickCustomer(customer: Customer) {
        val now = android.os.SystemClock.uptimeMillis()
        if (lastPick?.let { it.first == customer.id && now - it.second < 1_000 } == true) return
        lastPick = customer.id to now
        phoneSearchJob?.cancel() // a late search result must not reopen the list
        val changed = _sell.value.customer?.id != customer.id
        _sell.update { it.copy(
            customer = customer,
            customerName = customer.name,
            phone = customer.phone?.filter { it.isDigit() || it == '+' } ?: _sell.value.phone,
            customerMatches = emptyList(),
            customerHasMore = false,
            customerNotFound = false,
            // A different customer has a different card: the old balance and reward must not carry over.
            zzCash = if (changed) null else it.zzCash,
            zzRedemption = if (changed) null else it.zzRedemption,
            totals = if (changed) null else it.totals,
        ) }
        resolveCustomer(customer)
    }

    /**
     * The customer's fiscal position (from their state) and ZZ Cash, read again. Applied only while the
     * same bill and customer are on screen. Returns the ZZ Cash read.
     */
    private fun resolveCustomer(customer: Customer): Job {
        val uuid = _sell.value.orderUuid
        launchSafe {
            val fposId = c.fiscalPositions.resolve(_sell.value.config ?: return@launchSafe, customer)
            // Another customer or bill may be on screen meanwhile: apply only to the one still attached.
            if (_sell.value.customer?.id != customer.id || _sell.value.orderUuid != uuid) return@launchSafe
            _sell.update { it.copy(fiscalPosition = fiscalPositionsById[fposId]) }
            reprice()
        }
        return refreshZzCash(customer)
    }

    /**
     * Read the customer's ZZ Cash balance. Never blocks the sale: on failure the cashier
     * just sees no balance. The result is only applied if the same customer is still attached.
     */
    private fun refreshZzCash(customer: Customer) = launchSafe {
        _sell.update { s -> if (s.customer?.id == customer.id) s.copy(zzLookup = ZzLookup.LOADING) else s }
        val program = zzProgram ?: _sell.value.config?.let { cfg -> runCatching { zzProgramFallback(cfg) }.getOrNull() }?.also { zzProgram = it }
        if (program == null) {
            _sell.update { s -> if (s.customer?.id == customer.id) s.copy(zzLookup = ZzLookup.FAILED) else s }
            return@launchSafe
        }
        // The points this cart earns count towards a reward (Odoo's rule), so the earning rules must be
        // here too, not only at payment: without them the cart earns "0" and no reward fits.
        val rulesJob = if (_sell.value.zzRules != null) null else async {
            runCatching { c.zzCash.rules(program.id) }.onFailure { PosLog.w("ZZ_RULES_LOAD_FAILED", "err" to it.message) }.getOrNull()
        }
        val (lookup, balance, rewards) = try {
            val b = c.zzCash.balance(customer.id, program.id)
            if (b == null) Triple(ZzLookup.NO_CARD, null, emptyList()) else Triple(ZzLookup.LOADED, b, rewardsFor(program.id))
        } catch (e: Exception) {
            PosLog.w("ZZ_CASH_LOOKUP_FAILED", "partner" to customer.id, "err" to e.message)
            Triple(ZzLookup.FAILED, null, emptyList<ZzCashReward>())
        }
        val rules = rulesJob?.await()
        _sell.update { s ->
            if (s.customer?.id != customer.id) s
            else s.copy(zzCash = balance, zzRewards = rewards, zzLookup = lookup, zzRules = s.zzRules ?: rules)
        }
    }

    /** The program's "₹N off" rewards: they change rarely, so read once per catalogue load. */
    private var rewardsCache: Pair<Int, List<ZzCashReward>>? = null

    private suspend fun rewardsFor(programId: Int): List<ZzCashReward> =
        rewardsCache?.takeIf { it.first == programId }?.second
            ?: c.zzCash.rewards(programId).also { rewardsCache = programId to it }

    /** "Retry" on the ZZ Cash row after a failed lookup. */
    fun retryZzCash() {
        if (_sell.value.zzLookup == ZzLookup.LOADING) return // already reading the card
        _sell.value.customer?.let { refreshZzCash(it) }
    }

    /**
     * Apply a ZZ Cash reward to this sale. It is priced by Odoo on the next reprice (split over the
     * cart's tax groups), and the payment total includes it. Refused when the card cannot pay,
     * counting the points this cart earns as Odoo does.
     */
    fun applyZzCash(reward: ZzCashReward) {
        val s = _sell.value
        if (s.pay is PayUi.Working || lockedByPartPayment()) return
        val balance = s.zzCash?.takeIf { s.customer?.id == it.partnerId }
        if (balance == null) {
            _sell.update { it.copy(error = "ZZ Cash balance not loaded for this customer") }; return
        }
        if (s.lines.isEmpty() || s.lines.any { !it.isPriced }) {
            _sell.update { it.copy(error = "Wait for Odoo prices before applying ZZ Cash") }; return
        }
        _parked.value.firstOrNull { it.zzReward != null && it.customer?.id == s.customer?.id }?.let { other ->
            _sell.update { it.copy(error = "This customer's ZZ Cash reward is already on ${other.label}.") }; return
        }
        if (reward.points > ZzCashRules.available(balance, s.zzEarned)) {
            _sell.update { it.copy(error = "Not enough ZZ Cash for ${reward.description}") }; return
        }
        val cartTotal = totalsWith(s.lines, null, s.offerLines)?.totalIncl
        if (cartTotal == null || reward.amount >= cartTotal) {
            _sell.update { it.copy(error = "ZZ Cash reward is more than the bill") }; return
        }
        _sell.update {
            it.copy(
                zzRedemption = ZzCashRedemption(balance.cardId, reward, ZzCashRules.newRewardCode()),
                totals = null, error = null,
            )
        }
        reprice()
    }

    /** Take the ZZ Cash reward off this sale. */
    fun removeZzCash() {
        if (_sell.value.pay is PayUi.Working || lockedByPartPayment()) return
        _sell.update { it.copy(zzRedemption = null, totals = null) }
        reprice()
    }

    /** Clear the attached customer, search results, ZZ Cash balance and any reward. */
    fun clearCustomer() {
        if (_sell.value.pay is PayUi.Working || lockedByPartPayment()) return // the charge already holds this customer
        phoneSearchJob?.cancel()
        val hadReward = _sell.value.zzRedemption != null
        _sell.update {
            it.copy(customer = null, customerMatches = emptyList(), customerHasMore = false, phone = "", customerName = "",
                customerNotFound = false, customerNote = null,
                zzCash = null, zzRewards = emptyList(), zzRedemption = null, zzLookup = ZzLookup.NONE,
                // The reward was inside the total: without it the old total must never be charged.
                totals = if (hadReward) null else it.totals)
        }
        if (hadReward) reprice()
    }

    private var phoneSearchJob: kotlinx.coroutines.Job? = null

    /**
     * Search in the background once 4 digits or 2 letters are typed: the first page, A→Z. Debounced:
     * only the last keystroke's search runs. Clears the list when too little is typed.
     */
    private fun searchCustomers(query: String, debounce: Boolean = true) {
        phoneSearchJob?.cancel() // any edit cancels the search in flight
        if (!CustomerService.isSearchable(query)) {
            _sell.update { it.copy(customerMatches = emptyList(), customerHasMore = false) }
            return
        }
        phoneSearchJob = launchSafe {
            if (debounce) delay(CUSTOMER_SEARCH_DEBOUNCE_MS)
            try {
                val page = c.customers.search(query)
                // Only for what is typed now: a slow answer for older text is dropped.
                _sell.update {
                    if (it.phone == query) it.copy(customerMatches = page, customerHasMore = page.size == CustomerService.PAGE) else it
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The cashier can still type the full number; the reason goes to the log only.
                PosLog.w("CUSTOMER_SEARCH_FAILED", "err" to e.message)
            }
        }
    }

    /** The list was scrolled to its end: append the next page of the same search. */
    fun loadMoreCustomers() {
        val s = _sell.value
        if (!s.customerHasMore || phoneSearchJob?.isActive == true) return
        val query = s.phone
        val offset = s.customerMatches.size
        phoneSearchJob = launchSafe {
            try {
                val page = c.customers.search(query, offset)
                _sell.update {
                    if (it.phone != query || it.customerMatches.size != offset) it
                    else it.copy(
                        customerMatches = (it.customerMatches + page).distinctBy { m -> m.id },
                        customerHasMore = page.size == CustomerService.PAGE,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PosLog.w("CUSTOMER_SEARCH_FAILED", "offset" to offset, "err" to e.message)
            }
        }
    }

    private fun editCart(f: (List<CartLine>) -> List<CartLine>) {
        if (_sell.value.pay is PayUi.Working || lockedByPartPayment()) return // cart frozen while paying
        // A payment still to check would send the bill as it was charged (I4): the screen must match it.
        if (unresolved.value.any { it.orderUuid == _sell.value.orderUuid }) {
            _sell.update { it.copy(error = "This bill has a payment to check (yellow bar). Resolve it before changing the bill.") }; return
        }
        _sell.update { it.copy(lines = f(it.lines), totals = null) }
        reprice()
    }

    private var repriceJob: Job? = null

    /**
     * Bumped by every reprice. Only the newest may write prices, totals or a pricing error, so a
     * slow answer for an older cart can never bring back a removed reward, totals of another fiscal
     * position, or enable Pay with stale figures.
     */
    private var priceGeneration = 0L

    /**
     * Price the cart with Odoo (I2). Taps and keypad digits coming quickly are coalesced into one
     * pass ([REPRICE_DEBOUNCE_MS]); a pass still running for an older cart is cancelled, which also
     * cancels its HTTP calls.
     */
    private fun reprice() {
        // A bill with a paid part keeps the prices and rewards it was charged with.
        if (_sell.value.partPaid.isPositive) return
        val generation = ++priceGeneration
        repriceJob?.cancel()
        _sell.update { it.copy(pricing = true, error = null) }
        repriceJob = launchSafe {
            delay(REPRICE_DEBOUNCE_MS)
            try {
                val start = _sell.value
                val position = start.fiscalPosition
                val priced = c.pricing.priceAll(start.lines, position)
                val wanted = _sell.value.zzRedemption
                // With the store's offers loaded, Odoo's rules decide (ZZ Cash among them); without, ZZ Cash alone.
                val offers = if (offersLoaded && priced.isNotEmpty()) evaluateOffers(priced, _sell.value, position) else null
                val pricedRedemption = if (offers != null) offers.zz
                    else wanted?.takeIf { affordable(it, priced) }?.let { priceRedemption(it, priced, position) }
                val dropped = wanted != null && pricedRedemption == null
                _sell.update { s ->
                    when {
                        generation != priceGeneration -> s // a newer reprice owns the cart
                        // The cart changed while we were pricing: the newer reprice will answer.
                        s.lines.map { it.uuid to it.qty } != priced.map { it.uuid to it.qty } -> s
                        else -> s.copy(
                            lines = priced.map { p -> p.copy(note = s.lines.firstOrNull { it.uuid == p.uuid }?.note ?: p.note) },
                            zzRedemption = pricedRedemption,
                            offerLines = offers?.lines.orEmpty(),
                            offerCoupons = offers?.coupons.orEmpty(),
                            offerClaimable = offers?.claimable.orEmpty(),
                            offerPrograms = offers?.programs.orEmpty(),
                            offers = if (offers != null) s.offers.copy(claims = offers.claims) else s.offers,
                            totals = totalsWith(priced, pricedRedemption, offers?.lines.orEmpty()),
                            pricing = false,
                            error = listOfNotNull(
                                "ZZ Cash reward removed: the cart no longer qualifies. Check the total.".takeIf { dropped },
                                offers?.messages?.takeIf { it.isNotEmpty() }?.joinToString(" ")?.let { "$it. Check the total." },
                            ).joinToString(" ").ifEmpty { s.error },
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == priceGeneration) {
                    val why = if (OdooErrors.kind(e) == OdooErrorKind.TRANSIENT)
                        "Odoo is out of reach and has never priced one of these items, so it can be sold once Odoo is back. Items Odoo priced before still sell."
                    else "Could not get prices from Odoo: ${friendly(e)}"
                    _sell.update { it.copy(pricing = false, error = why) }
                }
            }
        }
    }

    /** The card, with what [lines] earn, still pays for [r]: a smaller cart earns less. */
    private fun affordable(r: ZzCashRedemption, lines: List<CartLine>): Boolean {
        val s = _sell.value
        val balance = s.zzCash?.takeIf { it.cardId == r.cardId } ?: return false
        return r.reward.points <= ZzCashRules.available(balance, LoyaltyPoints.earned(s.zzRules.orEmpty(), lines))
    }

    /**
     * Split the reward over the cart's tax groups like Odoo ([ZzCashRules.split]) and price each
     * negative line with Odoo's compute_all. Null when the reward no longer applies (bill at or below it).
     */
    private suspend fun priceRedemption(r: ZzCashRedemption, lines: List<CartLine>, position: FiscalPosition?): ZzCashRedemption? {
        val cartTotal = c.pricing.totals(lines)?.totalIncl ?: return null
        if (r.reward.amount >= cartTotal) return null
        val parts = ZzCashRules.split(r.reward.amount, lines).takeIf { it.isNotEmpty() } ?: return null
        val priced = parts.mapIndexed { i, part ->
            val line = c.pricing.price(
                CartLine(
                    uuid = "zz-${r.rewardCode}-$i",
                    product = Product(r.reward.discountProductId, r.reward.description, Paise(-part.amount.value), null, null, part.taxIds),
                    qty = 1,
                ),
                position,
            )
            part.copy(totalIncl = line.totalIncl, totalExcl = line.totalExcl)
        }
        return r.copy(parts = priced)
    }

    /** Cart total plus the priced rewards (negative lines). Null until every line is priced. */
    private fun totalsWith(lines: List<CartLine>, redemption: ZzCashRedemption?, offers: List<PricedOffer> = emptyList()): CartTotals? {
        val base = c.pricing.totals(lines) ?: return null
        val offIncl = offers.fold(Paise.ZERO) { a, o -> a + o.totalIncl }
        val offExcl = offers.fold(Paise.ZERO) { a, o -> a + o.totalExcl }
        if (redemption == null) return CartTotals(base.totalIncl + offIncl, base.totalExcl + offExcl)
        val incl = redemption.totalIncl ?: return null
        val excl = redemption.totalExcl ?: return null
        return CartTotals(base.totalIncl + incl + offIncl, base.totalExcl + excl + offExcl)
    }

    // ------------------------------------------------------------------ payment

    /**
     * True from the tap until the pay flow ends. pay only becomes Working after
     * prepareSnapshot has suspended, so without this a second tap in that gap would start a
     * second flow for the same order (UAT case 10). Main thread only.
     */
    private var payStarting = false

    private fun launchPay(block: suspend () -> Unit) = launchSafe {
        if (payStarting || _sell.value.pay is PayUi.Working) return@launchSafe
        payStarting = true
        try { block() } finally { payStarting = false }
    }

    fun payWithTerminal(mode: PayMode) = launchPay {
        if (lockedByPartPayment()) return@launchPay
        if (c.paymentRoles().forMode(mode) == 0) { _sell.update { it.copy(error = "${mode.label()} is not set up on this till (Setup)") }; return@launchPay }
        val snap = prepareSnapshot() ?: return@launchPay
        _sell.update { it.copy(pay = PayUi.Working(mode.label(), "Complete the payment on the terminal…")) }
        val outcome = c.payments.pay(snap.orderUuid, snap.totals().totalIncl, mode, snap.customerPhone?.takeLast(10))
        handleOutcome(outcome)
    }

    fun payCash(tendered: Paise?) = launchPay {
        if (lockedByPartPayment()) return@launchPay
        if (c.settings.cashPaymentMethodId == 0) { _sell.update { it.copy(error = "No cash method configured") }; return@launchPay }
        val snap = prepareSnapshot() ?: return@launchPay
        val total = snap.totals().totalIncl
        val change = tendered?.takeIf { it >= total }?.minus(total)
        // The cash is in the drawer: the bill leaves the queue before it is sent, since a cash sale
        // leaves no journal row that would stop a restart bringing it back to be sold again.
        billPaid(snap.orderUuid)
        _sell.update { it.copy(pay = PayUi.Working("CASH", "Saving order…")) }
        val sale = c.finalizer.finalize(snap, PaymentLineData(c.settings.cashPaymentMethodId, total, null, null, null))
        countOfferUse(sale)
        // Paid as soon as Odoo has the order; the bill prints behind the "Paid" screen.
        _sell.update { it.copy(pay = PayUi.Done(outcome = null, paidByCash = true, cashChange = change)) }
        printInBackground(sale, "CASH", emptyList())
    }

    // ------------------------------------------------------------------ split payment (Odoo payment screen)

    private val _split = MutableStateFlow<SplitUi?>(null)
    val split: StateFlow<SplitUi?> = _split.asStateFlow()

    /** Split bills with a part paid but not the total, from this or an earlier run (red bar). */
    private val _partlyPaid = MutableStateFlow<List<PartlyPaid>>(emptyList())
    val partlyPaid: StateFlow<List<PartlyPaid>> = _partlyPaid.asStateFlow()

    fun refreshPartlyPaid() = launchSafe { _partlyPaid.value = c.finalizer.partlyPaid() }

    /**
     * A split bill with a part paid cannot change or be dropped: the paid amount belongs to it.
     * Says so and returns true; the cashier finishes it on SPLIT (or voids the card part).
     */
    private fun lockedByPartPayment(): Boolean {
        val paid = _sell.value.partPaid
        if (!paid.isPositive) return false
        _sell.update { it.copy(error = "This bill is partly paid (${paid.format()}). Finish it on SPLIT, or void the card part.") }
        return true
    }

    private fun cashMethod() = PaymentMethod(c.settings.cashPaymentMethodId, "Cash", isCash = true, terminal = null)
    private fun terminalMethod(mode: PayMode) = PaymentMethod(c.paymentRoles().forMode(mode), mode.label(), isCash = false, terminal = null)

    /** SPLIT on the pay row: save the cart (HARD RULE 4) and open Odoo's payment screen for it. */
    fun openSplit() = launchPay {
        val snap = prepareSnapshot(split = true) ?: return@launchPay
        _split.value = splitScreenFor(snap)
    }

    /** The payment screen for [snap], with the parts already paid (screen closed, or app restarted). */
    private suspend fun splitScreenFor(snap: SaleSnapshot): SplitUi {
        var split = PaymentSplit(snap.totals().totalIncl)
        val parts = LinkedHashMap<String, PartState>()
        c.journal.forOrder(snap.orderUuid).filter { it.state.isPaid }.forEach { a ->
            val uuid = "paid-${a.billingRef}"
            split = split.add(uuid, terminalMethod(a.mode), a.amount, a.mode).getOrElse { return@forEach }
            parts[uuid] = PartState.Paid(a)
        }
        val paid = parts.values.filterIsInstance<PartState.Paid>().fold(Paise.ZERO) { acc, p -> acc + p.attempt.amount }
        _sell.update { it.copy(partPaid = paid) }
        return SplitUi(snap.orderUuid, split, parts = parts)
    }

    /** "Finish" on the red bar: bring a partly paid bill back into the cart and open its payment screen. */
    fun resumePartlyPaid(orderUuid: String) = launchSafe {
        if (_sell.value.pay is PayUi.Working) return@launchSafe
        val snap = c.finalizer.load(orderUuid) ?: return@launchSafe
        // The bill on screen is not lost: it waits in the queue (when it has no payment of its own).
        val leaving = _sell.value.asParked()
        if (leaving.orderUuid != orderUuid && !leaving.isEmpty) {
            if (hasPayment(leaving.orderUuid)) {
                return@launchSafe _sell.update { it.copy(error = "Resolve the payment of the bill on screen first (yellow bar).") }
            }
            _parked.update { it + leaving }
            c.db.parkedBills().upsert(ParkedBillEntity(leaving.orderUuid, leaving.toJson(), leaving.createdAt, 0))
        }
        val customer = snap.customer()
        _sell.update {
            it.copy(
                billNumber = nextBillNumber(),
                phone = customer?.phone?.filter { ch -> ch.isDigit() || ch == '+' } ?: "", customerName = customer?.name ?: "",
                zzCash = null, zzLookup = ZzLookup.NONE, zzRewards = emptyList(), customerMatches = emptyList(), customerNotFound = false,
                orderUuid = snap.orderUuid, lines = snap.cartLines(), totals = snap.totals(), customer = customer,
                zzRedemption = snap.zzRedemption(), fiscalPosition = fiscalPositionsById[snap.fiscalPositionId],
                offerLines = snap.appliedOffers()?.lines.orEmpty(), offerCoupons = snap.appliedOffers()?.coupons.orEmpty(),
                offerClaimable = emptyList(),
                offers = snap.offers?.state() ?: OfferState(), offerPrograms = snap.appliedOffers()?.programs.orEmpty(),
                selectedLine = null, keypad = "", error = null,
            )
        }
        _split.value = splitScreenFor(snap)
    }

    fun splitAdd(kind: SplitKind) {
        val ui = _split.value ?: return
        if (kind == SplitKind.CASH && c.settings.cashPaymentMethodId == 0) {
            _split.value = ui.copy(message = "No cash method is set up for this till (Setup)."); return
        }
        val mode = when (kind) { SplitKind.CASH -> null; SplitKind.CARD -> PayMode.CARD; SplitKind.UPI -> PayMode.UPI }
        if (mode != null && c.paymentRoles().forMode(mode) == 0) {
            _split.value = ui.copy(message = "${mode.label()} is not set up for this till (Setup)."); return
        }
        val method = mode?.let { terminalMethod(it) } ?: cashMethod()
        _split.value = ui.split.add(UUID.randomUUID().toString(), method, mode = mode)
            .fold({ ui.copy(split = it, entry = "", message = null) }, { ui.copy(message = it.message) })
    }

    fun splitSelect(uuid: String) = _split.update { it?.copy(split = it.split.select(uuid), entry = "", message = null) }

    /** Numpad: typing replaces the selected line's amount, as on Odoo's payment screen. */
    fun splitKey(key: Char) = _split.update { ui ->
        ui ?: return@update null
        val line = ui.split.selected ?: return@update ui.copy(message = "Select a payment line first.")
        if (ui.isLocked(line.uuid)) return@update ui.copy(message = "This part is already charged.")
        val entry = when {
            key == '<' -> ui.entry.dropLast(1)
            key == '.' && '.' in ui.entry -> ui.entry
            key == '.' || key.isDigit() -> (ui.entry + key).let { e -> if (e.substringAfter('.', "").length > 2) ui.entry else e.take(9) }
            else -> ui.entry
        }
        val amount = Paise.parse(entry.ifEmpty { "0" }.removeSuffix(".")) ?: return@update ui.copy(entry = entry)
        ui.split.setAmount(line.uuid, amount).fold({ ui.copy(split = it, entry = entry, message = null) }, { ui.copy(entry = entry, message = it.message) })
    }

    fun splitQuick(rupees: Long) = _split.update { ui ->
        ui ?: return@update null
        val line = ui.split.selected
        if (line != null && ui.isLocked(line.uuid)) return@update ui.copy(message = "This part is already charged.")
        ui.split.quickAdd(rupees).fold({ ui.copy(split = it, entry = "", message = null) }, { ui.copy(message = it.message) })
    }

    fun splitRemove(uuid: String) = _split.update { ui ->
        ui ?: return@update null
        if (ui.isLocked(uuid)) ui.copy(message = "A charged part cannot be removed. Void the card part to undo it.")
        else ui.copy(split = ui.split.remove(uuid), entry = "", message = null)
    }

    /**
     * Charge one Card / UPI line on the E700: its own attempt and billing ref (HARD RULE 8), never
     * more than what is still due, one at a time (HARD RULE 3), persisted first (I4).
     */
    fun splitCharge(uuid: String) = launchSafe {
        val ui = _split.value ?: return@launchSafe
        val line = ui.split.lines.firstOrNull { it.uuid == uuid } ?: return@launchSafe
        val mode = line.mode ?: return@launchSafe
        if (ui.isLocked(uuid) || ui.parts.values.any { it is PartState.Charging } || _sell.value.pay is PayUi.Working) return@launchSafe
        if (!line.amount.isPositive) { _split.update { it?.copy(message = "Enter the amount for this part first.") }; return@launchSafe }
        _contractProblem.value?.let { why -> _split.update { it?.copy(message = why) }; return@launchSafe } // F2: fail closed
        // Charging is set before anything waits, so a second tap finds it and stops here.
        val seenPaid = _sell.value.partPaid
        _split.update { it?.copy(parts = it.parts + (uuid to PartState.Charging), message = null) }
        val snap = c.finalizer.load(ui.orderUuid) ?: return@launchSafe _split.update {
            it?.copy(parts = it.parts - uuid, message = "This bill was not saved. Close and press SPLIT again.")
        }
        _sell.update { it.copy(pay = PayUi.Working(mode.label(), "Complete ${line.amount.format()} on the terminal…")) }
        val outcome = try {
            c.payments.pay(ui.orderUuid, line.amount, mode, snap.customerPhone?.takeLast(10), orderTotal = ui.split.total, expectedPaid = seenPaid)
        } finally {
            _sell.update { it.copy(pay = PayUi.Idle) }
        }
        val state = when (outcome) {
            is PaymentOutcome.Paid -> PartState.Paid(outcome.attempt)
            is PaymentOutcome.NotPaid -> PartState.Failed(outcome.reason)
            is PaymentOutcome.NotSent -> PartState.Failed(outcome.reason)
            is PaymentOutcome.Unresolved -> PartState.Unconfirmed(outcome.attempt)
            is PaymentOutcome.Blocked -> PartState.Failed("Another part of this bill is not confirmed yet. Resolve it on the yellow bar first.")
            is PaymentOutcome.AlreadyPaid -> PartState.Failed("That is more than this bill still needs, or another part was just paid. Check the amounts.")
        }
        PosLog.i("SPLIT_PART", "order" to ui.orderUuid, "mode" to mode, "amount" to line.amount, "outcome" to outcome::class.simpleName)
        _split.update { it?.copy(parts = it.parts + (uuid to state)) }
        val paid = c.journal.forOrder(ui.orderUuid).filter { it.state.isPaid }.fold(Paise.ZERO) { acc, a -> acc + a.amount }
        _sell.update { it.copy(partPaid = paid) }
        refreshPartlyPaid()
    }

    /**
     * Validate: every Card/UPI part paid and the bill covered. Cash is the rest (change handed
     * back, not booked), and the order goes to Odoo with all its payment lines at once (I7).
     */
    fun splitValidate() = launchSafe {
        val ui = _split.value ?: return@launchSafe
        if (ui.busy) return@launchSafe // a second tap while the first is saving
        if (!ui.canValidate) {
            _split.update { it?.copy(message = if (!ui.terminalLinesPaid) "Charge every Card / UPI part first." else "The payments do not cover the bill yet.") }
            return@launchSafe
        }
        _split.update { it?.copy(busy = true, message = null) }
        val snap = c.finalizer.load(ui.orderUuid)
            ?: return@launchSafe _split.update { it?.copy(busy = false, message = "This bill was not saved. Close and press SPLIT again.") }
        _sell.update { it.copy(pay = PayUi.Working("SPLIT", "Saving order…")) }
        billPaid(snap.orderUuid) // covered: its cash part leaves no journal row (see payCash)
        val sale = try { c.finalizer.finalizeSplit(snap) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            PosLog.e("SPLIT_FINALIZE_FAILED", e, "order" to ui.orderUuid)
            _split.update { it?.copy(busy = false, message = "Could not save the order: ${friendly(e)}") }
            _sell.update { it.copy(pay = PayUi.Idle) }
            return@launchSafe
        }
        countOfferUse(sale)
        val attempts = ui.parts.values.filterIsInstance<PartState.Paid>().map { it.attempt }
        val summary = sale.payments.joinToString(" · ") { p -> "${p.mode?.label() ?: "CASH"} ${p.amount.format()}" }
        _split.value = null
        _sell.update { it.copy(partPaid = Paise.ZERO, pay = PayUi.Done(outcome = null, splitSummary = summary, cashChange = ui.split.change.takeIf { c -> c.isPositive })) }
        refreshPartlyPaid()
        printInBackground(sale, "SPLIT", attempts)
    }

    /** Back on the payment screen. A bill with a paid part stays locked, with the red bar to finish it. */
    fun closeSplit() {
        val ui = _split.value ?: return
        if (ui.parts.values.any { it is PartState.Charging }) return
        _split.value = null
        refreshPartlyPaid()
    }

    /** Validates session + totals, resolves the customer, and persists the cart snapshot BEFORE any charge. */
    private suspend fun prepareSnapshot(split: Boolean = false): SaleSnapshot? {
        val s = _sell.value
        if (s.pay is PayUi.Working) return null
        _contractProblem.value?.let { why -> _sell.update { it.copy(error = why) }; return null } // F2: fail closed
        val totals = s.totals ?: run { _sell.update { it.copy(error = "Prices not confirmed by Odoo yet") }; return null }
        val cfg = s.config ?: return null
        offlineRefusal(cfg)?.let { why -> _sell.update { it.copy(error = why) }; return null }
        if (!cfg.sessionOpen || cfg.currentSessionId == null) {
            _sell.update { it.copy(error = "No open Odoo session for ${cfg.name}. Open a session first.") }; return null
        }
        // If an earlier attempt for this order is unresolved or paid, keep ITS snapshot:
        // the coordinator will answer Blocked / AlreadyPaid and nothing new is charged.
        val earlier = c.journal.forOrder(s.orderUuid).any { it.state.blocksNewCharge || it.state.isPaid }
        if (earlier) return c.finalizer.load(s.orderUuid)?.let { snap ->
            if (split && !snap.split) snap.copy(split = true).also { c.finalizer.save(it) } else snap
        }
        // A pricelist rule may have reached a product after it was added to the cart.
        s.lines.firstNotNullOfOrNull { pricelistRefusal(it.product) }?.let { msg -> _sell.update { it.copy(error = msg) }; return null }
        val customer = s.customer ?: run {
            if (s.phone.isBlank()) return@run null
            if (!CustomerService.isPhoneQuery(s.phone)) return null.also {
                _sell.update { it.copy(error = "Pick the customer from the list under Customer, or clear the search.") }
            }
            // A typed number must belong to a saved customer: nobody is created silently at payment.
            val e164 = c.customers.normalizeIndianMobile(s.phone)
                ?: return null.also { _sell.update { it.copy(error = "The customer mobile is not valid. Fix or clear it under Customer.") } }
            val found = try { c.customers.findByPhone(e164) } catch (e: Exception) {
                PosLog.w("CUSTOMER_LOOKUP_FAILED", "phone" to Redact.phone(e164), "err" to e.message)
                return null.also { _sell.update { it.copy(error = "Could not find the customer in Odoo: ${friendly(e)}") } }
            }
            found ?: return null.also {
                _sell.update { it.copy(error = "Mobile ••••${e164.takeLast(4)} is not a saved customer. Save it under Customer, or clear the number.") }
            }
        }
        // The cart was priced under s.fiscalPosition. If resolving the customer now would
        // pick a different one, the totals we hold are wrong for this customer — re-price
        // first rather than send Odoo figures it will not reproduce.
        val expectedFpos = c.fiscalPositions.resolve(cfg, customer)
        if (expectedFpos != s.fiscalPosition?.id) {
            _sell.update {
                it.copy(
                    customer = customer,
                    fiscalPosition = fiscalPositionsById[expectedFpos],
                    error = "Customer changed the tax treatment of this cart. Prices have been refreshed — check the total and charge again.",
                )
            }
            reprice()
            return null
        }
        if (s.zzRedemption != null && s.zzCash?.partnerId != customer?.id) {
            _sell.update { it.copy(error = "Customer changed. ZZ Cash was removed; check the total and charge again.") }
            removeZzCash()
            return null
        }
        // ZZ Cash: points are booked after sync (confirm_coupon_programs), so they must be known now.
        var rules = s.zzRules
        if (customer != null && rules == null) {
            // No program for this config = no ZZ Cash to earn; a failed lookup blocks, as before.
            rules = runCatching {
                val program = zzProgram ?: zzProgramFallback(cfg).also { zzProgram = it }
                if (program == null) emptyList() else c.zzCash.rules(program.id)
            }.getOrNull()
                ?: run { _sell.update { it.copy(error = "Could not load ZZ Cash rules from Odoo. Check the connection and try again.") }; return null }
            _sell.update { it.copy(zzRules = rules) }
        }
        val earned = if (customer != null) LoyaltyPoints.earned(rules.orEmpty(), s.lines) else Paise.ZERO
        s.zzRedemption?.let { r ->
            // A paid bill's points are booked in Odoo after it syncs; until then Odoo's balance is too high.
            val pendingOnCard = c.db.loyalty().due(Long.MAX_VALUE).any { row ->
                runCatching { Json.parseToJsonElement(row.couponDataJson).jsonObject.containsKey(r.cardId.toString()) }.getOrDefault(false)
            }
            if (pendingOnCard) {
                _sell.update { it.copy(error = "This card's last ZZ Cash is still being booked in Odoo. Try the reward again in a minute, or pay without it.") }
                return null
            }
            // Odoo's own pre-payment check (validate_coupon_programs): the card, with what this
            // order earns, still covers the reward.
            val check = runCatching { c.zzCash.validateRedemption(r.cardId, earned, r.reward.points) }
                .getOrElse { err -> _sell.update { it.copy(error = friendly(err as? Exception ?: Exception(err))) }; return null }
            if (check != null) { _sell.update { it.copy(error = "ZZ Cash: $check") }; return null }
        }
        // A scanned coupon is checked by Odoo before the charge, as the web POS does.
        val couponSpend = s.offerLines.map { it.line }.filter { it.couponKey > 0 }.groupBy { it.couponKey }
            .mapValues { (_, ls) -> ls.fold(java.math.BigDecimal.ZERO) { a, l -> a + l.pointsCost }.negate() }
        if (couponSpend.isNotEmpty()) {
            val check = runCatching { c.offers.validate(couponSpend) }
                .getOrElse { err -> _sell.update { it.copy(error = friendly(err as? Exception ?: Exception(err))) }; return null }
            if (check != null) { _sell.update { it.copy(error = "Coupon: $check") }; return null }
        }
        val cardId = s.zzCash?.takeIf { it.partnerId == customer?.id }?.cardId
        val applied = AppliedOffers(s.offerLines, s.offerCoupons, s.offerPrograms)
        val snap = SaleSnapshot.of(s.orderUuid, cfg, s.lines, totals, customer, s.fiscalPosition?.id, s.zzRedemption, earned, cardId,
            loyaltyProgramId = zzProgram?.id, split = split, offers = applied, offerState = s.offers)
        c.finalizer.save(snap) // HARD RULE 4/7: the cart survives a crash mid-payment
        return snap
    }

    /** Payment refs with a bar action (Check status, Supervisor) running: one action per ref at a time. */
    private val _barBusy = MutableStateFlow<Set<String>>(emptySet())
    val barBusy: StateFlow<Set<String>> = _barBusy.asStateFlow()

    /** Refs whose status the coordinator is checking with the terminal right now. */
    val checking: StateFlow<Set<String>> get() = c.payments.checking

    private fun launchForRef(ref: String, block: suspend () -> Unit) = launchSafe {
        if (ref in _barBusy.value) return@launchSafe // a double tap does nothing
        _barBusy.update { it + ref }
        try { block() } finally { _barBusy.update { it - ref } }
    }

    /**
     * "Check status" on the bar. Runs in the background, no blocking dialog: the bar says
     * "Checking with the terminal…" meanwhile, and only a settled outcome is shown.
     */
    fun checkAgain(billingRef: String) = launchForRef(billingRef) {
        val outcome = c.payments.checkAgain(billingRef)
            ?: return@launchForRef _sell.update { it.copy(error = "Payment $billingRef is not on this till.") }
        if (outcome is PaymentOutcome.Unresolved) {
            _sell.update { it.copy(error = "Payment $billingRef: the terminal has not confirmed it yet. Check the terminal, or decide as supervisor.") }
        } else handleOutcome(outcome)
    }

    /**
     * Supervisor decision. Works at once even while a status check runs (the check stops), and a
     * payment that settled meanwhile keeps its real outcome, which is shown instead.
     */
    fun resolveManually(billingRef: String, paid: Boolean) = launchForRef(billingRef) {
        val outcome = c.payments.resolveManually(billingRef, paid, if (paid) "confirmed paid on terminal" else "confirmed not paid")
            ?: return@launchForRef _sell.update { it.copy(error = "Payment $billingRef is not on this till.") }
        handleOutcome(outcome)
    }

    private suspend fun handleOutcome(outcome: PaymentOutcome) {
        val paidAttempt = when (outcome) {
            is PaymentOutcome.Paid -> outcome.attempt
            is PaymentOutcome.AlreadyPaid -> outcome.attempt // paid earlier; UI never finished
            else -> null
        }
        if (paidAttempt != null) {
            billPaid(paidAttempt.orderUuid)
            val sale = c.finalizer.finalizeTerminal(paidAttempt)
            countOfferUse(sale)
            _sell.update { it.copy(pay = PayUi.Done(PaymentOutcome.Paid(paidAttempt), printed = if (sale == null) false else null,
                orderUuid = paidAttempt.orderUuid)) }
            sale?.let { printInBackground(it, paidAttempt.mode.label(), listOf(paidAttempt)) }
        } else {
            _sell.update { it.copy(pay = PayUi.Done(outcome, orderUuid = outcome.attempt?.orderUuid)) }
        }
        PosLog.i("PAY_UI_OUTCOME", "outcome" to outcome::class.simpleName)
    }

    /** The last bill printed (or tried), for "Print again" on the result screen. */
    private var lastBill: Triple<PaidSale, String, List<PaymentAttempt>>? = null

    /** Print [sale]'s bill without holding the cashier: the result screen shows "Printing…" meanwhile. */
    private fun printInBackground(sale: PaidSale, label: String, attempts: List<PaymentAttempt>, reprint: Boolean = false) {
        lastBill = Triple(sale, label, attempts)
        _sell.update { s -> (s.pay as? PayUi.Done)?.let { s.copy(pay = it.copy(printing = true, printed = null)) } ?: s }
        launchSafe {
            val ok = try { print(sale, label, attempts, reprint) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                PosLog.w("PRINT_FAILED", "err" to e.message); false
            }
            _sell.update { s -> (s.pay as? PayUi.Done)?.takeIf { it.printing }?.let { s.copy(pay = it.copy(printing = false, printed = ok)) } ?: s }
        }
    }

    /** "Print again" after a failed print (paper out): the same bill, marked REPRINT. */
    fun printAgain() {
        if ((_sell.value.pay as? PayUi.Done)?.printing == true) return // a second tap: one bill is printing
        lastBill?.let { (sale, label, attempts) -> printInBackground(sale, label, attempts, reprint = true) }
    }

    /** Company id → "street, street2, city zip", read with the catalogue, for the bill header. */
    private var companyAddress: Pair<Int, String>? = null

    /** Company id → its logo shrunk for the printer; read once, only when the Setup switch is on. */
    private var printerLogo: Pair<Int, String>? = null

    private suspend fun logoForBill(): String? {
        if (!c.settings.printLogo) return null
        val companyId = _sell.value.config?.companyId ?: return null
        printerLogo?.takeIf { it.first == companyId }?.let { return it.second }
        return runCatching { c.bills.companyLogo(companyId)?.let { BillLogo.forPrinter(it) } }
            .onFailure { PosLog.w("BILL_LOGO_FAILED", "err" to it.message) }.getOrNull()
            ?.also { printerLogo = companyId to it }
    }

    /**
     * Print the bill Odoo sends the customer (report "ZenZebra Bill"): read the order back from Odoo
     * right after sync and print it as Odoo has it. Only while the order waits in the outbox is the
     * till's own copy printed. Never blocks the sale (I8).
     */
    private suspend fun print(sale: PaidSale, label: String, attempts: List<PaymentAttempt>, reprint: Boolean = false): Boolean {
        val attempt = attempts.firstOrNull()
        // An order still in the outbox is not in Odoo: print the till's copy at once, no Odoo wait.
        // A synced one is read back, but a slow Odoo never holds the paid customer longer than this.
        val inOutbox = runCatching { c.db.outbox().contains(sale.orderUuid) }.getOrDefault(false)
        // Odoo's reply to the sync already holds the bill (no read-back); without it, or when the
        // reply lacks a name the till does not know, the bill is read from Odoo as before.
        val known = KnownNames(_sell.value.config?.name ?: c.settings.posConfigName, sale.customer?.name, sale.customer?.phone,
            taxNames, paymentMethodNames)
        val bill = if (inOutbox) null else runCatching {
            withTimeoutOrNull(BILL_READ_TIMEOUT_MS) {
                c.orderSync.syncReply(sale.orderUuid)
                    ?.let { reply -> runCatching { c.bills.billFromReply(sale.orderUuid, reply, known) }.getOrNull() }
                    ?: c.bills.bill(sale.orderUuid, knownPhone = sale.customer?.phone, knownTaxNames = taxNames)
            }
        }
            .onFailure { PosLog.w("BILL_READ_FAILED", "order" to sale.orderUuid, "err" to it.message) }.getOrNull()
        val eBill = bill?.accessToken?.let { runCatching { TicketQr.ticketUrl(BuildConfig.ODOO_BASE_URL, it) }.getOrNull() }
        val ref = attempt?.billingRef ?: sale.orderUuid.take(8).uppercase()
        val data = (bill?.let { BillReceipt.fromOdoo(it, eBill) }
            ?: BillReceipt.fromSale(sale, _sell.value.config?.name ?: "", ref))
            .copy(
                companyAddress = companyAddress?.takeIf { it.first == _sell.value.config?.companyId }?.second,
                terminals = attempts.map { TerminalSlip(it.mode.label(), it.details?.last4, it.details?.rrn ?: it.details?.transactionId, it.details?.authCode) },
                reprint = reprint,
                logoBase64 = logoForBill(),
            )
        PosLog.i("BILL_PRINT", "order" to sale.orderUuid, "from" to if (bill != null) "odoo" else "till", "lines" to data.items.size)
        val fmt = ReceiptFormatter(c.settings.printerWidth, billText)
        var r = c.gateway.print(fmt.format(data), attempt?.billingRef ?: sale.orderUuid.take(12), c.settings.printerWidth)
        if (r is PrintResult.Failed && data.logoBase64 != null) {
            // Pine's image printing is not proven on the E700 yet: never lose the bill over the logo.
            PosLog.w("PRINT_LOGO_FAILED", "reason" to r.reason)
            r = c.gateway.print(fmt.format(data.copy(logoBase64 = null)), attempt?.billingRef ?: sale.orderUuid.take(12), c.settings.printerWidth)
        }
        return when (r) {
            PrintResult.Printed -> true
            is PrintResult.Failed -> { PosLog.w("PRINT_FAILED", "reason" to r.reason); false }
        }
    }

    /** The config's ZZ Cash loyalty.program, found at catalogue load. Null = no ZZ Cash on this till. */
    private var zzProgram: ZzCashProgram? = null

    /** account.fiscal.position records this till may use, keyed by id. */
    private var fiscalPositionsById: Map<Int, FiscalPosition> = emptyMap()

    /** After a paid bill: the oldest waiting bill comes up, else an empty one. */
    fun newSale() {
        if (lockedByPartPayment()) return
        _split.value = null
        billPaid(_sell.value.orderUuid)
        val next = _parked.value.firstOrNull()
        if (next != null) _parked.update { it - next }
        startBill(next)
    }

    fun dismissPayResult() {
        // After a paid sale the cart must reset; otherwise (declined/not sent) keep cart for retry.
        // A result resolved from the bar for another order never clears the bill on screen.
        val done = _sell.value.pay as? PayUi.Done
        val forThisBill = done?.orderUuid == null || done.orderUuid == _sell.value.orderUuid
        if (done?.isPaid == true && forThisBill) newSale() else _sell.update { it.copy(pay = PayUi.Idle) }
    }

    // ------------------------------------------------------------------ open bills (the queue)

    /**
     * Bills waiting while another is on screen, oldest first (the web POS's "+" and order tabs). The
     * bill on screen is [_sell]. Every open bill is kept on the till (`parked_bill`) so a restart brings
     * the queue back; Odoo sees a bill only once it is paid.
     */
    private val _parked = MutableStateFlow<List<ParkedBill>>(emptyList())
    val parked: StateFlow<List<ParkedBill>> = _parked.asStateFlow()

    /** Bills paid on this till since it started: the open-bill save never writes them again. */
    private val paidBills = mutableSetOf<String>()

    /**
     * The moment a bill is paid it leaves the queue for good: a cash bill leaves no journal row that
     * would keep a restart from bringing it back as an open bill to sell again.
     */
    private fun billPaid(orderUuid: String) {
        paidBills += orderUuid
        _parked.update { list -> list.filterNot { it.orderUuid == orderUuid } }
        launchSafe { c.db.parkedBills().delete(orderUuid) }
    }

    /** When the bill on screen was first opened (kept with it while it waits). */
    private var activeCreatedAt = System.currentTimeMillis()

    /** A park or switch is running: a second tap waits for it. */
    private var switching = false

    private fun SellState.asParked(): ParkedBill = ParkedBill(orderUuid, billNumber, activeCreatedAt,
        lines.map { ParkedLine(it.uuid, it.product.id, it.qty, it.note) }, customer, offers, zzRedemption?.reward)

    /** The next free number for a bill without a customer; back to 1 when no other bill is open. */
    private fun nextBillNumber(): Int = (_parked.value.map { it.number }.maxOrNull() ?: 0) + 1

    /** Why the bill on screen cannot be left now, or null. Checked before anything waits. */
    private fun switchRefusal(): String? {
        val s = _sell.value
        return when {
            switching || s.pay != PayUi.Idle || payStarting || _split.value != null -> "Finish the payment on screen first."
            s.partPaid.isPositive -> "This bill is part-paid: finish it first (FINISH on the red bar)."
            s.customerBusy || checkingCode -> "Wait for Odoo's answer first."
            else -> null
        }
    }

    /** A bill with a paid or unconfirmed payment stays on screen until it is finished or resolved (I4, I5). */
    private suspend fun hasPayment(orderUuid: String): Boolean =
        c.journal.forOrder(orderUuid).any { it.state.isPaid || it.state.blocksNewCharge }

    /** "+": the bill on screen waits in the queue and an empty one opens. */
    fun newBill() = switchTo(null)

    /** A chip: the bill on screen waits and that one opens. */
    fun openBill(uuid: String) {
        if (uuid == _sell.value.orderUuid) return
        switchTo(_parked.value.firstOrNull { it.orderUuid == uuid } ?: return)
    }

    private fun switchTo(target: ParkedBill?) {
        switchRefusal()?.let { why -> _sell.update { it.copy(error = why) }; return }
        val leaving = _sell.value.asParked()
        if (target == null && leaving.isEmpty) return // an empty bill is already on screen
        if (target == null && _parked.value.size + 1 >= MAX_OPEN_BILLS) {
            _sell.update { it.copy(error = "At most $MAX_OPEN_BILLS open bills on a till. Pay or remove one first.") }; return
        }
        switching = true
        launchSafe {
            try {
                if (hasPayment(leaving.orderUuid)) {
                    return@launchSafe _sell.update { it.copy(error = "This bill has a payment to check (yellow bar). Resolve it before opening another bill.") }
                }
                if (leaving.isEmpty) c.db.parkedBills().delete(leaving.orderUuid) else {
                    _parked.update { it + leaving }
                    c.db.parkedBills().upsert(ParkedBillEntity(leaving.orderUuid, leaving.toJson(), leaving.createdAt, 0))
                }
                if (target != null) _parked.update { list -> list.filterNot { it.orderUuid == target.orderUuid } }
                PosLog.i("BILL_SWITCH", "parked" to _parked.value.size, "open" to (target?.orderUuid ?: "new"))
                startBill(target)
            } finally {
                switching = false
            }
        }
    }

    /** Long-press on a waiting bill's chip → Remove: it is dropped from the till (nothing was charged). */
    fun removeBill(uuid: String) {
        if (_parked.value.none { it.orderUuid == uuid }) return
        _parked.update { list -> list.filterNot { it.orderUuid == uuid } }
        launchSafe { c.db.parkedBills().delete(uuid) }
        PosLog.i("BILL_REMOVED", "parked" to _parked.value.size)
    }

    /**
     * Put [bill] on screen (null: an empty bill). Its lines come back from today's catalogue and are
     * priced again by Odoo; the customer's fiscal position and ZZ Cash are read again; a chosen ZZ Cash
     * reward is applied again once the card and prices are in (I2: nothing old is charged).
     */
    private fun startBill(bill: ParkedBill?) {
        phoneSearchJob?.cancel()
        repriceJob?.cancel()
        priceGeneration++
        activeCreatedAt = bill?.createdAt ?: System.currentTimeMillis()
        val restored = bill?.restore(_sell.value.products.associateBy { it.id }) { pricelistHold.holds(it) }
        val number = bill?.number ?: nextBillNumber()
        _sell.update {
            it.copy(
                orderUuid = bill?.orderUuid ?: UUID.randomUUID().toString(), billNumber = number,
                lines = restored?.lines.orEmpty(), totals = null, partPaid = Paise.ZERO,
                phone = bill?.customer?.phone?.filter { ch -> ch.isDigit() || ch == '+' } ?: "", customerName = bill?.customer?.name ?: "",
                customer = bill?.customer, customerMatches = emptyList(), customerHasMore = false, zzCash = null,
                customerNotFound = false, customerNote = null, customerNoteOk = false, customerBusy = false, zzLookup = ZzLookup.NONE,
                zzRewards = emptyList(), zzRedemption = null, selectedLine = null, keypad = "",
                fiscalPosition = fiscalPositionsById[it.config?.defaultFiscalPositionId],
                pay = PayUi.Idle, error = null,
                notice = restored?.dropped?.takeIf { d -> d > 0 }?.let { d -> "$d item(s) on this bill are no longer sold here and were taken off." },
                offerLines = emptyList(), offerCoupons = emptyList(), offerClaimable = emptyList(), offers = bill?.offers ?: OfferState(),
                offerPrograms = emptyList(),
            )
        }
        val customer = bill?.customer
        if (customer != null) {
            val zzJob = resolveCustomer(customer)
            bill.zzReward?.let { reapplyZzReward(bill.orderUuid, customer, it, zzJob) }
        }
        if (_sell.value.lines.isNotEmpty()) reprice()
    }

    /** After the card's balance and Odoo's prices are in, the ZZ Cash reward a waiting bill had goes back on. */
    private fun reapplyZzReward(uuid: String, customer: Customer, reward: ZzCashReward, zzJob: Job) = launchSafe {
        zzJob.join()
        val ready = withTimeoutOrNull(20_000) {
            _sell.first { it.orderUuid != uuid || (it.totals != null && !it.pricing && it.lines.all { l -> l.isPriced }) }
        }
        val s = _sell.value
        if (ready == null || s.orderUuid != uuid || s.customer?.id != customer.id || s.zzRedemption != null) return@launchSafe
        applyZzCash(reward)
    }

    /** The queue as it was before a restart; a bill whose payment started is left to the Reconciler. */
    private fun restoreOpenBills() = launchSafe {
        val rows = c.db.parkedBills().all()
        val bills = rows.mapNotNull { r -> ParkedBill.fromJson(r.billJson) ?: null.also { c.db.parkedBills().delete(r.orderUuid) } }
        val kept = bills.filter { b -> !hasPayment(b.orderUuid).also { paid -> if (paid) c.db.parkedBills().delete(b.orderUuid) } }
        if (kept.isEmpty()) return@launchSafe
        _parked.update { now -> (kept + now).distinctBy { it.orderUuid }.filterNot { it.orderUuid == _sell.value.orderUuid } }
        // The bill on screen started before the queue was read: give it a number of its own.
        _sell.update { s -> if (_parked.value.any { it.number == s.billNumber }) s.copy(billNumber = nextBillNumber()) else s }
        PosLog.i("BILLS_RESTORED", "count" to kept.size)
    }

    /** The bill on screen is kept on the till as it changes (a restart brings it back as a waiting bill). */
    @OptIn(FlowPreview::class)
    private fun keepOpenBillSaved() = launchSafe {
        _sell.map { it.asParked() }.distinctUntilChanged().debounce(400).collect { b ->
            if (b.isEmpty || b.orderUuid in paidBills) c.db.parkedBills().delete(b.orderUuid)
            else c.db.parkedBills().upsert(ParkedBillEntity(b.orderUuid, b.toJson(), b.createdAt, System.currentTimeMillis()))
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is OdooException.Network -> "No connection to Odoo. Check Wi-Fi."
        is OdooException.Auth -> "Odoo rejected the API key. Re-enter it in Setup."
        is OdooException.Server -> e.message ?: "Odoo error"
        else -> e.message ?: e::class.simpleName ?: "Error"
    }

    private fun PayMode.label() = when (this) { PayMode.CARD -> "CARD"; PayMode.UPI -> "UPI"; PayMode.BHARAT_QR -> "BHARAT QR" }

    /**
     * Last in the class on purpose: Kotlin runs property initializers and init blocks in source order,
     * so an init block above a property starts work that finds it still null. 0.5.0 had this one near
     * the top: `refreshPartlyPaid()` captured `_partlyPaid` before it was set, waited on Room, then
     * wrote to null (NullPointerException on every start; the part-paid bar was not restored).
     * `PosViewModelOrderTest` keeps it here.
     */
    init {
        if (_configured.value) loadCatalog()
        refreshPartlyPaid()
        startLiveSync()
        restoreOpenBills()
        keepOpenBillSaved()
    }

    companion object {
        /** Tallest keypad key (dp), as before the keypad became resizable. */
        const val MAX_KEY_DP = 48

        /** No answer from Odoo for this long counts as out of reach (live sync asks every 2 minutes). */
        private const val OFFLINE_AFTER_MS = 3 * 60_000L

        /** Saved prices are trusted this long after Odoo last confirmed its taxes. */
        private const val OFFLINE_TRUST_MS = 24 * 60 * 60_000L

        /** Products archived or taken out of the POS are looked for this often (Odoo's loader never sends them). */
        private const val SELLABLE_EVERY_MS = 15 * 60_000L

        /** The bill template's stamp is looked at this often (it is not in Odoo's loader). */
        private const val BILL_TEXT_EVERY_MS = 60 * 60_000L

        /** After Odoo's loader refused a call, the till reads Odoo the separate way this long. */
        private const val LOADER_OFF_MS = 60 * 60_000L

        /** Odoo's version is looked at this often (the fields only when it changed). */
        private const val CONTRACT_EVERY_MS = 24 * 60 * 60_000L

        /** Open bills on one till, the one on screen included. */
        const val MAX_OPEN_BILLS = 10

        /** Search waits this long after the last key before filtering the grid. */
        private const val SEARCH_DEBOUNCE_MS = 120L

        /** The customer search asks Odoo once typing pauses this long. */
        private const val CUSTOMER_SEARCH_DEBOUNCE_MS = 400L

        /** Taps closer together than this are priced once, together. */
        private const val REPRICE_DEBOUNCE_MS = 150L

        /** Longest the paid customer waits for Odoo's bill before the till prints its own copy. */
        private const val BILL_READ_TIMEOUT_MS = 8_000L

        /** How often the till asks Odoo what changed (config, products, menu, taxes, offers, bill text). */
        private const val LIVE_SYNC_MS = 120_000L

        /** Outside the sales day: the config only, this often. */
        private const val QUIET_SYNC_MS = 15 * 60_000L

        /** A first load this soon after boot waits up to [BOOT_SPREAD_MS] first. */
        private const val BOOT_WINDOW_MS = 5 * 60_000L
        private const val BOOT_SPREAD_MS = 20_000L

        /** The SALES screen reloads this often while it is open. */
        private const val SALES_REFRESH_MS = 60_000L

        /** Longest a sales refresh waits for Odoo before keeping the last figures. */
        private const val SALES_TIMEOUT_MS = 20_000L
    }
}
