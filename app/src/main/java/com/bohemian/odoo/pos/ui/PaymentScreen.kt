package com.bohemian.odoo.pos.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bohemian.odoo.pos.R
import com.bohemian.odoo.pos.payment.SplitLine

/**
 * Odoo's payment screen for a split bill: method tiles add a line pre-filled with what is still
 * due, the numpad edits the selected line, each Card / UPI line is charged on the E700 on its own,
 * and Validate sends the order with all its payment lines once the bill is covered.
 */
@Composable
fun PaymentScreen(ui: SplitUi, vm: PosViewModel) {
    Dialog(onDismissRequest = vm::closeSplit, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.94f), shape = RoundedCornerShape(10.dp), color = Zz.Cream) {
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Left: methods and the payment lines.
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Payment", style = ZzHeading, fontSize = 20.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MethodTile("CASH", R.drawable.mdi_cash, Modifier.weight(1f)) { vm.splitAdd(SplitKind.CASH) }
                        MethodTile("CARD", R.drawable.mdi_credit_card_outline, Modifier.weight(1f)) { vm.splitAdd(SplitKind.CARD) }
                        MethodTile("UPI", R.drawable.mdi_qrcode, Modifier.weight(1f)) { vm.splitAdd(SplitKind.UPI) }
                    }
                    if (ui.split.lines.isEmpty()) {
                        Text("Tap Cash, Card or UPI to add a payment. Each line starts with what is still due.", color = Zz.Muted)
                    }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(ui.split.lines, key = { it.uuid }) { line -> PaymentLineRow(line, ui, vm) }
                    }
                    ui.message?.let { Text(it, color = Zz.Red, fontSize = 14.sp) }
                }
                // Right: amounts, numpad, Back / Validate.
                Column(Modifier.width(340.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ui.split.total.format(), style = ZzHeading, fontSize = 34.sp, modifier = Modifier.align(Alignment.End))
                    AmountRow("Paid", ui.split.paid.format())
                    if (ui.split.change.isPositive) AmountRow("Change", ui.split.change.format(), Zz.Green)
                    else AmountRow("Still due", ui.split.remainingDue.format(), if (ui.split.remainingDue.isPositive) Zz.Red else Zz.Green)
                    Spacer(Modifier.weight(1f))
                    SplitNumpad(vm)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(vm::closeSplit, Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(6.dp),
                            enabled = ui.parts.values.none { it is PartState.Charging }) { Text("BACK") }
                        Button(vm::splitValidate, Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(6.dp), enabled = ui.canValidate,
                            colors = ButtonDefaults.buttonColors(containerColor = Zz.Black, contentColor = Zz.Cream)) {
                            Text("VALIDATE", style = ZzHeading)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MethodTile(label: String, @DrawableRes icon: Int, modifier: Modifier, onClick: () -> Unit) =
    OutlinedButton(onClick, modifier.height(56.dp), shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, Zz.Line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White)) {
        MdiIcon(icon, null, Zz.Black, size = 20); Spacer(Modifier.width(6.dp)); Text(label, color = Zz.Black, style = ZzHeading)
    }

@Composable
private fun AmountRow(label: String, value: String, color: Color = Zz.Black) =
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = Zz.Muted, fontSize = 16.sp)
        Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }

@Composable
private fun PaymentLineRow(line: SplitLine, ui: SplitUi, vm: PosViewModel) {
    val selected = ui.split.selectedUuid == line.uuid
    val part = ui.parts[line.uuid]
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (selected) Zz.Selected else Color.White)
            .clickable { vm.splitSelect(line.uuid) }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(line.mode?.name ?: "CASH", fontWeight = FontWeight.Bold)
            val status = when (part) {
                null -> if (line.mode == null) "Taken at Validate" else "Not charged yet"
                PartState.Charging -> "Charging on the terminal…"
                is PartState.Paid -> "✓ Paid" + (part.attempt.details?.rrn?.let { " · RRN $it" } ?: "")
                is PartState.Failed -> "✕ ${part.reason.lineSequence().first()}"
                is PartState.Unconfirmed -> "⚠ Not confirmed: check the yellow bar"
            }
            Text(status, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = when (part) { is PartState.Paid -> Zz.Green; is PartState.Failed, is PartState.Unconfirmed -> Zz.Red; else -> Zz.Muted })
        }
        Text(line.amount.format(), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        if (line.mode != null && part !is PartState.Paid && part !is PartState.Unconfirmed) {
            Button({ vm.splitCharge(line.uuid) }, enabled = part != PartState.Charging && line.amount.isPositive, shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Zz.Red, contentColor = Zz.Cream)) { Text("CHARGE") }
        }
        if (!ui.isLocked(line.uuid)) TextButton({ vm.splitRemove(line.uuid) }) { Text("✕", fontSize = 18.sp, color = Zz.Muted) }
    }
}

/** Odoo's payment numpad: digits replace the selected line's amount; +10 / +20 / +50 add to it. */
@Composable
private fun SplitNumpad(vm: PosViewModel) {
    val rows = listOf(listOf("1", "2", "3", "+10"), listOf("4", "5", "6", "+20"), listOf("7", "8", "9", "+50"), listOf(".", "0", "⌫", ""))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(4.dp))
                            .background(if (k.startsWith("+")) Zz.GoldSoft else if (k.isEmpty()) Color.Transparent else Zz.CreamDeep)
                            .clickable(enabled = k.isNotEmpty()) {
                                when {
                                    k.startsWith("+") -> vm.splitQuick(k.drop(1).toLong())
                                    k == "⌫" -> vm.splitKey('<')
                                    else -> vm.splitKey(k[0])
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (k == "⌫") MdiIcon(R.drawable.mdi_backspace_outline, "Delete", Zz.Black)
                        else Text(k, fontSize = 20.sp, fontWeight = if (k.startsWith("+")) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}
