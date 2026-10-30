package com.bohemian.odoo.pos.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bohemian.odoo.pos.R
import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.odoo.CartLine
import com.bohemian.odoo.pos.odoo.LineNote
import com.bohemian.odoo.pos.odoo.MenuTree
import com.bohemian.odoo.pos.odoo.ParkedBill
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
        if (s.query.isBlank()) CategoryBar(s.menu, s.selectedCateg, s.products.size, vm::selectCategory)
        if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        val menu = s.menu
        val inCart = s.qtyInCart
        if (s.filtered.isEmpty()) {
            val msg = when {
                s.loading && s.products.isEmpty() -> "Loading the catalogue from Odoo…"
                s.loading -> null
                s.products.isEmpty() -> "No products for this POS in Odoo. Check \"Available in POS\" on the products, then reload."
                s.query.isNotBlank() -> "No product matches \"${s.query}\"."
                else -> "No products in this menu."
            }
            msg?.let { Text(it, Modifier.padding(24.dp), color = Zz.Muted) }
        }
        LazyVerticalGrid(GridCells.Adaptive(132.dp), Modifier.fillMaxSize().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.filtered, key = { it.id }) { p ->
                ProductTile(p, inCart[p.id] ?: 0, menu.colorOf(p), vm.images) { vm.add(p) }
            }
        }
    }
}

/**
 * "All" (every product, also those with no menu tab), then the top-level tabs, then a row of
 * children for each selected level (WhatsApp Menu › WA HO …).
 */
@Composable
private fun CategoryBar(menu: MenuTree, selected: Int?, productCount: Int, onSelect: (Int?) -> Unit) {
    val path = menu.path(selected).map { it.id }.toSet()
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        menu.rows(selected).forEachIndexed { i, row ->
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (i == 0) CategoryTab("All · $productCount", Color.White, selected == null) { onSelect(null) }
                row.forEach { c -> CategoryTab(c.name, Zz.odooTint(c.color), c.id in path) { onSelect(c.id) } }
            }
        }
    }
}

@Composable
private fun CategoryTab(name: String, tint: Color, on: Boolean, onClick: () -> Unit) =
    Box(
        Modifier.heightIn(min = 48.dp).widthIn(min = 120.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(tint)
            .border(if (on) 2.dp else 1.dp, if (on) Zz.Black else Zz.Line, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(name, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = Zz.Black)
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

/**
 * The queue of open bills (the web POS's "+" and order tabs): the bill on screen first, then the ones
 * waiting, oldest first. Tap one to open it, "+" for a new bill, long-press a waiting one to remove it.
 */
@Composable
private fun BillChips(s: SellState, vm: PosViewModel) {
    val parked by vm.parked.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<ParkedBill?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        BillChip("${ParkedBill.labelFor(s.customer, s.billNumber)} · ${s.lines.sumOf { it.qty }}", active = true, onClick = {}, onLongClick = null)
        parked.forEach { b ->
            BillChip("${b.label} · ${b.itemCount}", active = false, onClick = onceIn { vm.openBill(b.orderUuid) }, onLongClick = { removing = b })
        }
        OutlinedButton(onceIn { vm.newBill() }, contentPadding = PaddingValues(horizontal = 14.dp), shape = RoundedCornerShape(6.dp)) {
            Text("+", fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
        if (parked.isNotEmpty()) Text("${parked.size} waiting", fontSize = 12.sp, color = Zz.Muted)
    }
    removing?.let { b ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${b.label}?") },
            text = { Text("${b.itemCount} item(s). Nothing was charged; the bill is dropped from this till.") },
            confirmButton = { TextButton({ vm.removeBill(b.orderUuid); removing = null }) { Text("Remove bill") } },
            dismissButton = { TextButton({ removing = null }) { Text("Keep") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BillChip(label: String, active: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (active) Zz.Black else Zz.Cream,
        border = BorderStroke(1.dp, if (active) Zz.Black else Zz.Line),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = if (active) Zz.Cream else Zz.Black,
            fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
fun OrderPanel(s: SellState, vm: PosViewModel, modifier: Modifier) {
    var noteFor by remember { mutableStateOf<String?>(null) }
    var showActions by remember { mutableStateOf(false) }
    var showRewards by remember { mutableStateOf(false) }
    var showCustomer by remember { mutableStateOf(false) }

    // Keep the selected (or just added) line in view: the list is short when the keypad is open.
    val cart = rememberLazyListState()
    LaunchedEffect(s.selectedLine, s.lines.size) {
        val i = s.lines.indexOfFirst { it.uuid == s.selectedLine }
        if (i < 0) return@LaunchedEffect
        val info = cart.layoutInfo
        val shown = info.visibleItemsInfo.firstOrNull { it.index == i }
        if (shown == null || shown.offset < 0 || shown.offset + shown.size > info.viewportEndOffset) cart.animateScrollToItem(i)
    }

    Column(modifier.background(Color.White)) {
        BillChips(s, vm)
        HorizontalDivider(color = Zz.Line)
        if (s.lines.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Tap a product to start the order", color = Zz.Muted)
            }
        } else LazyColumn(Modifier.weight(1f), state = cart) {
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
            items(s.offerLines, key = { "offer-${it.line.code}-${it.line.taxIds}-${it.line.rewardProductId}" }) { o -> OfferLineRow(o, vm) }
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
            PointsRow(s, vm, onRewards = { showRewards = true })
        }
        HorizontalDivider(color = Zz.Line)
        // Action row: customer, note, preset, more
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val cust = s.customer
            ActionButton(cust?.name ?: "Customer", R.drawable.mdi_account, Modifier.weight(1.4f), highlight = cust != null) { showCustomer = true }
            ActionButton("Note", R.drawable.mdi_note_text_outline, Modifier.weight(1f), enabled = s.selectedLine != null) { noteFor = s.selectedLine }
            s.config?.defaultPresetName?.let { ActionButton(it, R.drawable.mdi_shopping_outline, Modifier.weight(1f), enabled = false) {} }
            ActionButton(null, R.drawable.mdi_dots_vertical, Modifier.width(52.dp), highlight = s.rewardFits) { showActions = true }
        }
        // The keypad gives way to the cart: drag the handle to resize it, tap to fold it.
        var keyDp by remember(s.keypadKeyDp) { mutableFloatStateOf(s.keypadKeyDp.toFloat()) }
        KeypadHandle(
            folded = keyDp < 1f,
            onDrag = { dyDp -> keyDp = (keyDp - dyDp / KEY_ROWS).coerceIn(0f, PosViewModel.MAX_KEY_DP.toFloat()) },
            onDragEnd = { val snapped = snapKey(keyDp); keyDp = snapped.toFloat(); vm.setKeypadKeyDp(snapped) },
            onTap = { vm.setKeypadKeyDp(if (s.keypadKeyDp == 0) DEFAULT_KEY_DP else 0) },
        )
        if (keyDp >= 1f) Keypad(s, vm, keyDp.dp)
        PayRow(s, vm)
    }

    noteFor?.let { uuid ->
        s.lines.firstOrNull { it.uuid == uuid }?.let { line -> NoteDialog(line, s, onDone = { vm.setLineNote(uuid, it); noteFor = null }, onDismiss = { noteFor = null }) }
            ?: run { noteFor = null }
    }
    var showCode by remember { mutableStateOf(false) }
    if (showActions) ActionsDialog(s, onReward = { showActions = false; showRewards = true }, onCode = { showActions = false; showCode = true },
        onDismiss = { showActions = false })
    if (showCode) EnterCodeDialog(onApply = { vm.enterCode(it); showCode = false }, onDismiss = { showCode = false })
    if (showRewards) RewardsDialog(s, vm, onDismiss = { showRewards = false })
    if (showCustomer) CustomerDialog(s, vm, onDismiss = { showCustomer = false })
}

/** A promotion, code or free item on the bill, in italics like Odoo's reward lines; ✕ takes it off. */
@Composable
private fun OfferLineRow(o: com.bohemian.odoo.pos.odoo.PricedOffer, vm: PosViewModel) {
    val italic = androidx.compose.ui.text.font.FontStyle.Italic
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${o.line.qty}", Modifier.width(28.dp), fontStyle = italic)
        Text(o.line.name, Modifier.weight(1f), fontSize = 14.sp, fontStyle = italic, color = Zz.Green, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(o.totalIncl.format(), fontSize = 14.sp, fontStyle = italic, color = Zz.Green)
        TextButton({ vm.removeOffer(o.line.code) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("✕", color = Zz.Muted) }
    }
}

/** ⋮ → Enter Code: a promo code (a ZZ Note, a store code) or a coupon, as on the web POS. */
@Composable
private fun EnterCodeDialog(onApply: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter Code") },
        text = {
            OutlinedTextField(code, { code = it.trim().take(64) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (code.isNotBlank()) onApply(code) }))
        },
        confirmButton = { Button({ onApply(code) }, enabled = code.isNotBlank()) { Text("Apply") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
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

/**
 * "ZZ Cash  34.6 +1.2 −0 = 35.8": balance, earned by this order, spent on a reward, and after;
 * with "Use reward" when one fits this bill. Loading, no card and a failed lookup each say so.
 */
@Composable
private fun PointsRow(s: SellState, vm: PosViewModel, onRewards: () -> Unit) {
    val cust = s.customer ?: return
    val bal = s.zzCash?.takeIf { it.partnerId == cust.id }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("ZZ Cash", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        if (bal == null) {
            when (s.zzLookup) {
                ZzLookup.NO_CARD -> Text("No card yet: earns +${s.zzEarned.toPlainString()} on this sale", fontSize = 13.sp, color = Zz.Muted)
                ZzLookup.FAILED -> {
                    Text("Couldn't load", fontSize = 13.sp, color = Zz.Red)
                    TextButton(onceIn { vm.retryZzCash() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Retry") }
                }
                else -> Text("Loading…", fontSize = 13.sp, color = Zz.Muted)
            }
            return@Row
        }
        if (s.zzRules == null) {
            // Without the earning rules this bill would count as earning 0 and no reward would fit.
            Text("${bal.points.toPlainString()} · earning rules not loaded", fontSize = 13.sp, color = Zz.Red)
            TextButton(onceIn { vm.retryZzCash() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Retry") }
            return@Row
        }
        val earned = s.zzEarned
        val spent = s.zzRedemption?.reward?.points ?: Paise.ZERO
        Text(bal.points.toPlainString(), fontSize = 14.sp)
        Text(" +${earned.toPlainString()}", color = Zz.Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        if (spent.isPositive) Text(" −${spent.toPlainString()}", color = Zz.Red, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(" = ${(bal.points + earned - spent).toPlainString()}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        val fits = s.rewardFits
        if (fits || s.zzRedemption != null) {
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onRewards, Modifier.height(32.dp), shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 8.dp), border = BorderStroke(1.dp, Zz.Gold),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Zz.GoldSoft)) {
                Text(if (fits) "Use reward" else "Reward", color = Zz.Black, fontSize = 13.sp)
            }
        }
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

private const val KEY_ROWS = 4
private const val DEFAULT_KEY_DP = 36

/** Snap a dragged key height to folded, compact or full. */
private fun snapKey(dp: Float): Int = when {
    dp < 18f -> 0
    dp < 42f -> DEFAULT_KEY_DP
    else -> PosViewModel.MAX_KEY_DP
}

/** The grip above the keypad: drag up or down to resize it, tap to fold or unfold it. */
@Composable
private fun KeypadHandle(folded: Boolean, onDrag: (Float) -> Unit, onDragEnd: () -> Unit, onTap: () -> Unit) {
    val density = LocalDensity.current.density
    // The gesture detector outlives recompositions; always call the latest callbacks.
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    Row(
        Modifier.fillMaxWidth().height(24.dp).background(Zz.CreamDeep)
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragEnd = { end() }, onDragCancel = { end() }) { _, dy -> drag(dy / density) }
            }
            .clickable(onClickLabel = if (folded) "Show keypad" else "Hide keypad", onClick = onTap),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Zz.Muted))
        if (folded) Text("  Keypad", fontSize = 12.sp, color = Zz.Muted)
    }
}

/**
 * Odoo's numpad, [keyHeight] per key row. Qty acts on the selected line. % and Price stay off: the
 * config does not allow a manual discount, and a price typed on the till would need Odoo's own
 * permission checks.
 */
@Composable
private fun Keypad(s: SellState, vm: PosViewModel, keyHeight: androidx.compose.ui.unit.Dp) {
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
                        Modifier.weight(1f).height(keyHeight).background(bg)
                            .alpha(if (enabled || k == "Qty") 1f else 0.35f)
                            .clickable(enabled = enabled) { if (k == "⌫") vm.keypadBackspace() else vm.keypadDigit(k[0]) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (k == "⌫") MdiIcon(R.drawable.mdi_backspace_outline, "Delete", Zz.Black)
                        else if (keyHeight >= 20.dp) Text(k, fontSize = if (digit) 20.sp else 16.sp, fontWeight = if (k == "Qty") FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

/**
 * Card, UPI, cash, or SPLIT (several of them on one bill, Odoo's payment screen). Bharat QR is not
 * offered (2026-10-27); [PayMode.BHARAT_QR] stays so the journal and the Reconciler can still settle
 * an older BQR attempt. A partly paid split bill can only be finished on SPLIT.
 */
@Composable
private fun PayRow(s: SellState, vm: PosViewModel) {
    val canPay = s.totals != null && !s.pricing && s.pay == PayUi.Idle && s.config?.sessionOpen == true
    val single = canPay && !s.partPaid.isPositive
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PayTile("CARD", R.drawable.mdi_credit_card_outline, single, Modifier.weight(1f)) { vm.payWithTerminal(PayMode.CARD) }
        PayTile("UPI", R.drawable.mdi_qrcode, single, Modifier.weight(1f)) { vm.payWithTerminal(PayMode.UPI) }
        PayTile("CASH", R.drawable.mdi_cash, single, Modifier.weight(1f), dark = true) { vm.payCash(null) }
        PayTile("SPLIT", R.drawable.mdi_call_split, canPay, Modifier.weight(1f), dark = true) { vm.openSplit() }
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
private fun ActionsDialog(s: SellState, onReward: () -> Unit, onCode: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Actions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onCode, Modifier.fillMaxWidth().height(56.dp), enabled = s.lines.isNotEmpty(), shape = RoundedCornerShape(6.dp)) {
                    MdiIcon(R.drawable.mdi_barcode_scan, null, Zz.Black); Spacer(Modifier.width(8.dp)); Text("Enter Code", color = Zz.Black)
                }
                val canReward = s.totals != null && (s.offerClaimable.isNotEmpty() || s.offerLines.isNotEmpty() || (s.customer != null && s.zzCash != null))
                OutlinedButton(onReward, Modifier.fillMaxWidth().height(56.dp), enabled = canReward, shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (s.rewardFits) Zz.GoldSoft else Color.Transparent)) {
                    MdiIcon(R.drawable.mdi_star, null, Zz.Gold); Spacer(Modifier.width(8.dp)); Text("Reward", color = Zz.Black)
                }
                if (!canReward) Text("No reward fits this bill yet. ZZ Cash needs a customer with a card.", fontSize = 13.sp, color = Zz.Muted)
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

/** "Available rewards": the store's offers this bill can take, and every ZZ Cash reward the card can pay for. */
@Composable
private fun RewardsDialog(s: SellState, vm: PosViewModel, onDismiss: () -> Unit) {
    val bal = s.zzCash?.takeIf { it.partnerId == s.customer?.id }
    val total = s.totals?.totalIncl
    val options = if (bal != null && total != null) ZzCashRules.eligible(bal, s.zzEarned, s.zzRewards, total) else emptyList()
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
                s.offerLines.distinctBy { it.line.code }.forEach { o ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Applied: ${o.line.name}", Modifier.weight(1f))
                        TextButton({ vm.removeOffer(o.line.code); onDismiss() }) { Text("Remove") }
                    }
                }
                OfferChoices(s, vm, onDismiss)
                if (options.isEmpty() && s.zzRedemption == null && s.customer != null) {
                    val can = bal?.let { ZzCashRules.available(it, s.zzEarned) }
                    Text("No reward fits this card and bill" + (can?.let { " (${it.toPlainString()} ZZ Cash to spend, cheapest reward " +
                        (s.zzRewards.firstOrNull()?.points?.toPlainString() ?: "—") + ")" } ?: "") + "." +
                        if (s.zzRules == null) " This bill's points are not counted yet: earning rules not loaded." else "", color = Zz.Muted)
                }
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

/** The store's offers this bill can take; a reward with several free items lists those in the cart. */
@Composable
private fun OfferChoices(s: SellState, vm: PosViewModel, onDismiss: () -> Unit) {
    s.offerClaimable.forEach { o ->
        val names = s.lines.associate { it.product.id to it.product.name }
        val picks: List<Int?> = o.productChoices.ifEmpty { listOf(o.productId) }
        picks.forEach { pid ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { vm.claimOffer(o, pid); onDismiss() },
                colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Zz.Line)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(o.program.name, fontWeight = FontWeight.Bold)
                        Text("Add \"${o.reward.description}\"" + (pid?.let { names[it] }?.let { ": $it" } ?: ""), fontSize = 14.sp)
                    }
                    Text("›", fontSize = 22.sp, color = Zz.Muted)
                }
            }
        }
    }
}

/**
 * Every customer matching "Name or mobile", A→Z. Odoo sends 20 at a time; scrolling near the end
 * asks for the next 20, so all matches (436 for "ra") can be reached.
 */
@Composable
private fun CustomerMatches(s: SellState, vm: PosViewModel) {
    if (s.customerMatches.isEmpty()) return
    val list = rememberLazyListState()
    // A new search starts at the top again.
    LaunchedEffect(s.phone) { list.scrollToItem(0) }
    val nearEnd by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, s.customerMatches.size) { if (nearEnd) vm.loadMoreCustomers() }
    Text("${s.customerMatches.size}${if (s.customerHasMore) "+" else ""} found" + if (s.customerHasMore) " · scroll for more" else "",
        fontSize = 13.sp, color = Zz.Muted)
    LazyColumn(Modifier.heightIn(max = 300.dp), state = list) {
        items(s.customerMatches, key = { it.id }) { m ->
            Row(Modifier.fillMaxWidth().clickable { vm.pickCustomer(m) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(m.name, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.phone ?: "", fontSize = 13.sp, color = Zz.Muted)
            }
            HorizontalDivider(color = Zz.Line)
        }
    }
}

/**
 * Find the customer by name or mobile (as you type, or the full number + Find), or save a new
 * one: mobile above + name → Save creates the Odoo customer and attaches them to this sale.
 */
@Composable
private fun CustomerDialog(s: SellState, vm: PosViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Customer") },
        text = {
            Column(Modifier.widthIn(min = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val cust = s.customer
                if (cust != null) {
                    Row(Modifier.fillMaxWidth().background(Zz.GoldSoft).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(cust.name, fontWeight = FontWeight.Bold)
                            cust.phone?.let { Text(it, fontSize = 13.sp, color = Zz.Muted) }
                            val bal = s.zzCash?.takeIf { it.partnerId == cust.id }
                            Text(when {
                                bal != null -> "${bal.points.toPlainString()} ZZ Cash"
                                s.zzLookup == ZzLookup.NO_CARD -> "No ZZ Cash card yet: it starts with this sale"
                                s.zzLookup == ZzLookup.FAILED -> "ZZ Cash could not be loaded"
                                else -> "Loading ZZ Cash…"
                            }, fontSize = 13.sp)
                        }
                        TextButton({ vm.clearCustomer() }) { Text("Unselect") }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Locked while Find / Save talk to Odoo, so the answer always matches the text shown.
                        OutlinedTextField(s.phone, vm::setCustomerQuery, Modifier.weight(1f), singleLine = true, enabled = !s.customerBusy,
                            label = { Text("Name or mobile") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { vm.lookupCustomer() }))
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onceIn { vm.lookupCustomer() }, Modifier.height(56.dp), enabled = !s.customerBusy,
                            shape = RoundedCornerShape(6.dp)) { Text("Find") }
                    }
                    CustomerMatches(s, vm)
                    // New customer: always offered, and called out when the full number is not in Odoo.
                    Text(if (s.customerNotFound) "Not in Odoo yet: save as a new customer" else "New customer: mobile above, name here",
                        fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (s.customerNotFound) Zz.Red else Zz.Black)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(s.customerName, vm::setCustomerName, Modifier.weight(1f), singleLine = true,
                            enabled = !s.customerBusy, label = { Text("Name") })
                        Spacer(Modifier.width(8.dp))
                        Button(onceIn { vm.saveNewCustomer() }, Modifier.height(56.dp), shape = RoundedCornerShape(6.dp),
                            enabled = !s.customerBusy && s.customerName.isNotBlank() && s.phone.count { it.isDigit() } >= 10,
                            colors = ButtonDefaults.buttonColors(containerColor = Zz.Black, contentColor = Zz.Cream)) { Text("Save") }
                    }
                }
                if (s.customerBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                s.customerNote?.let { Text(it, fontSize = 13.sp, color = if (s.customerNoteOk) Zz.Green else Zz.Red) }
            }
        },
        confirmButton = { Button(onDismiss) { Text("Done") } },
    )
}
