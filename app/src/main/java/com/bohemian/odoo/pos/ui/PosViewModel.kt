package com.bohemian.odoo.pos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bohemian.odoo.pos.BuildConfig
import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.core.PosLog
import com.bohemian.odoo.pos.core.Redact
import com.bohemian.odoo.pos.di.AppContainer
import com.bohemian.odoo.pos.odoo.CartLine
import com.bohemian.odoo.pos.odoo.CartTotals
import com.bohemian.odoo.pos.odoo.Customer
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
    val loading: Boolean = false,
    val error: String? = null,
    val pay: PayUi = PayUi.Idle,
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

    fun loadCatalog() = viewModelScope.launch {
        _sell.update { it.copy(loading = true, error = null) }
        try {
            val cfg = c.catalog.config(c.settings.posConfigId)
            val products = c.catalog.products()
            _sell.update { it.copy(config = cfg, products = products, loading = false) }
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

    fun setPhone(v: String) = _sell.update { it.copy(phone = v.filter { ch -> ch.isDigit() || ch == '+' }.take(14)) }
    fun setCustomerName(v: String) = _sell.update { it.copy(customerName = v.take(60)) }

    private fun editCart(f: (List<CartLine>) -> List<CartLine>) {
        if (_sell.value.pay is PayUi.Working) return // cart frozen while paying
        _sell.update { it.copy(lines = f(it.lines), totals = null) }
        reprice()
    }

    private fun reprice() = viewModelScope.launch {
        _sell.update { it.copy(pricing = true, error = null) }
        try {
            val priced = _sell.value.lines.map { c.pricing.price(it) }
            _sell.update { s ->
                // Ignore if the cart changed while we were pricing.
                if (s.lines.map { it.uuid to it.qty } != priced.map { it.uuid to it.qty }) s
                else s.copy(lines = priced, totals = c.pricing.totals(priced), pricing = false)
            }
        } catch (e: Exception) {
            _sell.update { it.copy(pricing = false, error = "Could not get prices from Odoo: ${friendly(e)}") }
        }
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
        val customer = resolveCustomer()
        val snap = SaleSnapshot.of(s.orderUuid, cfg, s.lines, totals, customer)
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
        it.copy(orderUuid = UUID.randomUUID().toString(), lines = emptyList(), totals = null, phone = "", customerName = "", pay = PayUi.Idle, error = null)
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
