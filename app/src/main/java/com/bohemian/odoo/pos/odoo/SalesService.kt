package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The store's sales day: [startHour] to [endHour] (24 = midnight) in the Odoo user's time zone.
 * Before [startHour] the day that just ended is shown ("Yesterday"), so the figure resets by itself
 * each morning and nobody has to press anything.
 */
data class SalesDay(val today: Boolean, val fromMs: Long, val toMs: Long) {
    companion object {
        fun of(nowMs: Long, tz: TimeZone, startHour: Int, endHour: Int): SalesDay {
            require(startHour in 0..23 && endHour in startHour + 1..24) { "sales day must run from 0–23 to a later hour up to 24" }
            val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
            val today = cal.get(Calendar.HOUR_OF_DAY) >= startHour
            if (!today) cal.add(Calendar.DAY_OF_MONTH, -1)
            cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            cal.set(Calendar.HOUR_OF_DAY, startHour)
            val from = cal.timeInMillis
            cal.add(Calendar.HOUR_OF_DAY, endHour - startHour)
            return SalesDay(today, from, cal.timeInMillis)
        }
    }
}

/** One hour of the day: from [startMs] for an hour, as Odoo grouped it. */
data class SalesHour(val startMs: Long, val total: Paise, val bills: Int)

/** Money taken by one payment method ("UPI", "Cash", "Pine Labs E700 (A2A)") as Odoo names it. */
data class SalesByMethod(val name: String, val total: Paise, val count: Int)

/** A store's sales for one [SalesDay], every figure as Odoo has it. */
data class StoreSales(
    val day: SalesDay,
    val tz: TimeZone,
    /** Net of refunds, as in Odoo's Orders Analysis. */
    val total: Paise,
    val bills: Int,
    val firstBillMs: Long?,
    val lastBillMs: Long?,
    val byMethod: List<SalesByMethod>,
    val hours: List<SalesHour>,
) {
    val average: Paise? get() = if (bills > 0) Paise(total.value / bills) else null
}

/**
 * Read-only sales figures of one pos.config, straight from Odoo's own grouping
 * (`formatted_read_group`, the call behind Odoo's Orders Analysis): no local sums of bills.
 */
class SalesService(private val odoo: OdooJson2Client) {

    /** The Odoo user's time zone ("Asia/Calcutta"); read once. Null until read. */
    @Volatile private var odooTz: TimeZone? = null

    suspend fun timeZone(): TimeZone = odooTz ?: run {
        val name = (odoo.call("res.users", "context_get") as? JsonObject)?.str("tz")
        (name?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault()).also { odooTz = it }
    }

    suspend fun storeSales(configId: Int, nowMs: Long, startHour: Int, endHour: Int): StoreSales = coroutineScope {
        val tz = timeZone()
        val day = SalesDay.of(nowMs, tz, startHour, endHour)
        val from = utc(day.fromMs)
        val to = utc(day.toMs)
        val orders = listOf(
            listOf("config_id", "=", configId),
            listOf("state", "in", PAID_STATES),
            listOf("date_order", ">=", from),
            listOf("date_order", "<", to),
        )
        val summaryJob = async {
            odoo.call("pos.order", "formatted_read_group", jsonOf(
                "domain" to orders,
                "groupby" to emptyList<String>(),
                "aggregates" to listOf("amount_total:sum", "__count", "date_order:min", "date_order:max"),
            )).objects().firstOrNull()
        }
        val hoursJob = async {
            odoo.call("pos.order", "formatted_read_group", jsonOf(
                "domain" to orders,
                "groupby" to listOf("date_order:hour"),
                "aggregates" to listOf("amount_total:sum", "__count"),
                "context" to mapOf("tz" to tz.id),
            )).objects()
        }
        val methodsJob = async {
            odoo.call("pos.payment", "formatted_read_group", jsonOf(
                "domain" to listOf(
                    listOf("pos_order_id.config_id", "=", configId),
                    listOf("pos_order_id.state", "in", PAID_STATES),
                    listOf("pos_order_id.date_order", ">=", from),
                    listOf("pos_order_id.date_order", "<", to),
                ),
                "groupby" to listOf("payment_method_id"),
                "aggregates" to listOf("amount:sum", "__count"),
            )).objects()
        }
        val summary = summaryJob.await()
        StoreSales(
            day = day,
            tz = tz,
            total = summary?.sum("amount_total:sum") ?: Paise.ZERO,
            bills = summary?.int("__count") ?: 0,
            firstBillMs = summary?.str("date_order:min")?.let(::parseUtc),
            lastBillMs = summary?.str("date_order:max")?.let(::parseUtc),
            byMethod = methodsJob.await().mapNotNull { g ->
                SalesByMethod(g.m2oName("payment_method_id") ?: return@mapNotNull null, g.sum("amount:sum"), g.int("__count") ?: 0)
            }.sortedByDescending { it.total },
            hours = hoursJob.await().mapNotNull { g ->
                // [start in UTC, label]: the bucket's own start, so the hour is Odoo's, not the till's.
                val start = (g["date_order:hour"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content }?.let(::parseUtc)
                    ?: return@mapNotNull null
                SalesHour(start, g.sum("amount_total:sum"), g.int("__count") ?: 0)
            }.sortedBy { it.startMs },
        )
    }

    /** An aggregate is `false` on an empty group. */
    private fun JsonObject.sum(key: String): Paise = (this[key] as? JsonPrimitive)?.doubleOrNull?.let { Paise.fromOdoo(it) } ?: Paise.ZERO

    companion object {
        /** A sale counts once paid, posted or invoiced; drafts and cancelled orders do not. */
        val PAID_STATES = listOf("paid", "done", "invoiced")

        private fun fmt() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        /** Odoo datetimes are UTC "yyyy-MM-dd HH:mm:ss". */
        fun utc(ms: Long): String = fmt().format(ms)
        fun parseUtc(s: String): Long? = runCatching { fmt().parse(s)?.time }.getOrNull()
    }
}
