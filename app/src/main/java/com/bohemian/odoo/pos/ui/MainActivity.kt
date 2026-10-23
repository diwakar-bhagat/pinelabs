package com.bohemian.odoo.pos.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bohemian.odoo.pos.PosApp
import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.payment.PaymentOutcome
import com.bohemian.odoo.pos.pine.PayMode

class MainActivity : ComponentActivity() {
    private val vm: PosViewModel by lazy {
        val container = (application as PosApp).container
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PosViewModel(container) as T
        })[PosViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF111111), secondary = Color(0xFFFFC107))) {
                Surface(Modifier.fillMaxSize()) { PosRoot(vm) }
            }
        }
    }
}

@Composable
fun PosRoot(vm: PosViewModel) {
    val configured by vm.configured.collectAsStateWithLifecycle()
    if (configured) SellScreen(vm) else SetupScreen(vm)
}

// ============================================================================ Setup

@Composable
fun SetupScreen(vm: PosViewModel) {
    val s by vm.setup.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Bohemian Odoo POS · Setup (${vm.envLabel})", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text("1. Odoo API key for this terminal's user")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(key, { key = it }, Modifier.width(420.dp), singleLine = true,
                placeholder = { Text(if (s.apiKeyEntered) "saved — enter to replace" else "paste API key") },
                visualTransformation = PasswordVisualTransformation())
            Spacer(Modifier.width(8.dp))
            Button({ vm.saveApiKey(key) }, enabled = key.isNotBlank() && !s.busy) { Text("Save & connect") }
            if (s.apiKeyEntered) { Spacer(Modifier.width(8.dp)); OutlinedButton({ vm.loadConfigs() }) { Text("Reload POS list") } }
        }

        if (s.configs.isNotEmpty()) {
            Text("2. Which Odoo Point of Sale is this terminal?")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.configs.forEach { cfg ->
                    FilterChip(s.selectedConfig?.id == cfg.id, { vm.selectConfig(cfg) },
                        label = { Text(cfg.name + if (cfg.sessionOpen) " ●" else "") })
                }
            }
        }
        if (s.methods.isNotEmpty()) {
            Text("3. Odoo payment method for Card / UPI / Bharat QR on this E700")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.methods.filter { !it.isCash }.forEach { m ->
                    FilterChip(s.terminalMethodId == m.id, { vm.setTerminalMethod(m.id) }, label = { Text(m.name) })
                }
            }
            Text("4. Cash method")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.methods.filter { it.isCash }.forEach { m ->
                    FilterChip(s.cashMethodId == m.id, { vm.setCashMethod(m.id) }, label = { Text(m.name) })
                }
            }
            Text("5. Printer line width (characters)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(24, 32, 48).forEach { w -> FilterChip(s.printerWidth == w, { vm.setPrinterWidth(w) }, label = { Text("$w") }) }
            }
            if (vm.isDebug) Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(s.useFake, { vm.setUseFake(it) }); Spacer(Modifier.width(8.dp))
                Text("Use FAKE payment gateway (debug only — no real money)")
            }
            Button({ vm.finishSetup() }, enabled = !s.busy) { Text("Start selling") }
        }
        if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

// ============================================================================ Sell

@Composable
fun SellScreen(vm: PosViewModel) {
    val s by vm.sell.collectAsStateWithLifecycle()
    val outbox by vm.outboxCount.collectAsStateWithLifecycle()
    val unresolved by vm.unresolved.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        // Top bar
        Row(Modifier.fillMaxWidth().background(Color(0xFF111111)).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("ZenZebra · ${s.config?.name ?: "…"}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.width(12.dp))
            Badge(containerColor = Color(0xFFFFC107)) { Text(vm.envLabel, color = Color.Black) }
            Spacer(Modifier.weight(1f))
            if (outbox > 0) Badge(containerColor = Color.Red) { Text("$outbox not synced to Odoo", color = Color.White) }
            if (unresolved.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Badge(containerColor = Color(0xFFFF6F00)) { Text("${unresolved.size} payment(s) to check", color = Color.White) } }
            Spacer(Modifier.width(12.dp))
            TextButton({ vm.reopenSetup() }) { Text("Setup", color = Color.White) }
        }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
        if (s.config != null && !s.config!!.sessionOpen) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("No open session for ${s.config!!.name}. ")
                Button({ vm.openSession() }) { Text("Open session") }
            }
        }
        if (unresolved.isNotEmpty()) UnresolvedBar(unresolved.first().billingRef, unresolved.first().mode, vm)

        Row(Modifier.fillMaxSize()) {
            // Products
            Column(Modifier.weight(0.62f).fillMaxHeight().padding(12.dp)) {
                OutlinedTextField(
                    s.query, vm::setQuery, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Search or scan barcode") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.submitQuery() }),
                )
                if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyVerticalGrid(GridCells.Adaptive(170.dp), Modifier.fillMaxSize().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(s.filtered, key = { it.id }) { p ->
                        Card(Modifier.fillMaxWidth().height(86.dp).clickable { vm.add(p) }) {
                            Column(Modifier.padding(10.dp)) {
                                Text(p.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                Spacer(Modifier.weight(1f))
                                Text(p.price.format(), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            // Cart
            Column(Modifier.weight(0.38f).fillMaxHeight().background(Color(0xFFF5F5F5)).padding(12.dp)) {
                Text("Cart", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                LazyColumn(Modifier.weight(1f)) {
                    items(s.lines, key = { it.uuid }) { l ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(l.product.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton({ vm.changeQty(l.uuid, -1) }) { Text("−") }
                            Text("${l.qty}")
                            TextButton({ vm.changeQty(l.uuid, +1) }) { Text("+") }
                            Text(l.totalIncl?.format() ?: "…", Modifier.width(90.dp))
                        }
                    }
                }
                HorizontalDivider()
                val t = s.totals
                TotalRow("Excl. tax", t?.totalExcl)
                TotalRow("GST", t?.tax)
                TotalRow("TOTAL", t?.totalIncl, big = true)
                if (s.pricing) Text("Getting prices from Odoo…", fontSize = 12.sp)

                // ---- Customer section ----
                val cust = s.customer
                if (cust != null) {
                    Row(Modifier.fillMaxWidth().background(Color(0xFFE8F5E9)).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("✓ ${cust.name}", Modifier.weight(1f), fontSize = 14.sp,
                            fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                        cust.phone?.let { Text(it, fontSize = 12.sp, color = Color.Gray) }
                        // Only show a balance that belongs to the customer on screen.
                        s.zzCash?.takeIf { it.partnerId == cust.id }?.let { z ->
                            Spacer(Modifier.width(8.dp))
                            Text("ZZ Cash ${z.redeemable.format()}", fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton({ vm.clearCustomer() }) { Text("Clear", fontSize = 12.sp) }
                    }
                } else {
                    OutlinedTextField(s.phone, vm::setPhone, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Customer mobile (for WhatsApp bill)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    if (s.customerMatches.isNotEmpty()) {
                        Card(Modifier.fillMaxWidth().heightIn(max = 120.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            LazyColumn {
                                items(s.customerMatches, key = { it.id }) { match ->
                                    Row(Modifier.fillMaxWidth().clickable { vm.pickCustomer(match) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(match.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(match.phone ?: "", fontSize = 12.sp, color = Color.Gray)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    OutlinedTextField(s.customerName, vm::setCustomerName, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Name (new customers)") })
                }

                val canPay = t != null && !s.pricing && s.pay == PayUi.Idle && s.config?.sessionOpen == true
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PayButton("CARD", canPay, Modifier.weight(1f)) { vm.payWithTerminal(PayMode.CARD) }
                    PayButton("UPI", canPay, Modifier.weight(1f)) { vm.payWithTerminal(PayMode.UPI) }
                    PayButton("BQR", canPay, Modifier.weight(1f)) { vm.payWithTerminal(PayMode.BHARAT_QR) }
                    PayButton("CASH", canPay, Modifier.weight(1f)) { vm.payCash(null) }
                }
            }
        }
    }
    PaymentDialog(s.pay, vm)
}

@Composable
private fun TotalRow(label: String, value: Paise?, big: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), fontWeight = if (big) FontWeight.Bold else FontWeight.Normal, fontSize = if (big) 22.sp else 14.sp)
        Text(value?.format() ?: "—", fontWeight = if (big) FontWeight.Bold else FontWeight.Normal, fontSize = if (big) 22.sp else 14.sp)
    }
}

@Composable
private fun PayButton(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) =
    Button(onClick, modifier.height(56.dp), enabled = enabled) { Text(label, fontWeight = FontWeight.Bold) }

@Composable
private fun UnresolvedBar(ref: String, mode: String, vm: PosViewModel) {
    Row(Modifier.fillMaxWidth().background(Color(0xFFFFE0B2)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Payment $ref ($mode) not confirmed. ", Modifier.weight(1f))
        if (mode != PayMode.CARD.name) OutlinedButton({ vm.checkAgain(ref) }) { Text("Check status") }
        Spacer(Modifier.width(6.dp))
        OutlinedButton({ vm.resolveManually(ref, paid = true) }) { Text("Supervisor: PAID") }
        Spacer(Modifier.width(6.dp))
        OutlinedButton({ vm.resolveManually(ref, paid = false) }) { Text("Supervisor: NOT paid") }
    }
}

@Composable
private fun PaymentDialog(pay: PayUi, vm: PosViewModel) {
    when (pay) {
        PayUi.Idle -> Unit
        is PayUi.Working -> AlertDialog(
            onDismissRequest = {}, // cannot dismiss while the terminal is working
            confirmButton = {},
            title = { Text("${pay.mode} payment") },
            text = { Column { Text(pay.message); Spacer(Modifier.height(12.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) } },
        )
        is PayUi.Error -> AlertDialog({ vm.dismissPayResult() }, { TextButton({ vm.dismissPayResult() }) { Text("OK") } },
            title = { Text("Error") }, text = { Text(pay.message) })
        is PayUi.Done -> {
            val (title, body) = when {
                pay.paidByCash -> "✓ PAID (CASH)" to (pay.cashChange?.let { "Change to return: ${it.format()}" } ?: "")
                else -> when (val o = pay.outcome) {
                    is PaymentOutcome.Paid -> "✓ PAYMENT SUCCESSFUL" to listOfNotNull(
                        o.attempt.amount.format(), o.attempt.details?.rrn?.let { "RRN $it" }, o.attempt.details?.last4?.let { "Card ****$it" },
                    ).joinToString("\n")
                    is PaymentOutcome.NotPaid -> "✕ NOT PAID" to o.reason
                    is PaymentOutcome.NotSent -> "Payment not started" to o.reason
                    is PaymentOutcome.Unresolved -> "⚠ Status not confirmed" to
                        "Do NOT take payment again. Use 'Check status' or ask a supervisor to verify on the terminal.\nRef ${o.attempt.billingRef}"
                    is PaymentOutcome.Blocked -> "⚠ Previous payment unresolved" to
                        "Payment ${o.attempt.billingRef} for this bill is not confirmed yet. Resolve it before charging again."
                    is PaymentOutcome.AlreadyPaid -> "Already paid" to "This bill was already paid (${o.attempt.billingRef})."
                    null -> "" to ""
                }
            }
            val printedNote = when (pay.printed) { false -> "\n\nReceipt did not print — check paper."; else -> "" }
            AlertDialog(
                onDismissRequest = { vm.dismissPayResult() },
                confirmButton = { Button({ vm.dismissPayResult() }) { Text(if (pay.isPaid) "NEW BILL" else "BACK TO CART") } },
                title = { Text(title, fontWeight = FontWeight.Bold) },
                text = { Text(body + printedNote) },
            )
        }
    }
}
