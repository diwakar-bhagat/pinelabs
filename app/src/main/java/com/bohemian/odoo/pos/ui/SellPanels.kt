package com.bohemian.odoo.pos.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bohemian.odoo.pos.R
import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.odoo.CartLine
import com.bohemian.odoo.pos.odoo.LineNote
import com.bohemian.odoo.pos.odoo.MenuTree
import com.bohemian.odoo.pos.odoo.Product
import com.bohemian.odoo.pos.odoo.ZzCashRules
import com.bohemian.odoo.pos.pine.PayMode

// The till laid out like the Odoo 19 web POS: the order on the left, the menu on the right.

@Composable
fun MdiIcon(@DrawableRes id: Int, description: String?, tint: Color = Zz.Black, size: Int = 22) =
    Icon(painterResource(id), description, Modifier.size(size.dp), tint = tint)

// ============================================================================ products

@Composable
fun ProductPanel(s: SellState, vm: PosViewModel, modifier: Modifier) {
    Column(modifier.background(Zz.Cream).padding(10.dp)) {
        OutlinedTextField(
            s.query, vm::setQuery, Modifier.fillMaxWidth(), singleLine = true,
            leadingIcon = { MdiIcon(R.drawable.mdi_magnify, null, Zz.Muted) },
            trailingIcon = { MdiIcon(R.drawable.mdi_barcode_scan, "Scan with the barcode scanner", Zz.Muted) },
            placeholder = { Text("Search products or scan a barcode") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.submitQuery() }),
        )
        if (s.query.isBlank()) CategoryBar(s.menu, s.selectedCateg, vm::selectCategory)
        if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        val menu = s.menu
        val inCart = s.qtyInCart
        if (s.filtered.isEmpty() && !s.loading) {
            Text(if (s.query.isBlank()) "No products in this menu." else "No product matches \"${s.query}\".",
                Modifier.padding(24.dp), color = Zz.Muted)
        }
        LazyVerticalGrid(GridCells.Adaptive(132.dp), Modifier.fillMaxSize().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.filtered, key = { it.id }) { p ->
                ProductTile(p, inCart[p.id] ?: 0, menu.colorOf(p), vm.images) { vm.add(p) }
            }
        }
    }
}

/** Top-level tabs, then a row of children for each selected level (WhatsApp Menu › WA HO …). */
@Composable
private fun CategoryBar(menu: MenuTree, selected: Int?, onSelect: (Int) -> Unit) {
    val path = menu.path(selected).map { it.id }.toSet()
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        menu.rows(selected).forEach { row ->
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c ->
                    val on = c.id in path
                    Box(
                        Modifier.heightIn(min = 48.dp).widthIn(min = 120.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Zz.odooTint(c.color))
                            .border(if (on) 2.dp else 1.dp, if (on) Zz.Black else Zz.Line, RoundedCornerShape(6.dp))
                            .clickable { onSelect(c.id) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(c.name, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = Zz.Black)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductTile(p: Product, qty: Int, color: Int, images: ProductImages, onTap: () -> Unit) {
    var photo by remember(p.id, p.writeDate) { mutableStateOf<ImageBitmap?>(images.cached(p)) }
    LaunchedEffect(p.id, p.writeDate) { if (photo == null) photo = images.load(p) }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onTap).semantics { contentDescription = "${p.name}, ${p.price.format()}" },
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Zz.Line),
    ) {
        Box {
            Column {
                Box(Modifier.fillMaxWidth().aspectRatio(1.25f).background(Zz.CreamDeep), contentAlignment = Alignment.Center) {
                    val img = photo
                    if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else MdiIcon(R.drawable.mdi_silverware_fork_knife, null, Zz.Line, size = 40)
                }
                Text(p.name, Modifier.padding(horizontal = 8.dp, vertical = 6.dp).heightIn(min = 38.dp),
                    fontSize = 14.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Zz.Black)
                Text(p.price.format(), Modifier.padding(start = 8.dp, bottom = 6.dp), fontSize = 13.sp, color = Zz.Muted)
                Box(Modifier.fillMaxWidth().height(4.dp).background(if (color == 0) Zz.Line else Zz.odooColor(color)))
            }
            if (qty > 0) {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Zz.Black)
                    .padding(horizontal = 7.dp, vertical = 2.dp)) {
                    Text("$qty", color = Zz.Cream, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

// ============================================================================ order

@Composable
fun OrderPanel(s: SellState, vm: PosViewModel, modifier: Modifier) {
    var noteFor by remember { mutableStateOf<String?>(null) }
    var showActions by remember { mutableStateOf(false) }
    var showRewards by remember { mutableStateOf(false) }
    var showCustomer by remember { mutableStateOf(false) }

    Column(modifier.background(Color.White)) {
        if (s.lines.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Tap a product to start the order", color = Zz.Muted)
            }
        } else LazyColumn(Modifier.weight(1f)) {
            items(s.lines, key = { it.uuid }) { l -> OrderLineRow(l, l.uuid == s.selectedLine) { vm.selectLine(l.uuid) } }
            s.zzRedemption?.let { r ->
                items(r.parts.size) { i ->
                    val part = r.parts[i]
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text("1", Modifier.width(28.dp), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                        Text(r.reward.description, Modifier.weight(1f), fontSize = 14.sp,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = Zz.Muted)
                        Text((part.totalIncl ?: Paise(-part.amount.value)).format(), fontSize = 14.sp,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    }
                }
            }
        }
        HorizontalDivider(color = Zz.Line)
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            val t = s.totals
            Row { Text("Taxes", Modifier.weight(1f), color = Zz.Muted, fontSize = 13.sp); Text(t?.tax?.format() ?: "—", color = Zz.Muted, fontSize = 13.sp) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Total", Modifier.weight(1f), style = ZzHeading, fontSize = 20.sp)
                Text(t?.totalIncl?.format() ?: "—", style = ZzHeading, fontSize = 24.sp)
            }
            if (s.pricing) Text("Getting prices from Odoo…", fontSize = 12.sp, color = Zz.Muted)
            PointsRow(s)
        }
        HorizontalDivider(color = Zz.Line)
        // Action row: customer, note, preset, more
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val cust = s.customer
            ActionButton(cust?.name ?: "Customer", R.drawable.mdi_account, Modifier.weight(1.4f), highlight = cust != null) { showCustomer = true }
            ActionButton("Note", R.drawable.mdi_note_text_outline, Modifier.weight(1f), enabled = s.selectedLine != null) { noteFor = s.selectedLine }
            s.config?.defaultPresetName?.let { ActionButton(it, R.drawable.mdi_shopping_outline, Modifier.weight(1f), enabled = false) {} }
            ActionButton(null, R.drawable.mdi_dots_vertical, Modifier.width(52.dp)) { showActions = true }
        }
        Keypad(s, vm)
        PayRow(s, vm)
    }

    noteFor?.let { uuid ->
        s.lines.firstOrNull { it.uuid == uuid }?.let { line -> NoteDialog(line, s, onDone = { vm.setLineNote(uuid, it); noteFor = null }, onDismiss = { noteFor = null }) }
            ?: run { noteFor = null }
    }
    if (showActions) ActionsDialog(s, onReward = { showActions = false; showRewards = true }, onDismiss = { showActions = false })
    if (showRewards) RewardsDialog(s, vm, onDismiss = { showRewards = false })
    if (showCustomer) CustomerDialog(s, vm, onDismiss = { showCustomer = false })
}

@Composable
private fun OrderLineRow(l: CartLine, selected: Boolean, onTap: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(if (selected) Zz.Selected else Color.White).clickable(onClick = onTap)
        .padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${l.qty}", Modifier.width(28.dp), fontWeight = FontWeight.Bold)
            Text(l.product.name, Modifier.weight(1f), fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(l.totalIncl?.format() ?: "…", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        if (l.note.isNotEmpty()) FlowRowTags(l.note, Modifier.padding(start = 28.dp, top = 4.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowTags(tags: List<LineNote>, modifier: Modifier) =
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.forEach { n ->
            Text(n.text, Modifier.clip(RoundedCornerShape(4.dp)).background(Zz.odooTint(n.colorIndex).takeIf { n.colorIndex != 0 } ?: Zz.CreamDeep)
                .padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 12.sp, color = Zz.Black)
        }
    }

/** "ZZ Cash  34.6 +1.2 −0 = 35.8": balance, earned by this order, spent on a reward, and after. */
@Composable
private fun PointsRow(s: SellState) {
    val cust = s.customer ?: return
    val bal = s.zzCash?.takeIf { it.partnerId == cust.id }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("ZZ Cash", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        if (bal == null) { Text("…", color = Zz.Muted); return@Row }
        val earned = s.zzEarned
        val spent = s.zzRedemption?.reward?.points ?: Paise.ZERO
        Text(bal.points.toPlainString(), fontSize = 14.sp)
        Text(" +${earned.toPlainString()}", color = Zz.Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        if (spent.isPositive) Text(" −${spent.toPlainString()}", color = Zz.Red, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(" = ${(bal.points + earned - spent).toPlainString()}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ActionButton(label: String?, @DrawableRes icon: Int, modifier: Modifier, enabled: Boolean = true,
                         highlight: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(onClick, modifier.height(48.dp), enabled = enabled, shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (highlight) Zz.GoldSoft else Color.White),
        border = BorderStroke(1.dp, Zz.Line)) {
        MdiIcon(icon, label ?: "More actions", if (enabled) Zz.Black else Zz.Line, size = 20)
        if (label != null) {
            Spacer(Modifier.width(6.dp))
            Text(label, color = if (enabled) Zz.Black else Zz.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
        }
    }
}

/**
 * Odoo's numpad. Qty acts on the selected line. % and Price stay off: the config does not allow
 * a manual discount, and a price typed on the till would need Odoo's own permission checks.
 */
@Composable
private fun Keypad(s: SellState, vm: PosViewModel) {
    val hasLine = s.selectedLine != null && s.pay == PayUi.Idle
    val keys = listOf(
        listOf("1", "2", "3", "Qty"), listOf("4", "5", "6", "%"),
        listOf("7", "8", "9", "Price"), listOf("+/-", "0", ".", "⌫"),
    )
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                row.forEach { k ->
                    val digit = k.length == 1 && k[0].isDigit()
                    val enabled = hasLine && (digit || k == "⌫")
                    val bg = when (k) {
                        "Qty" -> Zz.Selected
                        "⌫" -> Zz.RedSoft
                        else -> Zz.CreamDeep
                    }
                    Box(
                        Modifier.weight(1f).height(48.dp).background(bg)
                            .alpha(if (enabled || k == "Qty") 1f else 0.35f)
                            .clickable(enabled = enabled) { if (k == "⌫") vm.keypadBackspace() else vm.keypadDigit(k[0]) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (k == "⌫") MdiIcon(R.drawable.mdi_backspace_outline, "Delete", Zz.Black)
                        else Text(k, fontSize = if (digit) 20.sp else 16.sp, fontWeight = if (k == "Qty") FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
private fun PayRow(s: SellState, vm: PosViewModel) {
    val canPay = s.totals != null && !s.pricing && s.pay == PayUi.Idle && s.config?.sessionOpen == true
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PayTile("CARD", R.drawable.mdi_credit_card_outline, canPay, Modifier.weight(1f)) { vm.payWithTerminal(PayMode.CARD) }
        PayTile("UPI", R.drawable.mdi_qrcode, canPay, Modifier.weight(1f)) { vm.payWithTerminal(PayMode.UPI) }
        PayTile("BQR", R.drawable.mdi_qrcode, canPay, Modifier.weight(1f)) { vm.payWithTerminal(PayMode.BHARAT_QR) }
        PayTile("CASH", R.drawable.mdi_cash, canPay, Modifier.weight(1f), dark = true) { vm.payCash(null) }
    }
}

@Composable
private fun PayTile(label: String, @DrawableRes icon: Int, enabled: Boolean, modifier: Modifier, dark: Boolean = false, onClick: () -> Unit) =
    Button(onClick, modifier.height(56.dp), enabled = enabled, shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (dark) Zz.Black else Zz.Red, contentColor = Zz.Cream)) {
        MdiIcon(icon, null, if (enabled) Zz.Cream else Zz.Muted, size = 18)
        Spacer(Modifier.width(4.dp))
        Text(label, style = ZzHeading, fontSize = 15.sp)
    }

// ============================================================================ dialogs

/** Odoo's "Add Note": tag chips from pos.note plus free text, saved as the line's note tags. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteDialog(line: CartLine, s: SellState, onDone: (List<LineNote>) -> Unit, onDismiss: () -> Unit) {
    val tagNames = s.notes.map { it.name }.toSet()
    var chosen by remember { mutableStateOf(line.note.filter { it.text in tagNames }.map { it.text }.toSet()) }
    var free by remember { mutableStateOf(line.note.filter { it.text !in tagNames }.joinToString("\n") { it.text }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${line.product.name}: Add Note", fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.notes.forEach { n ->
                        val on = n.name in chosen
                        FilterChip(on, { chosen = if (on) chosen - n.name else chosen + n.name }, label = { Text(n.name) },
                            colors = FilterChipDefaults.filterChipColors(containerColor = Zz.odooTint(n.color),
                                selectedContainerColor = Zz.odooColor(n.color).copy(alpha = 0.55f)))
                    }
                }
                OutlinedTextField(free, { free = it.take(200) }, Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    placeholder = { Text("Note for the kitchen / counter") })
            }
        },
        confirmButton = {
            Button({
                val tags = s.notes.filter { it.name in chosen }.map { LineNote(it.name, it.color) } +
                    free.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { LineNote(it, 0) }
                onDone(tags)
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Discard") } },
    )
}

/** The ⋮ actions of the web POS that this till supports. */
@Composable
private fun ActionsDialog(s: SellState, onReward: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Actions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val canReward = s.customer != null && s.zzCash != null && s.totals != null
                OutlinedButton(onReward, Modifier.fillMaxWidth().height(56.dp), enabled = canReward, shape = RoundedCornerShape(6.dp)) {
                    MdiIcon(R.drawable.mdi_star, null, Zz.Gold); Spacer(Modifier.width(8.dp)); Text("Reward", color = Zz.Black)
                }
                if (!canReward) Text("Attach a customer with a ZZ Cash card to use a reward.", fontSize = 13.sp, color = Zz.Muted)
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

/** "Available rewards": every ZZ Cash reward this card can pay for on this bill. */
@Composable
private fun RewardsDialog(s: SellState, vm: PosViewModel, onDismiss: () -> Unit) {
    val bal = s.zzCash?.takeIf { it.partnerId == s.customer?.id }
    val total = s.totals?.totalIncl
    val options = if (bal != null && total != null) ZzCashRules.eligible(bal, s.zzRewards, total) else emptyList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Available rewards") },
        text = {
            Column {
                s.zzRedemption?.let { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Applied: ${r.reward.description}", Modifier.weight(1f))
                        TextButton({ vm.removeZzCash(); onDismiss() }) { Text("Remove") }
                    }
                }
                if (options.isEmpty()) Text("No reward fits this card and bill.", color = Zz.Muted)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(options, key = { it.rewardId }) { r ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { vm.applyZzCash(r); onDismiss() },
                            colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Zz.Line)) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("ZZ Cash", fontWeight = FontWeight.Bold)
                                    Text("Add \"${r.description}\"", fontSize = 14.sp)
                                }
                                Text("›", fontSize = 22.sp, color = Zz.Muted)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

/** Find the customer by mobile (the existing phone search), or type a name for a new one. */
@Composable
private fun CustomerDialog(s: SellState, vm: PosViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose customer") },
        text = {
            Column(Modifier.widthIn(min = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val cust = s.customer
                if (cust != null) {
                    Row(Modifier.fillMaxWidth().background(Zz.GoldSoft).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(cust.name, fontWeight = FontWeight.Bold)
                            cust.phone?.let { Text(it, fontSize = 13.sp, color = Zz.Muted) }
                            s.zzCash?.takeIf { it.partnerId == cust.id }?.let { Text("${it.points.toPlainString()} ZZ Cash", fontSize = 13.sp) }
                        }
                        TextButton({ vm.clearCustomer() }) { Text("Unselect") }
                    }
                } else {
                    OutlinedTextField(s.phone, vm::setPhone, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Customer mobile (for WhatsApp bill)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(s.customerMatches, key = { it.id }) { m ->
                            Row(Modifier.fillMaxWidth().clickable { vm.pickCustomer(m); onDismiss() }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(m.name, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(m.phone ?: "", fontSize = 13.sp, color = Zz.Muted)
                            }
                            HorizontalDivider(color = Zz.Line)
                        }
                    }
                    OutlinedTextField(s.customerName, vm::setCustomerName, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Name (new customer, created at payment)") })
                }
            }
        },
        confirmButton = { Button(onDismiss) { Text("Done") } },
    )
}
