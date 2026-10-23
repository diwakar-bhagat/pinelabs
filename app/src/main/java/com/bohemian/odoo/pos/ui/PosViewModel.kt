package com.bohemian.odoo.pos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bohemian.odoo.pos.BuildConfig
import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.core.PosLog
import com.bohemian.odoo.pos.core.Redact
import com.bohemian.odoo.pos.di.AppContainer
import com.bohemian.odoo.pos.odoo.CartLine
import com.bohemian.odoo.pos.payment.UpiPayee
import com.bohemian.odoo.pos.odoo.CartTotals
import com.bohemian.odoo.pos.odoo.Customer
import com.bohemian.odoo.pos.odoo.ZzCashBalance
import com.bohemian.odoo.pos.odoo.ZzCashReward
import com.bohemian.odoo.pos.odoo.ZzCashRedemption
import com.bohemian.odoo.pos.odoo.ZzCashRules
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
import com.bohemian.odoo.pos.pine.PayMode
import com.bohemian.odoo.pos.pine.PrintResult
import com.bohemian.odoo.pos.receipt.ReceiptData
import com.bohemian.odoo.pos.receipt.ReceiptFormatter
import com.bohemian.odoo.pos.receipt.ReceiptItem
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

data class SetupState(
    val apiKeyEntered: Boolean = false,
    val configs: List<PosConfig> = emptyList(),
    val selectedConfig: PosConfig? = null,
    val methods: List<PaymentMethod> = emptyList(),
    val terminalMethodId: Int = 0,
    val cashMethodId: Int = 0,
    val printerWidth: Int = 32,
    val useFake: Boolean = false,
    val upiPrimary: String = "",
    val upiFallback: String = "",
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
    /**
     * The fiscal position the cart was priced with. It must be the one sent to Odoo,
     * otherwise our totals and Odoo's disagree on an inter-state B2B sale.
     */
    val fiscalPosition: FiscalPosition? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val pay: PayUi = PayUi.Idle,
    /** Cashier's choice on the cart: use the backup UPI ID instead of the primary. */
    val upiUseFallback: Boolean = false,
) {
    val filtered: List<Product>
        get() = if (query.isBlank()) products.take(60)
        else query.trim().lowercase().let { q ->
            products.filter { it.name.lowercase().contains(q) || it.barcode == q || it.defaultCode?.lowercase() == q }.take(60)
        }
}

class PosViewModel(private val c: AppContainer) : ViewModel() {

    private val _configured = MutableStateFlow(c.settings.isConfigured)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    private val _setup = MutableStateFlow(
        SetupState(
            apiKeyEntered = !c.settings.odooApiKey.isNullOrBlank(),
            printerWidth = c.settings.printerWidth,
            useFake = c.settings.useFakeGateway,
            upiPrimary = c.settings.upiPrimaryVpa,
            upiFallback = c.settings.upiFallbackVpa,
        )
    )
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
    fun setUpiPrimary(v: String) = _setup.update { it.copy(upiPrimary = v.trim()) }
    fun setUpiFallback(v: String) = _setup.update { it.copy(upiFallback = v.trim()) }

    fun finishSetup() {
        val s = _setup.value
        val cfg = s.selectedConfig ?: return _setup.update { it.copy(error = "Choose a POS") }
        if (s.terminalMethodId == 0) return _setup.update { it.copy(error = "Choose the Odoo payment method for E700 card/UPI") }
        c.settings.posConfigId = cfg.id
        c.settings.posConfigName = cfg.name
        c.settings.terminalPaymentMethodId = s.terminalMethodId
        c.settings.cashPaymentMethodId = s.cashMethodId
        c.settings.printerWidth = s.printerWidth
        if (s.upiPrimary.isNotBlank() && !UpiPayee.isValidVpa(s.upiPrimary))
            return _setup.update { it.copy(error = "Primary UPI ID is not valid (name@bank)") }
        if (s.upiFallback.isNotBlank() && !UpiPayee.isValidVpa(s.upiFallback))
            return _setup.update { it.copy(error = "Backup UPI ID is not valid (name@bank)") }
        c.settings.upiPrimaryVpa = s.upiPrimary
        c.settings.upiFallbackVpa = s.upiFallback
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

    fun loadCatalog() = viewModelScope.launch {
        _sell.update { it.copy(loading = true, error = null) }
        try {
            val cfg = c.catalog.config(c.settings.posConfigId)
            val products = c.catalog.products()
            val fposIds = (cfg.fiscalPositionIds + listOfNotNull(cfg.defaultFiscalPositionId)).distinct()
            fiscalPositionsById = c.fiscalPositions.load(fposIds)
            c.fiscalPositions.primeTaxFiscalPositions(products.flatMap { it.taxIds })
            _sell.update {
                it.copy(
                    config = cfg, products = products, loading = false,
                    fiscalPosition = fiscalPositionsById[cfg.defaultFiscalPositionId],
                )
            }
        } catch (e: Exception) {
            _sell.update { it.copy(loading = false, error = friendly(e)) }
        }
    }

    fun openSession() = viewModelScope.launch {
        _sell.update { it.copy(loading = true, error = null) }
        try {
            c.sessions.open(c.settings.posConfigId)
            _sell.update { it.copy(config = c.catalog.config(c.settings.posConfigId), loading = false) }
        } catch (e: Exception) {
            _sell.update { it.copy(loading = false, error = friendly(e)) }
        }
    }

    fun setQuery(q: String) = _sell.update { it.copy(query = q) }

    /** Toggle on the cart, before any UPI payment. Only the choice of ID changes, not the total. */
    fun setUpiUseFallback(v: Boolean) = _sell.update { it.copy(upiUseFallback = v) }

    /** The UPI IDs for the cart toggle, or null when no valid primary ID is set. */
    fun upiPayee(): UpiPayee? {
        val primary = c.settings.upiPrimaryVpa
        if (!UpiPayee.isValidVpa(primary)) return null
        val fallback = c.settings.upiFallbackVpa.takeIf { UpiPayee.isValidVpa(it) }
        return UpiPayee(primary, fallback, useFallback = _sell.value.upiUseFallback)
    }

    /** Keyboard-wedge scanners type the barcode then Enter. */
    fun submitQuery() {
        val s = _sell.value
        val exact = s.products.firstOrNull { it.barcode == s.query.trim() }
        if (exact != null) { add(exact); _sell.update { it.copy(query = "") } }
    }

    fun add(p: Product) = editCart { lines ->
        val i = lines.indexOfFirst { it.product.id == p.id }
        if (i >= 0) lines.toMutableList().also { it[i] = it[i].copy(qty = it[i].qty + 1, totalIncl = null, totalExcl = null) }
        else lines + CartLine(UUID.randomUUID().toString(), p, 1)
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
        val (balance, rewards) = try {
            val b = c.zzCash.balance(customer.id)
            b to (if (b == null) emptyList() else c.zzCash.rewards())
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
        if (ZzCashRules.singleTaxSet(s.lines.map { it.product.taxIds }) == null) {
            _sell.update { it.copy(error = "ZZ Cash works when every item has the same tax rate") }; return
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
    fun clearCustomer() = _sell.update {
        it.copy(customer = null, customerMatches = emptyList(), phone = "", customerName = "",
            zzCash = null, zzRewards = emptyList(), zzRedemption = null)
    }

    private var phoneSearchJob: kotlinx.coroutines.Job? = null

    /**
     * Fire a background search when the cashier types ≥4 digits. Debounced: only the
     * last keystroke's search runs. Clears matches when input is too short.
     */
    private fun searchCustomerByPhone(digits: String) {
        val clean = digits.filter { it.isDigit() }
        if (clean.length < 4) {
            _sell.update { it.copy(customerMatches = emptyList()) }
            return
        }
        phoneSearchJob?.cancel()
        phoneSearchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(300) // debounce keystrokes
            try {
                val matches = c.customers.searchByPhoneSuffix(clean)
                _sell.update { it.copy(customerMatches = matches) }
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
                    lines = priced,
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
     * Price the reward line with Odoo's compute_all, using the cart's single tax rate.
     * Null when the reward no longer applies (mixed taxes, or the bill fell to or below it).
     */
    private suspend fun priceRedemption(r: ZzCashRedemption, lines: List<CartLine>, position: FiscalPosition?): ZzCashRedemption? {
        val taxIds = ZzCashRules.singleTaxSet(lines.map { it.product.taxIds }) ?: return null
        val cartTotal = c.pricing.totals(lines)?.totalIncl ?: return null
        if (r.reward.amount >= cartTotal) return null
        val line = c.pricing.price(
            CartLine(
                uuid = "zz-${r.rewardCode}",
                product = Product(r.reward.discountProductId, r.reward.description, Paise(-r.reward.amount.value), null, null, taxIds),
                qty = 1,
            ),
            position,
        )
        return r.copy(totalIncl = line.totalIncl, totalExcl = line.totalExcl, taxIds = taxIds)
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

    fun payWithTerminal(mode: PayMode) = viewModelScope.launch {
        val snap = prepareSnapshot() ?: return@launch
        _sell.update { it.copy(pay = PayUi.Working(mode.label(), "Complete the payment on the terminal…")) }
        val outcome = c.payments.pay(snap.orderUuid, snap.totals().totalIncl, mode, snap.customerPhone?.takeLast(10))
        handleOutcome(outcome)
    }

    fun payCash(tendered: Paise?) = viewModelScope.launch {
        if (c.settings.cashPaymentMethodId == 0) { _sell.update { it.copy(error = "No cash method configured") }; return@launch }
        val snap = prepareSnapshot() ?: return@launch
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
        val snap = SaleSnapshot.of(s.orderUuid, cfg, s.lines, totals, customer, s.fiscalPosition?.id, s.zzRedemption)
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

    private suspend fun print(sale: PaidSale, label: String, attempt: PaymentAttempt?, reprint: Boolean = false): Boolean {
        val cfg = _sell.value.config
        val fmt = ReceiptFormatter(c.settings.printerWidth)
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
        )
        return when (val r = c.gateway.print(fmt.format(data), attempt?.billingRef ?: sale.orderUuid.take(12))) {
            PrintResult.Printed -> true
            is PrintResult.Failed -> { PosLog.w("PRINT_FAILED", "reason" to r.reason); false }
        }
    }

    /** account.fiscal.position records this till may use, keyed by id. */
    private var fiscalPositionsById: Map<Int, FiscalPosition> = emptyMap()

    /**
     * Resolve the customer from the typed phone / GSTIN and re-price the cart under
     * their fiscal position. Must run BEFORE payment: an inter-state B2B cart is
     * IGST, and the price changes with it.
     */
    fun attachCustomer() = viewModelScope.launch {
        val found = resolveCustomer()
        val fposId = c.fiscalPositions.resolve(_sell.value.config ?: return@launch, found)
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

    fun newSale() = _sell.update {
        it.copy(
            orderUuid = UUID.randomUUID().toString(), lines = emptyList(), totals = null,
            phone = "", customerName = "", customer = null, customerMatches = emptyList(), zzCash = null,
            zzRewards = emptyList(), zzRedemption = null,
            fiscalPosition = fiscalPositionsById[it.config?.defaultFiscalPositionId],
            pay = PayUi.Idle, error = null,
        )
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
