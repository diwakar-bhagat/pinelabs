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
import com.bohemian.odoo.pos.odoo.LineNote
import com.bohemian.odoo.pos.odoo.MenuTree
import com.bohemian.odoo.pos.odoo.PosCategory
import com.bohemian.odoo.pos.odoo.PosNote
import com.bohemian.odoo.pos.odoo.FiscalPosition
import com.bohemian.odoo.pos.odoo.OdooException
import com.bohemian.odoo.pos.odoo.PaidSale
import com.bohemian.odoo.pos.odoo.PaymentLineData
import com.bohemian.odoo.pos.odoo.PaymentMethod
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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
    val terminalMethodId: Int = 0,
    val cashMethodId: Int = 0,
    val printerWidth: Int = 24,
    val useFake: Boolean = false,
    val kioskMode: Boolean = false,
    /** Company logo at the top of the bill (Pine image printing still to be proven on the E700). */
    val printLogo: Boolean = false,
    /** Local crash reports (newest first): count and the first lines of the latest. */
    val crashCount: Int = 0,
    val latestCrash: String? = null,
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
        zzRedemption == null && bal != null && total != null && ZzCashRules.eligible(bal, zzEarned, zzRewards, total).isNotEmpty()
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
        return copy(crashCount = reports.size, latestCrash = reports.firstOrNull()?.readLines()?.take(12)?.joinToString("\n"))
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
    val unresolved: StateFlow<List<com.bohemian.odoo.pos.data.PaymentAttemptEntity>> =
        c.db.attempts().observeUnresolved().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val envLabel = BuildConfig.ENV_LABEL + if (BuildConfig.PINE_SDK_PRESENT) "" else " · NO PINE SDK"
    val isDebug = BuildConfig.DEBUG

    init {
        if (_configured.value) loadCatalog()
        refreshPartlyPaid()
    }

    // ------------------------------------------------------------------ setup

    fun saveApiKey(key: String) = launchSetup {
        c.settings.odooApiKey = key.trim()
        val configs = c.catalog.configs() // also validates the key
        _setup.update { it.copy(apiKeyEntered = true, configs = configs) }
    }

    fun loadConfigs() = launchSetup { _setup.update { it.copy(configs = c.catalog.configs()) } }

    fun selectConfig(cfg: PosConfig) = launchSetup {
        val methods = c.catalog.paymentMethods(cfg.paymentMethodIds)
        _setup.update {
            it.copy(
                selectedConfig = cfg, methods = methods,
                cashMethodId = methods.firstOrNull { m -> m.isCash }?.id ?: 0,
                terminalMethodId = methods.firstOrNull { m -> !m.isCash && m.name.contains("E700", true) }?.id ?: 0,
            )
        }
    }

    fun setTerminalMethod(id: Int) = _setup.update { it.copy(terminalMethodId = id) }
    fun setCashMethod(id: Int) = _setup.update { it.copy(cashMethodId = id) }
    fun setPrinterWidth(w: Int) = _setup.update { it.copy(printerWidth = w) }
    fun setUseFake(v: Boolean) = _setup.update { it.copy(useFake = v) }

    fun finishSetup() {
        val s = _setup.value
        val cfg = s.selectedConfig ?: return _setup.update { it.copy(error = "Choose a POS") }
        if (s.terminalMethodId == 0) return _setup.update { it.copy(error = "Choose the Odoo payment method for E700 card/UPI") }
        c.settings.posConfigId = cfg.id
        c.settings.posConfigName = cfg.name
        c.settings.terminalPaymentMethodId = s.terminalMethodId
        c.settings.cashPaymentMethodId = s.cashMethodId
        c.settings.printerWidth = s.printerWidth
        c.settings.useFakeGateway = BuildConfig.DEBUG && s.useFake
        _configured.value = true
        loadCatalog()
    }

    fun reopenSetup() { _configured.value = false; loadConfigs() }

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
    val versionLabel = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    private fun launchSetup(block: suspend () -> Unit) = launchSafe {
        _setup.update { it.copy(busy = true, error = null) }
        try { block() } catch (e: Exception) { _setup.update { it.copy(error = friendly(e)) } }
        _setup.update { it.copy(busy = false) }
    }

    // ------------------------------------------------------------------ catalogue & cart

    private var catalogLoadedAt = 0L

    /** On resume: refresh the catalogue and photos when the last load is older than 30 minutes. */
    fun refreshIfStale() {
        if (_configured.value && System.currentTimeMillis() - catalogLoadedAt > 30 * 60_000L && !_sell.value.loading
            && _sell.value.pay == PayUi.Idle) loadCatalog()
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
        _sell.update { it.copy(loading = true, error = null) }
        showSavedCatalogFirst()
        try {
            val cfg = c.catalog.config(c.settings.posConfigId) ?: run {
                _sell.update { it.copy(config = null, loading = false, error =
                    "POS \"${c.settings.posConfigName}\" is archived, deleted or not open to this Odoo user. Open Setup and choose a POS.") }
                return@launchSafe
            }
            // No config in the state = no selling (prepareSnapshot), until Setup fixes it.
            cfg.paymentMethodProblem(c.settings.terminalPaymentMethodId, c.settings.cashPaymentMethodId)?.let { problem ->
                _sell.update { it.copy(config = null, loading = false, error = problem) }
                return@launchSafe
            }
            // Everything below depends only on the config: ask Odoo for it all at once, not in a row.
            val fposIds = (cfg.fiscalPositionIds + listOfNotNull(cfg.defaultFiscalPositionId)).distinct()
            val loaded = coroutineScope {
                // Mapping and sorting 2,400+ products runs off the screen thread.
                val productsJob = async(Dispatchers.Default) { c.catalog.products(cfg) }
                val fposJob = async { c.fiscalPositions.load(fposIds) }
                // ZZ Cash is optional: a failed lookup hides the balance, it never blocks a sale here.
                val zzJob = async {
                    val program = try { c.zzCash.program(cfg) } catch (e: Exception) {
                        PosLog.w("ZZ_CASH_PROGRAM_LOOKUP_FAILED", "err" to e.message); null
                    }
                    program to program?.let { p ->
                        runCatching { c.zzCash.rules(p.id) }.onFailure { PosLog.w("ZZ_RULES_LOAD_FAILED", "err" to it.message) }.getOrNull()
                    }
                }
                val categoriesJob = async { runCatching { c.catalog.categories(cfg) }.onFailure { PosLog.w("MENU_LOAD_FAILED", "err" to it.message) }.getOrDefault(emptyList()) }
                val notesJob = async { runCatching { c.catalog.notes(cfg.noteIds) }.getOrDefault(emptyList()) }
                val addressJob = async {
                    cfg.companyId?.let { id -> runCatching { c.bills.companyAddress(id) }.onFailure { PosLog.w("BILL_ADDRESS_FAILED", "err" to it.message) }.getOrNull() }
                }
                val products = productsJob.await()
                val taxIds = products.flatMap { it.taxIds }.toSet()
                val primeJob = async { c.fiscalPositions.primeTaxFiscalPositions(taxIds.toList()) }
                val taxNamesJob = async { runCatching { c.bills.taxNames(taxIds) }.getOrDefault(emptyMap()) }
                val positions = fposJob.await()
                primeJob.await()
                Loaded(products, positions, zzJob.await(), categoriesJob.await(), notesJob.await(), addressJob.await(), taxNamesJob.await())
            }
            val (products, positions, zz, categories, notes, address, names) = loaded
            fiscalPositionsById = positions
            zzProgram = zz.first
            taxNames = names
            cfg.companyId?.let { id ->
                // Keep the last address only for the same company; another company's never prints.
                if (address != null) companyAddress = id to address
                else if (companyAddress?.first != id) companyAddress = null
            }
            useCatalog(products, categories)
            // A fresh catalogue may carry new prices, taxes or rewards: drop what was cached from the last one.
            c.pricing.clearCache()
            rewardsCache = null
            completeCatalogAt = System.currentTimeMillis()
            _sell.update {
                it.copy(
                    config = cfg, products = products, loading = false, offlineCatalogSavedAt = null, catalogStaleReason = null,
                    zzRules = zz.second, categories = categories, notes = notes,
                    // The cashier's tab survives a reload; All when it is gone or none was chosen.
                    selectedCateg = it.selectedCateg?.takeIf { id -> categories.any { c -> c.id == id } },
                    fiscalPosition = fiscalPositionsById[cfg.defaultFiscalPositionId],
                ).regrid()
            }
            // Only a complete catalogue is cached, so a later failure never falls back to a partial one.
            launchSafe(Dispatchers.IO) {
                runCatching { c.catalogCache.save(cfg, products, categories = categories, notes = notes) }
                    .onFailure { PosLog.w("CATALOG_CACHE_SAVE_FAILED", "err" to it.message) }
            }
            catalogLoadedAt = System.currentTimeMillis()
            // Photos of the menu tabs in the background; the rest load as their tiles appear.
            launchSafe { c.productImages.prefetch(products.filter { it.categIds.isNotEmpty() }, products) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = when {
                e is OdooException.Network -> "No connection to Odoo"
                e is OdooException.Server && e.status >= 500 -> "Odoo is not answering (HTTP ${e.status})"
                e is CatalogIncomplete -> "Odoo sent ${e.got} of ${e.expected} products"
                else -> null
            }
            if (reason == null) { _sell.update { it.copy(loading = false, error = friendly(e)) }; return@launchSafe }
            PosLog.w("CATALOG_REFRESH_FAILED", "reason" to e::class.simpleName, "err" to e.message)
            // Phase 5: keep the last complete catalogue so the till is usable for browsing; selling
            // still needs Odoo pricing (I2), which fails with a friendly error until it is back.
            // Only the catalogue of the POS this terminal is set to: never another POS's session.
            val posId = c.settings.posConfigId
            val onScreen = _sell.value.products.isNotEmpty() && completeCatalogAt > 0 && _sell.value.config?.id == posId
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

    /** What one catalogue load brought back from Odoo. */
    private data class Loaded(
        val products: List<Product>,
        val positions: Map<Int, FiscalPosition>,
        val zz: Pair<ZzCashProgram?, List<LoyaltyRule>?>,
        val categories: List<PosCategory>,
        val notes: List<PosNote>,
        val address: String?,
        val taxNames: Map<Int, String>,
    )

    fun openSession() = launchSafe {
        if (_sell.value.loading) return@launchSafe // a second tap must not create a second pos.session
        _sell.update { it.copy(loading = true, error = null) }
        try {
            c.sessions.open(c.settings.posConfigId)
            val cfg = c.catalog.config(c.settings.posConfigId)
            _sell.update { it.copy(config = cfg ?: it.config, loading = false) }
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
            when (val r = c.sessions.close(sessionId, cfg.cashControl, counted, ui.notes.trim())) {
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
            while (true) {
                // Never competes with a payment for the network.
                if (_sell.value.pay !is PayUi.Working) loadSales()
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
    fun retryOdooRefund(ref: String) = launchSafe {
        val v = c.voidJournal.get(ref) ?: return@launchSafe
        _sell.update { it.copy(error = recordRefund(v)) }
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
        val exact = s.products.firstOrNull { it.barcode == s.query.trim() }
        if (exact != null) { add(exact); _sell.update { it.copy(query = "").regrid() } }
    }

    fun add(p: Product) {
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
    fun pickCustomer(customer: Customer) {
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
        // Re-resolve fiscal position for the picked customer's state.
        launchSafe {
            val fposId = c.fiscalPositions.resolve(_sell.value.config ?: return@launchSafe, customer)
            // Another customer may have been picked meanwhile: apply only to the one still attached.
            if (_sell.value.customer?.id != customer.id) return@launchSafe
            _sell.update { it.copy(fiscalPosition = fiscalPositionsById[fposId]) }
            reprice()
        }
        refreshZzCash(customer)
    }

    /**
     * Read the customer's ZZ Cash balance. Never blocks the sale: on failure the cashier
     * just sees no balance. The result is only applied if the same customer is still attached.
     */
    private fun refreshZzCash(customer: Customer) = launchSafe {
        _sell.update { s -> if (s.customer?.id == customer.id) s.copy(zzLookup = ZzLookup.LOADING) else s }
        val program = zzProgram ?: _sell.value.config?.let { cfg -> runCatching { c.zzCash.program(cfg) }.getOrNull() }?.also { zzProgram = it }
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
    fun retryZzCash() { _sell.value.customer?.let { refreshZzCash(it) } }

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
        if (reward.points > ZzCashRules.available(balance, s.zzEarned)) {
            _sell.update { it.copy(error = "Not enough ZZ Cash for ${reward.description}") }; return
        }
        val cartTotal = c.pricing.totals(s.lines)?.totalIncl
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
                val pricedRedemption = wanted?.takeIf { affordable(it, priced) }?.let { priceRedemption(it, priced, position) }
                val dropped = wanted != null && pricedRedemption == null
                _sell.update { s ->
                    when {
                        generation != priceGeneration -> s // a newer reprice owns the cart
                        // The cart changed while we were pricing: the newer reprice will answer.
                        s.lines.map { it.uuid to it.qty } != priced.map { it.uuid to it.qty } -> s
                        else -> s.copy(
                            lines = priced.map { p -> p.copy(note = s.lines.firstOrNull { it.uuid == p.uuid }?.note ?: p.note) },
                            zzRedemption = pricedRedemption,
                            totals = totalsWith(priced, pricedRedemption),
                            pricing = false,
                            error = if (dropped) "ZZ Cash reward removed: the cart no longer qualifies. Check the total." else s.error,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == priceGeneration) {
                    _sell.update { it.copy(pricing = false, error = "Could not get prices from Odoo: ${friendly(e)}") }
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

    /** Cart total plus the priced reward (a negative line). Null until every line is priced. */
    private fun totalsWith(lines: List<CartLine>, redemption: ZzCashRedemption?): CartTotals? {
        val base = c.pricing.totals(lines) ?: return null
        if (redemption == null) return base
        val incl = redemption.totalIncl ?: return null
        val excl = redemption.totalExcl ?: return null
        return CartTotals(base.totalIncl + incl, base.totalExcl + excl)
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
        _sell.update { it.copy(pay = PayUi.Working("CASH", "Saving order…")) }
        val sale = c.finalizer.finalize(snap, PaymentLineData(c.settings.cashPaymentMethodId, total, null, null, null))
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
    private fun terminalMethod(mode: PayMode) = PaymentMethod(c.settings.terminalPaymentMethodId, mode.label(), isCash = false, terminal = null)

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
        _sell.update {
            it.copy(
                orderUuid = snap.orderUuid, lines = snap.cartLines(), totals = snap.totals(), customer = snap.customer(),
                zzRedemption = snap.zzRedemption(), fiscalPosition = fiscalPositionsById[snap.fiscalPositionId],
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
        val snap = c.finalizer.load(ui.orderUuid)
            ?: return@launchSafe _split.update { it?.copy(message = "This bill was not saved. Close and press SPLIT again.") }
        _split.update { it?.copy(parts = it.parts + (uuid to PartState.Charging), message = null) }
        _sell.update { it.copy(pay = PayUi.Working(mode.label(), "Complete ${line.amount.format()} on the terminal…")) }
        val outcome = try {
            c.payments.pay(ui.orderUuid, line.amount, mode, snap.customerPhone?.takeLast(10), orderTotal = ui.split.total)
        } finally {
            _sell.update { it.copy(pay = PayUi.Idle) }
        }
        val state = when (outcome) {
            is PaymentOutcome.Paid -> PartState.Paid(outcome.attempt)
            is PaymentOutcome.NotPaid -> PartState.Failed(outcome.reason)
            is PaymentOutcome.NotSent -> PartState.Failed(outcome.reason)
            is PaymentOutcome.Unresolved -> PartState.Unconfirmed(outcome.attempt)
            is PaymentOutcome.Blocked -> PartState.Failed("Another part of this bill is not confirmed yet. Resolve it on the yellow bar first.")
            is PaymentOutcome.AlreadyPaid -> PartState.Failed("That is more than this bill still needs.")
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
        if (!ui.canValidate) {
            _split.update { it?.copy(message = if (!ui.terminalLinesPaid) "Charge every Card / UPI part first." else "The payments do not cover the bill yet.") }
            return@launchSafe
        }
        val snap = c.finalizer.load(ui.orderUuid)
            ?: return@launchSafe _split.update { it?.copy(message = "This bill was not saved. Close and press SPLIT again.") }
        _split.update { it?.copy(busy = true, message = null) }
        _sell.update { it.copy(pay = PayUi.Working("SPLIT", "Saving order…")) }
        val sale = try { c.finalizer.finalizeSplit(snap) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            PosLog.e("SPLIT_FINALIZE_FAILED", e, "order" to ui.orderUuid)
            _split.update { it?.copy(busy = false, message = "Could not save the order: ${friendly(e)}") }
            _sell.update { it.copy(pay = PayUi.Idle) }
            return@launchSafe
        }
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
        val totals = s.totals ?: run { _sell.update { it.copy(error = "Prices not confirmed by Odoo yet") }; return null }
        val cfg = s.config ?: return null
        if (!cfg.sessionOpen || cfg.currentSessionId == null) {
            _sell.update { it.copy(error = "No open Odoo session for ${cfg.name}. Open a session first.") }; return null
        }
        // If an earlier attempt for this order is unresolved or paid, keep ITS snapshot:
        // the coordinator will answer Blocked / AlreadyPaid and nothing new is charged.
        val earlier = c.journal.forOrder(s.orderUuid).any { it.state.blocksNewCharge || it.state.isPaid }
        if (earlier) return c.finalizer.load(s.orderUuid)?.let { snap ->
            if (split && !snap.split) snap.copy(split = true).also { c.finalizer.save(it) } else snap
        }
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
                val program = zzProgram ?: c.zzCash.program(cfg).also { zzProgram = it }
                if (program == null) emptyList() else c.zzCash.rules(program.id)
            }.getOrNull()
                ?: run { _sell.update { it.copy(error = "Could not load ZZ Cash rules from Odoo. Check the connection and try again.") }; return null }
            _sell.update { it.copy(zzRules = rules) }
        }
        val earned = if (customer != null) LoyaltyPoints.earned(rules.orEmpty(), s.lines) else Paise.ZERO
        s.zzRedemption?.let { r ->
            // Odoo's own pre-payment check (validate_coupon_programs): the card, with what this
            // order earns, still covers the reward.
            val check = runCatching { c.zzCash.validateRedemption(r.cardId, earned, r.reward.points) }
                .getOrElse { err -> _sell.update { it.copy(error = friendly(err as? Exception ?: Exception(err))) }; return null }
            if (check != null) { _sell.update { it.copy(error = "ZZ Cash: $check") }; return null }
        }
        val cardId = s.zzCash?.takeIf { it.partnerId == customer?.id }?.cardId
        val snap = SaleSnapshot.of(s.orderUuid, cfg, s.lines, totals, customer, s.fiscalPosition?.id, s.zzRedemption, earned, cardId,
            loyaltyProgramId = zzProgram?.id, split = split)
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
            val sale = c.finalizer.finalizeTerminal(paidAttempt)
            _sell.update { it.copy(pay = PayUi.Done(PaymentOutcome.Paid(paidAttempt), printed = if (sale == null) false else null)) }
            sale?.let { printInBackground(it, paidAttempt.mode.label(), listOf(paidAttempt)) }
        } else {
            _sell.update { it.copy(pay = PayUi.Done(outcome)) }
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
    fun printAgain() { lastBill?.let { (sale, label, attempts) -> printInBackground(sale, label, attempts, reprint = true) } }

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
        val bill = if (inOutbox) null else runCatching {
            withTimeoutOrNull(BILL_READ_TIMEOUT_MS) { c.bills.bill(sale.orderUuid, knownPhone = sale.customer?.phone, knownTaxNames = taxNames) }
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
        val fmt = ReceiptFormatter(c.settings.printerWidth)
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

    fun newSale() {
        if (lockedByPartPayment()) return
        phoneSearchJob?.cancel()
        _split.value = null
        _sell.update {
            it.copy(
                orderUuid = UUID.randomUUID().toString(), lines = emptyList(), totals = null, partPaid = Paise.ZERO,
                phone = "", customerName = "", customer = null, customerMatches = emptyList(), customerHasMore = false, zzCash = null,
                customerNotFound = false, customerNote = null, zzLookup = ZzLookup.NONE,
                zzRewards = emptyList(), zzRedemption = null, selectedLine = null, keypad = "",
                fiscalPosition = fiscalPositionsById[it.config?.defaultFiscalPositionId],
                pay = PayUi.Idle, error = null,
            )
        }
    }

    fun dismissPayResult() {
        // After a paid sale the cart must reset; otherwise (declined/not sent) keep cart for retry.
        val done = _sell.value.pay as? PayUi.Done
        if (done?.isPaid == true) newSale() else _sell.update { it.copy(pay = PayUi.Idle) }
    }

    private fun friendly(e: Exception): String = when (e) {
        is OdooException.Network -> "No connection to Odoo. Check Wi-Fi."
        is OdooException.Auth -> "Odoo rejected the API key. Re-enter it in Setup."
        is OdooException.Server -> e.message ?: "Odoo error"
        else -> e.message ?: e::class.simpleName ?: "Error"
    }

    private fun PayMode.label() = when (this) { PayMode.CARD -> "CARD"; PayMode.UPI -> "UPI"; PayMode.BHARAT_QR -> "BHARAT QR" }

    companion object {
        /** Tallest keypad key (dp), as before the keypad became resizable. */
        const val MAX_KEY_DP = 48

        /** Search waits this long after the last key before filtering the grid. */
        private const val SEARCH_DEBOUNCE_MS = 120L

        /** The customer search asks Odoo once typing pauses this long. */
        private const val CUSTOMER_SEARCH_DEBOUNCE_MS = 300L

        /** Taps closer together than this are priced once, together. */
        private const val REPRICE_DEBOUNCE_MS = 150L

        /** Longest the paid customer waits for Odoo's bill before the till prints its own copy. */
        private const val BILL_READ_TIMEOUT_MS = 8_000L

        /** The SALES screen reloads this often while it is open. */
        private const val SALES_REFRESH_MS = 60_000L

        /** Longest a sales refresh waits for Odoo before keeping the last figures. */
        private const val SALES_TIMEOUT_MS = 20_000L
    }
}
