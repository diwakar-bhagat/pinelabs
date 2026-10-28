package com.bohemian.odoo.pos.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bohemian.odoo.pos.R
import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.odoo.SalesHour
import com.bohemian.odoo.pos.odoo.StoreSales
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * SALES in the top bar: the store's sales day so far, at a glance. One big total, bills, first
 * and last bill, how it was paid, and hour by hour. Figures are Odoo's; this only lays them out.
 */
@Composable
fun SalesScreen(ui: SalesUi, vm: PosViewModel) {
    Dialog(onDismissRequest = vm::closeSales, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.94f), shape = RoundedCornerShape(10.dp), color = Zz.Cream) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val s = ui.sales
                val tz = s?.tz ?: TimeZone.getDefault()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (s == null || s.day.today) "TODAY'S SALES" else "YESTERDAY'S SALES") + " · ${ui.storeName}",
                            style = ZzHeading, fontSize = 20.sp)
                        s?.let { Text(dayLine(it), color = Zz.Muted, fontSize = 14.sp) }
                    }
                    ui.updatedAt?.let { Text("Updated ${clock(it, tz)}", color = Zz.Muted, fontSize = 14.sp) }
                    IconButton(vm::refreshSales, enabled = !ui.loading) { MdiIcon(R.drawable.mdi_refresh, "Refresh now", Zz.Black) }
                    Button(vm::closeSales, shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Zz.Black, contentColor = Zz.Cream)) { Text("CLOSE") }
                }
                if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
                ui.error?.let { err ->
                    Text(
                        if (s != null) "Could not refresh ($err). Showing the figures from ${ui.updatedAt?.let { clock(it, tz) } ?: "earlier"}."
                        else "Could not load sales: $err",
                        Modifier.fillMaxWidth().background(Zz.GoldSoft).padding(8.dp),
                    )
                }
                if (s != null) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Summary(s, Modifier.weight(0.42f).fillMaxHeight())
                    HourByHour(s, Modifier.weight(0.58f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun Summary(s: StoreSales, modifier: Modifier) = Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(rupees(s.total), style = ZzHeading, fontSize = 44.sp)
    Text(
        if (s.bills == 0) "No bills yet" else "${s.bills} bill${if (s.bills == 1) "" else "s"} · average ${rupees(s.average ?: Paise.ZERO)}",
        fontSize = 18.sp, fontWeight = FontWeight.Bold,
    )
    if (s.firstBillMs != null) {
        Text("First bill ${clock(s.firstBillMs, s.tz)}" + (s.lastBillMs?.let { "  ·  Last bill ${clock(it, s.tz)}" } ?: ""), fontSize = 15.sp)
    }
    HorizontalDivider(color = Zz.Line)
    Text("PAID BY", style = ZzHeading, fontSize = 14.sp, color = Zz.Muted)
    if (s.byMethod.isEmpty()) Text("Nothing yet", color = Zz.Muted)
    s.byMethod.forEach { m ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(m.name, Modifier.weight(1f), fontSize = 16.sp)
            Text("${m.count}×", color = Zz.Muted, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            Text(rupees(m.total), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun HourByHour(s: StoreSales, modifier: Modifier) = Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("HOUR BY HOUR", style = ZzHeading, fontSize = 14.sp, color = Zz.Muted)
    val hours = withQuietHours(s.hours)
    if (hours.isEmpty()) { Text("Bills appear here hour by hour from the first bill.", color = Zz.Muted); return@Column }
    val top = hours.maxOf { it.total.value }.coerceAtLeast(1)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(hours, key = { it.startMs }) { h ->
            Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(hourRange(h.startMs, s.tz), Modifier.width(132.dp), fontSize = 14.sp)
                Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 4.dp)) {
                    val share = (h.total.value.coerceAtLeast(0).toFloat() / top).coerceIn(0f, 1f)
                    if (share > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(share).clip(RoundedCornerShape(3.dp)).background(Zz.Black))
                }
                Text(rupees(h.total), Modifier.width(110.dp), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    textAlign = TextAlign.End,
                    color = if (h.bills == 0) Zz.Muted else Color.Unspecified)
                Text("${h.bills} bill${if (h.bills == 1) "" else "s"}", Modifier.width(64.dp), color = Zz.Muted, fontSize = 13.sp,
                    textAlign = TextAlign.End)
            }
        }
    }
}

/** Odoo leaves out hours without a bill; between the first and last bill they show as zero, so a quiet hour is visible. */
private fun withQuietHours(hours: List<SalesHour>): List<SalesHour> {
    if (hours.isEmpty()) return hours
    val byStart = hours.associateBy { it.startMs }
    return (hours.first().startMs..hours.last().startMs step HOUR_MS).map { byStart[it] ?: SalesHour(it, Paise.ZERO, 0) }
}

private const val HOUR_MS = 3_600_000L

private val IN = Locale("en", "IN")

private fun clock(ms: Long, tz: TimeZone) = SimpleDateFormat("h:mm a", IN).apply { timeZone = tz }.format(ms)

/** "10 AM – 11 AM" */
private fun hourRange(startMs: Long, tz: TimeZone): String {
    val f = SimpleDateFormat("h a", IN).apply { timeZone = tz }
    return "${f.format(startMs)} – ${f.format(startMs + HOUR_MS)}"
}

/** "Wed 28 Oct · 6:00 AM to now", or the whole day once it is over. */
private fun dayLine(s: StoreSales): String {
    val date = SimpleDateFormat("EEE d MMM", IN).apply { timeZone = s.tz }.format(s.day.fromMs)
    val now = System.currentTimeMillis()
    val until = if (now < s.day.toMs) "now" else clock(s.day.toMs, s.tz)
    return "$date · ${clock(s.day.fromMs, s.tz)} to $until"
}

/** "₹1,802.00" with Indian grouping (₹1,23,456.00), easier to read at a glance than ₹123456.00. */
private fun rupees(p: Paise): String {
    val f = NumberFormat.getNumberInstance(IN).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
    return (if (p.value < 0) "-₹" else "₹") + f.format(p.toRupees().abs())
}
