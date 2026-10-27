package com.bohemian.odoo.pos.ui

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bohemian.odoo.pos.R
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bohemian.odoo.pos.BuildConfig
import com.bohemian.odoo.pos.PosApp
import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.odoo.ZzCashRules
import com.bohemian.odoo.pos.payment.PaymentOutcome
import com.bohemian.odoo.pos.payment.VoidState
import com.bohemian.odoo.pos.data.VoidAttemptEntity
import com.bohemian.odoo.pos.pine.PayMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: PosViewModel by lazy {
        val container = (application as PosApp).container
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PosViewModel(container) as T
        })[PosViewModel::class.java]
    }

    override fun onResume() {
        super.onResume()
        Kiosk.apply(this, (application as PosApp).container.settings.kioskMode)
        vm.refreshIfStale()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            ZenZebraTheme {
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZzLogo(48.dp)
            Spacer(Modifier.width(12.dp))
            Text("ZenZebra · Setup (${vm.envLabel})", style = ZzHeading, fontSize = 22.sp)
        }
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
            Text("3. Odoo payment method for Card / UPI on this E700")
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

        val activity = LocalContext.current as Activity
        Text("Terminal", fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(s.kioskMode, { v -> vm.setKioskMode(v); Kiosk.apply(activity, v) }); Spacer(Modifier.width(8.dp))
            Text(
                "Kiosk mode — start the till on boot and pin it to the screen" +
                    if (Kiosk.permitted(activity)) "" else " (pinning needs PAXSTORE to allow this app and the Pine payment app)"
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (s.crashCount == 0) "No crash reports." else "${s.crashCount} crash report(s) on this terminal.", Modifier.weight(1f))
            if (s.crashCount > 0) OutlinedButton({ vm.clearCrashReports() }) { Text("Clear") }
        }
        s.latestCrash?.let { Text(it, fontSize = 11.sp, maxLines = 12, overflow = TextOverflow.Ellipsis) }
        var pineReport by remember { mutableStateOf<String?>(null) }
        OutlinedButton({ pineReport = PineCheck.report(activity, BuildConfig.PINE_APPLICATION_ID, BuildConfig.PINE_SDK_PRESENT) }) {
            Text("Check Pine payment app")
        }
        pineReport?.let { Text(it, fontSize = 13.sp) }
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
        Row(Modifier.fillMaxWidth().background(Zz.Black).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ZzLogo(36.dp)
            Spacer(Modifier.width(10.dp))
            // The brand is always written "ZenZebra", and the POS name as it is in Odoo.
            Text("ZenZebra", color = Zz.Cream, style = ZzHeading, fontSize = 20.sp)
            Text("  ·  ${s.config?.name ?: "…"}", color = Zz.Cream, style = ZzHeading, fontSize = 16.sp)
            Spacer(Modifier.width(12.dp))
            // PROD is the normal state; anything else (UAT, NO PINE SDK) is flagged in gold.
            val prod = vm.envLabel == "PROD"
            Badge(containerColor = if (prod) Zz.Ink else Zz.Gold) { Text(vm.envLabel, color = if (prod) Zz.MutedOnBlack else Zz.Black) }
            Spacer(Modifier.weight(1f))
            if (outbox > 0) Badge(containerColor = Zz.Red) { Text("$outbox not synced to Odoo", color = Zz.Cream) }
            if (unresolved.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Badge(containerColor = Zz.Gold) { Text("${unresolved.size} payment(s) to check", color = Zz.Black) } }
            Spacer(Modifier.width(12.dp))
            IconButton({ vm.loadCatalog() }) { MdiIcon(R.drawable.mdi_refresh, "Reload menu and photos from Odoo", Zz.Cream) }
            TextButton({ vm.startVoid() }) { Text("VOID", color = Zz.Cream, style = ZzHeading) }
            if (s.config?.sessionOpen == true) TextButton({ vm.startClose() }) { Text("CLOSE SESSION", color = Zz.Cream, style = ZzHeading) }
            TextButton({ vm.reopenSetup() }) { Text("SETUP", color = Zz.MutedOnBlack, style = ZzHeading) }
        }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
        s.offlineCatalogSavedAt?.let { at ->
            Row(Modifier.fillMaxWidth().background(Zz.GoldSoft).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Catalogue not refreshed (${s.catalogStaleReason ?: "No connection to Odoo"}) — showing the complete one from " +
                    SimpleDateFormat("dd-MMM HH:mm", Locale("en", "IN")).format(Date(at)) +
                    ". Prices and payment need Odoo. ", Modifier.weight(1f))
                OutlinedButton({ vm.loadCatalog() }) { Text("Retry") }
            }
        }
        if (s.config != null && !s.config!!.sessionOpen && s.offlineCatalogSavedAt == null) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("No open session for ${s.config!!.name}. ")
                Button({ vm.openSession() }) { Text("Open session") }
            }
        }
        if (unresolved.isNotEmpty()) UnresolvedBar(unresolved.first().billingRef, unresolved.first().mode, vm)
        val openVoids by vm.openVoids.collectAsStateWithLifecycle()
        openVoids.firstOrNull()?.let { VoidBar(it, vm) }

        Row(Modifier.fillMaxSize()) {
            OrderPanel(s, vm, Modifier.weight(0.38f).fillMaxHeight())
            VerticalDivider(color = Zz.Line)
            ProductPanel(s, vm, Modifier.weight(0.62f).fillMaxHeight())
        }
    }
    PaymentDialog(s.pay, vm)
    val closing by vm.closing.collectAsStateWithLifecycle()
    closing?.let { ClosingDialog(it, cashControl = s.config?.cashControl == true, vm) }
    val voiding by vm.voiding.collectAsStateWithLifecycle()
    voiding?.let { VoidDialog(it, vm) }
}

/** A void that still needs a person: review on the terminal, or the Odoo refund. */
@Composable
private fun VoidBar(v: VoidAttemptEntity, vm: PosViewModel) {
    Row(Modifier.fillMaxWidth().background(Zz.RedSoft).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val amount = Paise(v.amountPaise).format()
        when (v.state) {
            VoidState.NEEDS_REVIEW.name -> {
                Text("Void ${v.billingRef} ($amount) not confirmed. Check the terminal report. ", Modifier.weight(1f))
                OutlinedButton({ vm.resolveVoid(v.billingRef, voided = true) }) { Text("Supervisor: VOIDED") }
                Spacer(Modifier.width(6.dp))
                OutlinedButton({ vm.resolveVoid(v.billingRef, voided = false) }) { Text("Supervisor: NOT voided") }
            }
            VoidState.VOIDED.name -> {
                Text("Sale ${v.saleRef} voided ($amount) — Odoo refund still missing. ", Modifier.weight(1f))
                OutlinedButton({ vm.retryOdooRefund(v.billingRef) }) { Text("Create Odoo refund") }
                Spacer(Modifier.width(6.dp))
                OutlinedButton({ vm.confirmOdooRefund(v.billingRef) }) { Text("Supervisor: refunded in Odoo") }
            }
            else -> Text("Void ${v.billingRef} in progress…", Modifier.weight(1f))
        }
    }
}

@Composable
private fun VoidDialog(ui: VoidUi, vm: PosViewModel) {
    AlertDialog(
        onDismissRequest = { if (!ui.busy) vm.dismissVoid() },
        title = { Text("Void a card sale", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val confirm = ui.confirm
                when {
                    confirm != null -> Text("Void ${confirm.amount.format()} — card ****${confirm.details?.last4 ?: "?"}, " +
                        "RRN ${confirm.details?.rrn ?: "?"}?\nThis reverses the charge on the terminal. Same-day, unsettled sales only.")
                    ui.message == null && !ui.busy && ui.sales.isEmpty() -> Text("No card sales today.")
                    ui.message == null -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(ui.sales, key = { it.billingRef }) { a ->
                            Row(Modifier.fillMaxWidth().clickable { vm.chooseVoid(a) }.padding(vertical = 8.dp)) {
                                Text(SimpleDateFormat("HH:mm", Locale("en", "IN")).format(Date(a.createdAt)), Modifier.width(56.dp))
                                Text("****${a.details?.last4 ?: "?"}  ${a.billingRef}", Modifier.weight(1f))
                                Text(a.amount.format(), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                ui.message?.let { Text(it) }
                if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            if (ui.confirm != null) Button({ vm.confirmVoid() }, enabled = !ui.busy) { Text("VOID ON TERMINAL") }
            else Button({ vm.dismissVoid() }, enabled = !ui.busy) { Text(if (ui.message != null) "DONE" else "CLOSE") }
        },
        dismissButton = {
            if (ui.confirm != null) TextButton({ vm.chooseVoid(null) }, enabled = !ui.busy) { Text("Back") }
        },
    )
}

@Composable
private fun ClosingDialog(ui: ClosingUi, cashControl: Boolean, vm: PosViewModel) {
    AlertDialog(
        onDismissRequest = { if (!ui.busy) vm.dismissClosing() },
        title = { Text(if (ui.closed) "✓ Session closed" else "Close session", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val sum = ui.summary
                if (sum != null) {
                    TotalRow("Sales (${sum.orderCount} orders)", sum.orderTotal)
                    sum.cash?.let { Text("${it.name}: expected ${it.expected.format()} (opening ${it.opening.format()} + sales ${it.payments.format()})") }
                    sum.nonCash.forEach { Text("${it.name}: ${it.count} · ${it.amount.format()}") }
                }
                if (!ui.closed && sum != null) {
                    if (cashControl) OutlinedTextField(ui.counted, vm::setCountedCash, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Cash counted in drawer (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(ui.notes, vm::setClosingNotes, Modifier.fillMaxWidth(), label = { Text("Closing notes (optional)") })
                }
                if (ui.closed) Text(when (ui.printed) {
                    true -> "Z-report printed."
                    false -> "Z-report did not print — check paper."
                    null -> "Printing Z-report…"
                })
                ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            when {
                ui.closed -> Button({ vm.dismissClosing() }) { Text("DONE") }
                else -> Button({ vm.confirmClose() }, enabled = !ui.busy && ui.summary != null) { Text("CLOSE SESSION") }
            }
        },
        dismissButton = {
            when {
                ui.closed && ui.printed == false -> TextButton({ vm.printZReport() }) { Text("Print again") }
                !ui.closed -> TextButton({ vm.dismissClosing() }, enabled = !ui.busy) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun TotalRow(label: String, value: Paise?, big: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), fontWeight = if (big) FontWeight.Bold else FontWeight.Normal, fontSize = if (big) 22.sp else 14.sp)
        Text(value?.format() ?: "—", fontWeight = if (big) FontWeight.Bold else FontWeight.Normal, fontSize = if (big) 22.sp else 14.sp)
    }
}

@Composable
private fun PayButton(label: String, enabled: Boolean, modifier: Modifier, dark: Boolean = false, onClick: () -> Unit) =
    Button(
        onClick, modifier.height(60.dp), enabled = enabled,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (dark) Zz.Black else Zz.Red,
            contentColor = Zz.Cream,
            disabledContainerColor = Zz.Line,
            disabledContentColor = Zz.Muted,
        ),
    ) { Text(label, style = ZzHeading, fontSize = 16.sp) }

@Composable
private fun UnresolvedBar(ref: String, mode: String, vm: PosViewModel) {
    Row(Modifier.fillMaxWidth().background(Zz.GoldSoft).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
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

/** The ZenZebra logo (pixel zebra on a cream tile), the same artwork as the launcher icon. */
@Composable
private fun ZzLogo(size: Dp) =
    Image(painterResource(R.mipmap.ic_launcher), contentDescription = "ZenZebra", modifier = Modifier.size(size))

