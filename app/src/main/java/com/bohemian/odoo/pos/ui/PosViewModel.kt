package com.bohemian.odoo.pos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bohemian.odoo.pos.BuildConfig
import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.core.PosLog
import com.bohemian.odoo.pos.core.Redact
import com.bohemian.odoo.pos.receipt.TicketQr
import com.bohemian.odoo.pos.di.AppContainer
import com.bohemian.odoo.pos.odoo.CartLine
import com.bohemian.odoo.pos.odoo.CartTotals
import com.bohemian.odoo.pos.odoo.Customer
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
import com.bohemian.odoo.pos.payment.PaymentOutcome
import com.bohemian.odoo.pos.payment.VoidOutcome
import com.bohemian.odoo.pos.pine.PayMode
import com.bohemian.odoo.pos.pine.PrintResult
import com.bohemian.odoo.pos.receipt.ReceiptData
import com.bohemian.odoo.pos.receipt.ReceiptFormatter
import com.bohemian.odoo.pos.receipt.ReceiptItem
import com.bohemian.odoo.pos.odoo.ClosingSummary
import com.bohemian.odoo.pos.odoo.CloseResult
import com.bohemian.odoo.pos.odoo.RefundResult
import com.bohemian.odoo.pos.receipt.ZReportCash
import com.bohemian.odoo.pos.receipt.ZReportData
import com.bohemian.odoo.pos.receipt.ZReportFormatter
import com.bohemian.odoo.pos.receipt.ZReportMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    /** Local crash reports (newest first): count and the first lines of the latest. */
    val crashCount: Int = 0,
    val latestCrash: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

sealed interface PayUi {
    data object Idle : PayUi
    data class Working(val mode: String, val message: String) : PayUi
    data class Done(
        val outcome: PaymentOutcome?,          // null for cash
        val paidByCash: Boolean = false,
        val cashChange: Paise? = null,
        val printed: Boolean? = null,
    ) : PayUi {
        val isPaid: Boolean get() = paidByCash || outcome is PaymentOutcome.Paid
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
    val phone: String = "",
    val customerName: String = "",
    /** Resolved customer. Set by [PosViewModel.attachCustomer] or [PosViewModel.pickCustomer]. */
    val customer: Customer? = null,
    /** Matches returned by partial phone search (last N digits). */
    val customerMatches: List<Customer> = emptyList(),
    /** ZZ Cash balance of [customer], read live from Odoo. Null while loading or if none. */
    val zzCash: ZzCashBalance? = null,
    /** Rewards the card can pay for, from loyalty.reward. Empty until the balance loads. */
    val zzRewards: List<ZzCashReward> = emptyList(),
    /** The ZZ Cash reward applied to this sale, priced by Odoo once the cart is priced. */
    val zzRedemption: ZzCashRedemption? = null,
    /** Menu tabs (pos.category) and note tags (pos.note) from Odoo. */
    val categories: List<PosCategory> = emptyList(),
    val notes: List<PosNote> = emptyList(),
    /** Selected tab; null = whole catalogue. Set to the first tab when the menu loads. */
    val selectedCateg: Int? = null,
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
    /** Set when Odoo was unreachable and the catalogue comes from the device cache (Phase 5). */
    val offlineCatalogSavedAt: Long? = null,
) {
    /** ZZ Cash this cart earns, as the web POS shows it ("34.6 +1.2 = 35.8"). */
    val zzEarned: Paise get() = zzRules?.let { LoyaltyPoints.earned(it, lines) } ?: Paise.ZERO

    val menu: MenuTree get() = MenuTree(categories)

    /** The selected tab (and everything under it), or the search results across the whole catalogue. */
    val filtered: List<Product>
        get() = if (query.isBlank()) menu.filter(products, selectedCateg).take(MAX_TILES)
        else query.trim().lowercase().let { q ->
            products.filter { it.name.lowercase().contains(q) || it.barcode == q || it.defaultCode?.lowercase() == q }.take(MAX_TILES)
        }

    /** Units of each product in the cart, for the badge on its tile. */
    val qtyInCart: Map<Int, Int> get() = lines.groupBy { it.product.id }.mapValues { (_, l) -> l.sumOf { it.qty } }

    private companion object { const val MAX_TILES = 200 }
}

class PosViewModel(private val c: AppContainer) : ViewModel() {

    private val _configured = MutableStateFlow(c.settings.isConfigured)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    private val _setup = MutableStateFlow(
        SetupState(
            apiKeyEntered = !c.settings.odooApiKey.isNullOrBlank(),
            printerWidth = c.settings.printerWidth,
            useFake = c.settings.useFakeGateway,
            kioskMode = c.settings.kioskMode,
        ).withCrashes()
    )

    private fun SetupState.withCrashes(): SetupState {
        val reports = c.crashLog.reports()
        return copy(crashCount = reports.size, latestCrash = reports.firstOrNull()?.readLines()?.take(12)?.joinToString("\n"))
    }

    /** Takes effect at once; MainActivity applies lock task on resume, Setup applies it on toggle. */
    fun setKioskMode(v: Boolean) {
        c.settings.kioskMode = v
        _setup.update { it.copy(kioskMode = v) }
    }

    fun clearCrashReports() {
        c.crashLog.clear()
        _setup.update { it.withCrashes() }
    }
    val setup: StateFlow<SetupState> = _setup.asStateFlow()

    private val _sell = MutableStateFlow(SellState())
    val sell: StateFlow<SellState> = _sell.asStateFlow()

    val outboxCount: StateFlow<Int> = c.db.outbox().observeCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val unresolved: StateFlow<List<com.bohemian.odoo.pos.data.PaymentAttemptEntity>> =
        c.db.attempts().observeUnresolved().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val envLabel = BuildConfig.ENV_LABEL + if (BuildConfig.PINE_SDK_PRESENT) "" else " · NO PINE SDK"
    val isDebug = BuildConfig.DEBUG

    init { if (_configured.value) loadCatalog() }

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

    private fun launchSetup(block: suspend () -> Unit) = viewModelScope.launch {
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

    fun loadCatalog() = viewModelScope.launch {
        catalogLoadedAt = System.currentTimeMillis() // also stops refreshIfStale from loading twice
        _sell.update { it.copy(loading = true, error = null) }
        try {
            val cfg = c.catalog.config(c.settings.posConfigId) ?: run {
                _sell.update { it.copy(config = null, loading = false, error =
                    "POS \"${c.settings.posConfigName}\" is archived, deleted or not open to this Odoo user. Open Setup and choose a POS.") }
                return@launch
            }
            // No config in the state = no selling (prepareSnapshot), until Setup fixes it.
            cfg.paymentMethodProblem(c.settings.terminalPaymentMethodId, c.settings.cashPaymentMethodId)?.let { problem ->
                _sell.update { it.copy(config = null, loading = false, error = problem) }
                return@launch
            }
            val products = c.catalog.products()
            val fposIds = (cfg.fiscalPositionIds + listOfNotNull(cfg.defaultFiscalPositionId)).distinct()
            fiscalPositionsById = c.fiscalPositions.load(fposIds)
            c.fiscalPositions.primeTaxFiscalPositions(products.flatMap { it.taxIds })
            // ZZ Cash is optional: a failed lookup hides the balance, it never blocks a sale here.
            zzProgram = try { c.zzCash.program(cfg) } catch (e: Exception) {
                PosLog.w("ZZ_CASH_PROGRAM_LOOKUP_FAILED", "err" to e.message); null
            }
            val rules = zzProgram?.let { p ->
                runCatching { c.zzCash.rules(p.id) }.onFailure { PosLog.w("ZZ_RULES_LOAD_FAILED", "err" to it.message) }.getOrNull()
            }
            val categories = runCatching { c.catalog.categories() }.onFailure { PosLog.w("MENU_LOAD_FAILED", "err" to it.message) }.getOrDefault(emptyList())
            val notes = runCatching { c.catalog.notes(cfg.noteIds) }.getOrDefault(emptyList())
            _sell.update {
                it.copy(
                    config = cfg, products = products, loading = false, offlineCatalogSavedAt = null,
                    zzRules = rules, categories = categories, notes = notes,
                    selectedCateg = it.selectedCateg?.takeIf { id -> categories.any { c -> c.id == id } }
                        ?: MenuTree(categories).roots.firstOrNull()?.id,
                    fiscalPosition = fiscalPositionsById[cfg.defaultFiscalPositionId],
                )
            }
            runCatching { c.catalogCache.save(cfg, products, categories = categories, notes = notes) }
                .onFailure { PosLog.w("CATALOG_CACHE_SAVE_FAILED", "err" to it.message) }
            catalogLoadedAt = System.currentTimeMillis()
            // Photos of the menu tabs in the background; the rest load as their tiles appear.
            viewModelScope.launch { c.productImages.prefetch(products.filter { it.categIds.isNotEmpty() }, products) }
        } catch (e: OdooException.Network) {
            // Phase 5: show the last catalogue so the till is usable for browsing; selling
            // still needs Odoo pricing (I2), which fails with a friendly error until it is back.
            val cached = c.catalogCache.load()
            _sell.update {
                if (cached == null) it.copy(loading = false, error = friendly(e))
                else it.copy(config = cached.config, products = cached.products, loading = false,
                    categories = cached.categories, notes = cached.notes,
                    selectedCateg = it.selectedCateg ?: MenuTree(cached.categories).roots.firstOrNull()?.id,
                    offlineCatalogSavedAt = cached.savedAt, error = null)
            }
        } catch (e: Exception) {
            _sell.update { it.copy(loading = false, error = friendly(e)) }
        }
    }

    fun openSession() = viewModelScope.launch {
        if (_sell.value.loading) return@launch // a second tap must not create a second pos.session
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
    fun startClose() = viewModelScope.launch {
        if (_closing.value != null) return@launch // dialog already open
        val sessionId = _sell.value.config?.currentSessionId ?: return@launch
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

    fun confirmClose() = viewModelScope.launch {
        val ui = _closing.value ?: return@launch
        // One close at a time: runs on Main.immediate, so this check and busy=true below happen
        // before the first suspension and a second tap sees busy. Closed is final.
        if (ui.busy || ui.closed) return@launch
        val cfg = _sell.value.config ?: return@launch
        val sessionId = cfg.currentSessionId ?: return@launch
        val counted = if (cfg.cashControl) {
            Paise.parse(ui.counted) ?: return@launch _closing.update { it?.copy(error = "Enter the cash counted in the drawer") }
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
    fun printZReport() = viewModelScope.launch {
        val ui = _closing.value ?: return@launch
        val summary = ui.summary ?: return@launch
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

    // ------------------------------------------------------------------ void (Phase 4b)

    val openVoids: StateFlow<List<com.bohemian.odoo.pos.data.VoidAttemptEntity>> =
        c.db.voids().observeOpen().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _voiding = MutableStateFlow<VoidUi?>(null)
    val voiding: StateFlow<VoidUi?> = _voiding.asStateFlow()

    /** Today's card sales (a void only works before the terminal settles the batch). */
    fun startVoid() = viewModelScope.launch {
        _voiding.value = VoidUi(busy = true)
        val since = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        _voiding.update { it?.copy(sales = c.journal.paidCardSince(since), busy = false) }
    }

    fun chooseVoid(sale: PaymentAttempt?) = _voiding.update { it?.copy(confirm = sale, message = null) }

    fun confirmVoid() = viewModelScope.launch {
        val sale = _voiding.value?.confirm ?: return@launch
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

    fun resolveVoid(ref: String, voided: Boolean) = viewModelScope.launch {
        runCatching { c.payments.resolveVoid(ref, voided) }.onFailure { _sell.update { s -> s.copy(error = it.message) } }
    }

    fun confirmOdooRefund(ref: String) = viewModelScope.launch {
        runCatching { c.payments.confirmOdooRefund(ref, "manual: supervisor recorded the refund in Odoo") }
            .onFailure { _sell.update { s -> s.copy(error = it.message) } }
    }

    /** Red-bar retry for a voided sale whose Odoo refund was not created yet. */
    fun retryOdooRefund(ref: String) = viewModelScope.launch {
        val v = c.voidJournal.get(ref) ?: return@launch
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

    fun setQuery(q: String) = _sell.update { it.copy(query = q) }

    /** Keyboard-wedge scanners type the barcode then Enter. */
    fun submitQuery() {
        val s = _sell.value
        val exact = s.products.firstOrNull { it.barcode == s.query.trim() }
        if (exact != null) { add(exact); _sell.update { it.copy(query = "") } }
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

    /** Tap a tab: select it, or clear the filter when it is already selected (Odoo web POS). */
    fun selectCategory(id: Int) = _sell.update { it.copy(selectedCateg = if (it.selectedCateg == id) null else id, query = "") }

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

    fun setPhone(v: String) {
        val cleaned = v.filter { ch -> ch.isDigit() || ch == '+' }.take(14)
        _sell.update { it.copy(phone = cleaned) }
        searchCustomerByPhone(cleaned)
    }

    fun setCustomerName(v: String) = _sell.update { it.copy(customerName = v.take(60)) }

    /** Pick a customer from the search results (cashier tapped a match). */
    fun pickCustomer(customer: Customer) {
        phoneSearchJob?.cancel() // a late search result must not reopen the list
        val changed = _sell.value.customer?.id != customer.id
        _sell.update { it.copy(
            customer = customer,
            customerName = customer.name,
            phone = customer.phone?.filter { it.isDigit() || it == '+' } ?: _sell.value.phone,
            customerMatches = emptyList(),
            // A different customer has a different card: the old reward must not carry over.
            zzRedemption = if (changed) null else it.zzRedemption,
            totals = if (changed) null else it.totals,
        ) }
        // Re-resolve fiscal position for the picked customer's state.
        viewModelScope.launch {
            val fposId = c.fiscalPositions.resolve(_sell.value.config ?: return@launch, customer)
            // Another customer may have been picked meanwhile: apply only to the one still attached.
            if (_sell.value.customer?.id != customer.id) return@launch
            _sell.update { it.copy(fiscalPosition = fiscalPositionsById[fposId]) }
            reprice()
        }
        refreshZzCash(customer)
    }

    /**
     * Read the customer's ZZ Cash balance. Never blocks the sale: on failure the cashier
     * just sees no balance. The result is only applied if the same customer is still attached.
     */
    private fun refreshZzCash(customer: Customer) = viewModelScope.launch {
        val program = zzProgram ?: return@launch
        val (balance, rewards) = try {
            val b = c.zzCash.balance(customer.id, program.id)
            b to (if (b == null) emptyList() else c.zzCash.rewards(program.id))
        } catch (e: Exception) {
            PosLog.w("ZZ_CASH_LOOKUP_FAILED", "partner" to customer.id, "err" to e.message)
            null to emptyList()
        }
        _sell.update { s ->
            if (s.customer?.id == customer.id) s.copy(zzCash = balance, zzRewards = rewards) else s
        }
    }

    /**
     * Apply a ZZ Cash reward to this sale. It is priced by Odoo on the next reprice, and the
     * payment total includes it. Refused when the cart has mixed tax rates or the card cannot pay.
     */
    fun applyZzCash(reward: ZzCashReward) {
        val s = _sell.value
        if (s.pay is PayUi.Working) return
        val balance = s.zzCash?.takeIf { s.customer?.id == it.partnerId }
        if (balance == null) {
            _sell.update { it.copy(error = "ZZ Cash balance not loaded for this customer") }; return
        }
        if (s.lines.isEmpty() || s.lines.any { !it.isPriced }) {
            _sell.update { it.copy(error = "Wait for Odoo prices before applying ZZ Cash") }; return
        }
        if (reward.points > balance.redeemable) {
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
        if (_sell.value.pay is PayUi.Working) return
        _sell.update { it.copy(zzRedemption = null, totals = null) }
        reprice()
    }

    /** Clear the attached customer, search results, ZZ Cash balance and any reward. */
    fun clearCustomer() {
        phoneSearchJob?.cancel()
        _sell.update {
            it.copy(customer = null, customerMatches = emptyList(), phone = "", customerName = "",
                zzCash = null, zzRewards = emptyList(), zzRedemption = null)
        }
    }

    private var phoneSearchJob: kotlinx.coroutines.Job? = null

    /**
     * Fire a background search when the cashier types ≥4 digits. Debounced: only the
     * last keystroke's search runs. Clears matches when input is too short.
     */
    private fun searchCustomerByPhone(digits: String) {
        val clean = digits.filter { it.isDigit() }
        phoneSearchJob?.cancel() // any edit cancels the search in flight
        if (clean.length < 4) {
            _sell.update { it.copy(customerMatches = emptyList()) }
            return
        }
        phoneSearchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(300) // debounce keystrokes
            try {
                val matches = c.customers.searchByPhoneSuffix(clean)
                // Only for what is typed now: a slow answer for older digits is dropped.
                _sell.update { if (it.phone.filter { ch -> ch.isDigit() } == clean) it.copy(customerMatches = matches) else it }
            } catch (_: Exception) {
                // search failure is silent — the cashier can still type the full number
            }
        }
    }

    private fun editCart(f: (List<CartLine>) -> List<CartLine>) {
        if (_sell.value.pay is PayUi.Working) return // cart frozen while paying
        _sell.update { it.copy(lines = f(it.lines), totals = null) }
        reprice()
    }

    private fun reprice() = viewModelScope.launch {
        _sell.update { it.copy(pricing = true, error = null) }
        try {
            val position = _sell.value.fiscalPosition
            val priced = c.pricing.priceAll(_sell.value.lines, position)
            val wanted = _sell.value.zzRedemption
            val pricedRedemption = wanted?.let { priceRedemption(it, priced, position) }
            val dropped = wanted != null && pricedRedemption == null
            _sell.update { s ->
                // Ignore if the cart changed while we were pricing.
                if (s.lines.map { it.uuid to it.qty } != priced.map { it.uuid to it.qty }) s
                else s.copy(
                    lines = priced.map { p -> p.copy(note = s.lines.firstOrNull { it.uuid == p.uuid }?.note ?: p.note) },
                    zzRedemption = pricedRedemption,
                    totals = totalsWith(priced, pricedRedemption),
                    pricing = false,
                    error = if (dropped) "ZZ Cash reward removed: the cart no longer qualifies. Check the total." else s.error,
                )
            }
        } catch (e: Exception) {
            _sell.update { it.copy(pricing = false, error = "Could not get prices from Odoo: ${friendly(e)}") }
        }
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

    private fun launchPay(block: suspend () -> Unit) = viewModelScope.launch {
        if (payStarting || _sell.value.pay is PayUi.Working) return@launch
        payStarting = true
        try { block() } finally { payStarting = false }
    }

    fun payWithTerminal(mode: PayMode) = launchPay {
        val snap = prepareSnapshot() ?: return@launchPay
        _sell.update { it.copy(pay = PayUi.Working(mode.label(), "Complete the payment on the terminal…")) }
        val outcome = c.payments.pay(snap.orderUuid, snap.totals().totalIncl, mode, snap.customerPhone?.takeLast(10))
        handleOutcome(outcome)
    }

    fun payCash(tendered: Paise?) = launchPay {
        if (c.settings.cashPaymentMethodId == 0) { _sell.update { it.copy(error = "No cash method configured") }; return@launchPay }
        val snap = prepareSnapshot() ?: return@launchPay
        val total = snap.totals().totalIncl
        val change = tendered?.takeIf { it >= total }?.minus(total)
        _sell.update { it.copy(pay = PayUi.Working("CASH", "Saving order…")) }
        val sale = c.finalizer.finalize(snap, PaymentLineData(c.settings.cashPaymentMethodId, total, null, null, null))
        val printed = print(sale, "CASH", null)
        _sell.update { it.copy(pay = PayUi.Done(outcome = null, paidByCash = true, cashChange = change, printed = printed)) }
    }

    /** Validates session + totals, resolves the customer, and persists the cart snapshot BEFORE any charge. */
    private suspend fun prepareSnapshot(): SaleSnapshot? {
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
        if (earlier) return c.finalizer.load(s.orderUuid)
        val customer = s.customer ?: resolveCustomer()
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
        s.zzRedemption?.let { r ->
            // Odoo's own pre-payment check (validate_coupon_programs): the card still covers the reward.
            val check = runCatching { c.zzCash.validateRedemption(r.cardId, r.reward.points) }
                .getOrElse { err -> _sell.update { it.copy(error = friendly(err as? Exception ?: Exception(err))) }; return null }
            if (check != null) { _sell.update { it.copy(error = "ZZ Cash: $check") }; return null }
        }
        val earned = if (customer != null) LoyaltyPoints.earned(rules.orEmpty(), s.lines) else Paise.ZERO
        val cardId = s.zzCash?.takeIf { it.partnerId == customer?.id }?.cardId
        val snap = SaleSnapshot.of(s.orderUuid, cfg, s.lines, totals, customer, s.fiscalPosition?.id, s.zzRedemption, earned, cardId,
            loyaltyProgramId = zzProgram?.id)
        c.finalizer.save(snap) // HARD RULE 4/7: the cart survives a crash mid-payment
        return snap
    }

    fun checkAgain(billingRef: String) = viewModelScope.launch {
        _sell.update { it.copy(pay = PayUi.Working("STATUS", "Checking payment status with the terminal…")) }
        handleOutcome(c.payments.checkAgain(billingRef))
    }

    fun resolveManually(billingRef: String, paid: Boolean) = viewModelScope.launch {
        handleOutcome(c.payments.resolveManually(billingRef, paid, if (paid) "confirmed paid on terminal" else "confirmed not paid"))
    }

    private suspend fun handleOutcome(outcome: PaymentOutcome) {
        val paidAttempt = when (outcome) {
            is PaymentOutcome.Paid -> outcome.attempt
            is PaymentOutcome.AlreadyPaid -> outcome.attempt // paid earlier; UI never finished
            else -> null
        }
        if (paidAttempt != null) {
            val sale = c.finalizer.finalizeTerminal(paidAttempt)
            val printed = sale?.let { print(it, paidAttempt.mode.label(), paidAttempt) } ?: false
            _sell.update { it.copy(pay = PayUi.Done(PaymentOutcome.Paid(paidAttempt), printed = printed)) }
        } else {
            _sell.update { it.copy(pay = PayUi.Done(outcome)) }
        }
        PosLog.i("PAY_UI_OUTCOME", "outcome" to outcome::class.simpleName)
    }

    /** The e-bill link for this sale, or null if Odoo has no token for it yet. Never blocks printing. */
    private suspend fun eBillQr(uuid: String): String? = try {
        c.orderSync.accessTokenFor(uuid)?.let { TicketQr.ticketUrl(BuildConfig.ODOO_BASE_URL, it) }
    } catch (e: Exception) {
        PosLog.w("EBILL_QR_SKIPPED", "order" to uuid, "err" to e.message)
        null
    }

    private suspend fun print(sale: PaidSale, label: String, attempt: PaymentAttempt?, reprint: Boolean = false): Boolean {
        val cfg = _sell.value.config
        val fmt = ReceiptFormatter(c.settings.printerWidth)
        val qr = eBillQr(sale.orderUuid)
        val data = ReceiptData(
            storeName = cfg?.companyName?.ifBlank { null } ?: "ZenZebra",
            storeLines = listOfNotNull(cfg?.name),
            orderRef = attempt?.billingRef ?: sale.orderUuid.take(8).uppercase(),
            dateTime = SimpleDateFormat("dd-MMM-yyyy HH:mm", Locale("en", "IN")).format(Date(sale.paidAtMillis)),
            items = sale.lines.map { ReceiptItem(it.product.name, it.qty, it.product.price, it.totalIncl!!) },
            subtotalExcl = sale.totals.totalExcl, tax = sale.totals.tax, total = sale.totals.totalIncl,
            paymentLabel = label,
            paymentRef = attempt?.details?.rrn ?: attempt?.details?.transactionId,
            authCode = attempt?.details?.authCode,
            cardLast4 = attempt?.details?.last4,
            customerName = sale.customer?.name,
            footer = listOf("Thank you for shopping!", "Try it. Love it. ZenZebra."),
            reprint = reprint,
            eBillUrl = qr,
        )
        return when (val r = c.gateway.print(fmt.format(data), attempt?.billingRef ?: sale.orderUuid.take(12), c.settings.printerWidth)) {
            PrintResult.Printed -> true
            is PrintResult.Failed -> { PosLog.w("PRINT_FAILED", "reason" to r.reason); false }
        }
    }

    /** The config's ZZ Cash loyalty.program, found at catalogue load. Null = no ZZ Cash on this till. */
    private var zzProgram: ZzCashProgram? = null

    /** account.fiscal.position records this till may use, keyed by id. */
    private var fiscalPositionsById: Map<Int, FiscalPosition> = emptyMap()

    /**
     * Resolve the customer from the typed phone / GSTIN and re-price the cart under
     * their fiscal position. Must run BEFORE payment: an inter-state B2B cart is
     * IGST, and the price changes with it.
     */
    fun attachCustomer() = viewModelScope.launch {
        val typed = _sell.value.phone to _sell.value.customer?.id
        val found = resolveCustomer()
        val fposId = c.fiscalPositions.resolve(_sell.value.config ?: return@launch, found)
        // The cashier typed another number or picked a match while we looked this one up:
        // their newer choice wins, this result is dropped.
        if ((_sell.value.phone to _sell.value.customer?.id) != typed) return@launch
        _sell.update { it.copy(customer = found, fiscalPosition = fiscalPositionsById[fposId], zzCash = null, zzRewards = emptyList(), zzRedemption = null) }
        reprice()
        found?.let { refreshZzCash(it) }
    }

    private suspend fun resolveCustomer(): Customer? {
        val s = _sell.value
        val e164 = c.customers.normalizeIndianMobile(s.phone) ?: return null
        return try {
            c.customers.findByPhone(e164) ?: c.customers.create(s.customerName, e164)
        } catch (e: Exception) {
            PosLog.w("CUSTOMER_LOOKUP_FAILED", "phone" to Redact.phone(e164), "err" to e.message)
            null // never block a sale on customer lookup
        }
    }

    fun newSale() {
        phoneSearchJob?.cancel()
        _sell.update {
            it.copy(
                orderUuid = UUID.randomUUID().toString(), lines = emptyList(), totals = null,
                phone = "", customerName = "", customer = null, customerMatches = emptyList(), zzCash = null,
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
}
