package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.core.PosLog
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * A sale as Odoo has it, read back right after it syncs: the records and fields the "ZenZebra Bill"
 * report (QWeb `x_zenzebra.pos_bill`) puts in the PDF sent to the customer on WhatsApp, so the
 * paper bill and the PDF say the same thing.
 */
data class OdooBill(
    /** pos.order.name, e.g. "ZenZebra - 000020" (the template's "Bill No."). */
    val name: String,
    /** pos.order.pos_reference, e.g. "260-1-000020" ("Bill ref"). */
    val posReference: String?,
    /** pos.order.date_order, UTC as Odoo stores it ("2026-10-27 12:04:16"). */
    val dateOrderUtc: String,
    val storeName: String,
    val customerName: String?,
    /** res.partner.phone as typed in Odoo ("+91 98765 43210"), printed like the PDF. */
    val customerPhone: String?,
    /** pos.order.cashier ("Served by"). */
    val cashier: String?,
    val lines: List<OdooBillLine>,
    val amountTotal: Paise,
    val amountTax: Paise,
    val payments: List<OdooBillPayment>,
    val accessToken: String?,
    /** The template's ZZ Cash block; null when Odoo hides it (`x_zz_show` false) or it cannot be read. */
    val zzCash: OdooBillZzCash?,
) {
    /** "27 Oct 2026, 05:34 pm" in the terminal's time zone (the template's `dd MMM yyyy, hh:mm a`). */
    fun localDate(tz: TimeZone = TimeZone.getDefault()): String = formatBillDate(dateOrderUtc, tz)
}

/** One pos.order.line: reward lines included (negative), as the template lists `o.lines`. */
data class OdooBillLine(
    val name: String,
    val qty: BigDecimal,
    val discount: BigDecimal,
    /** account.tax names of the line, e.g. "5% GST S". */
    val taxNames: List<String>,
    /** price_subtotal_incl: the line amount, tax included. */
    val totalIncl: Paise,
)

data class OdooBillPayment(val method: String, val amount: Paise)

/** The `x_zz_*` texts Odoo computes for the bill (earned, used, balance after this sale, expiry). */
data class OdooBillZzCash(val earned: String, val used: String?, val balance: String, val expiry: String?)

/** UTC "yyyy-MM-dd HH:mm:ss" → "dd MMM yyyy, hh:mm a" in [tz]; the raw text if Odoo sent another shape. */
fun formatBillDate(utc: String, tz: TimeZone): String {
    val parsed = runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(utc)
    }.getOrNull() ?: return utc
    return formatBillMillis(parsed.time, tz)
}

/** The bill's date format ("27 Oct 2026, 05:34 pm") for a time on the terminal clock. */
fun formatBillMillis(millis: Long, tz: TimeZone = TimeZone.getDefault()): String =
    SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale("en", "IN")).apply { timeZone = tz }.format(java.util.Date(millis))

/** Names the till already knows, for the ids in Odoo's sync reply. */
data class KnownNames(
    val storeName: String,
    val customerName: String?,
    val customerPhone: String?,
    val taxNames: Map<Int, String>,
    val methodNames: Map<Int, String>,
)

class BillService(private val odoo: OdooJson2Client) {

    /**
     * The bill from Odoo's answer to `sync_from_ui` ([reply]: `read_pos_data`, i.e. `pos.order` and
     * `pos.payment` with every field and `pos.order.line` with the POS fields, links as plain ids;
     * checked against a live order, 2026-10-30), so a sale costs no read-back. Names the reply only has
     * as ids come from [known]. The ZZ Cash texts are read once, after the loyalty booking, and only
     * for a customer's bill. Null when the reply lacks anything the bill needs: then [bill] reads it.
     */
    suspend fun billFromReply(uuid: String, reply: JsonObject, known: KnownNames): OdooBill? {
        val o = reply["pos.order"]?.objects()?.firstOrNull { it.str("uuid") == uuid } ?: return null
        val orderId = o.int("id") ?: return null
        val lines = reply["pos.order.line"]?.objects().orEmpty().filter { it.int("order_id") == orderId }.sortedBy { it.int("id") }
        val payments = reply["pos.payment"]?.objects().orEmpty().filter { it.int("pos_order_id") == orderId }.sortedBy { it.int("id") }
        if (lines.isEmpty() || payments.isEmpty()) return null
        val partnerId = o.int("partner_id")
        if (partnerId != null && known.customerName == null) return null
        val billLines = lines.map { l ->
            OdooBillLine(
                name = l.str("full_product_name")?.takeIf { it.isNotBlank() } ?: return null,
                qty = l.decimal("qty"),
                discount = l.decimal("discount"),
                taxNames = l.ids("tax_ids").map { known.taxNames[it] ?: return null }.filter { it.isNotBlank() },
                totalIncl = l.money("price_subtotal_incl"),
            )
        }
        val billPayments = payments.map { p ->
            OdooBillPayment(known.methodNames[p.int("payment_method_id")] ?: return null, p.money("amount"))
        }
        val zz = if (partnerId == null) null else runCatching { zzCash(orderId) }
            .onFailure { PosLog.w("BILL_ZZ_UNREADABLE", "uuid" to uuid, "err" to it.message) }.getOrNull()
        return OdooBill(
            name = o.str("name") ?: return null,
            posReference = o.str("pos_reference"),
            dateOrderUtc = o.str("date_order") ?: return null,
            storeName = known.storeName,
            customerName = if (partnerId == null) null else known.customerName,
            customerPhone = if (partnerId == null) null else known.customerPhone,
            cashier = o.str("cashier"),
            lines = billLines,
            amountTotal = o.money("amount_total"),
            amountTax = o.money("amount_tax"),
            payments = billPayments,
            accessToken = o.str("access_token")?.takeIf { it.isNotBlank() },
            zzCash = zz,
        )
    }

    /**
     * The bill of the order with [uuid], or null when Odoo does not have the order yet (outbox).
     * After the order itself, lines, payments and the ZZ Cash texts are read one after another: four
     * calls at once go past Odoo Online's burst and come back as 429s (peak simulation, 2026-10-30).
     * [knownPhone] (the attached customer's res.partner.phone) and [knownTaxNames] (from the
     * catalogue) save a round trip each; anything not known is read from Odoo.
     */
    suspend fun bill(uuid: String, knownPhone: String? = null, knownTaxNames: Map<Int, String> = emptyMap()): OdooBill? = coroutineScope {
        val o = odoo.call("pos.order", "search_read", jsonOf(
            "domain" to listOf(listOf("uuid", "=", uuid)),
            "fields" to ORDER_FIELDS,
            "limit" to 1,
        )).objects().firstOrNull() ?: return@coroutineScope null
        val lineIds = o.ids("lines")
        val paymentIds = o.ids("payment_ids")
        val lines =
            if (lineIds.isEmpty()) emptyList() else odoo.call("pos.order.line", "read", jsonOf(
                "ids" to lineIds, "fields" to listOf("full_product_name", "product_id", "qty", "price_subtotal_incl", "discount", "tax_ids"),
            )).objects()
        val payments =
            if (paymentIds.isEmpty()) emptyList() else odoo.call("pos.payment", "read", jsonOf(
                "ids" to paymentIds, "fields" to listOf("payment_method_id", "amount"),
            )).objects()
        val phone =
            knownPhone?.takeIf { it.isNotBlank() } ?: o.m2oId("partner_id")?.let { pid ->
                odoo.call("res.partner", "read", jsonOf("ids" to listOf(pid), "fields" to listOf("phone")))
                    .objects().firstOrNull()?.str("phone")
            }
        val zz =
            runCatching { zzCash(o.int("id")!!) }
                .onFailure { PosLog.w("BILL_ZZ_UNREADABLE", "uuid" to uuid, "err" to it.message) }.getOrNull()
        val missingTaxIds = lines.flatMap { it.ids("tax_ids") }.distinct().filter { it !in knownTaxNames }
        val taxNames = knownTaxNames + if (missingTaxIds.isEmpty()) emptyMap() else odoo.call("account.tax", "read", jsonOf(
            "ids" to missingTaxIds, "fields" to listOf("name"),
        )).objects().associate { it.int("id")!! to (it.str("name") ?: "") }
        OdooBill(
            name = o.str("name") ?: uuid.take(8),
            posReference = o.str("pos_reference"),
            dateOrderUtc = o.str("date_order") ?: "",
            storeName = o.m2oName("config_id") ?: "",
            customerName = o.m2oName("partner_id"),
            customerPhone = phone,
            cashier = o.str("cashier"),
            lines = lines.map { l ->
                OdooBillLine(
                    name = l.str("full_product_name")?.takeIf { it.isNotBlank() } ?: l.m2oName("product_id") ?: "?",
                    qty = l.decimal("qty"),
                    discount = l.decimal("discount"),
                    taxNames = l.ids("tax_ids").mapNotNull { taxNames[it]?.takeIf { n -> n.isNotBlank() } },
                    totalIncl = l.money("price_subtotal_incl"),
                )
            },
            amountTotal = o.money("amount_total"),
            amountTax = o.money("amount_tax"),
            payments = payments.map { OdooBillPayment(it.m2oName("payment_method_id") ?: "?", it.money("amount")) },
            accessToken = o.str("access_token")?.takeIf { it.isNotBlank() },
            zzCash = zz,
        )
    }

    /** account.tax id → name for [ids], for the bill's tax line under each item (read once per catalogue). */
    suspend fun taxNames(ids: Collection<Int>): Map<Int, String> =
        if (ids.isEmpty()) emptyMap() else odoo.call("account.tax", "read", jsonOf("ids" to ids.toList(), "fields" to listOf("name")))
            .objects().associate { it.int("id")!! to (it.str("name") ?: "") }

    /**
     * The ZZ Cash block. `x_zz_*` are this database's own computed fields (not stored, so they can be
     * read but not searched); read apart, so if one is renamed only this block is left out.
     */
    private suspend fun zzCash(orderId: Int): OdooBillZzCash? {
        val z = odoo.call("pos.order", "read", jsonOf("ids" to listOf(orderId), "fields" to ZZ_FIELDS))
            .objects().firstOrNull() ?: return null
        if (!z.bool("x_zz_show")) return null
        val used = (z["x_zz_used"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        return OdooBillZzCash(
            earned = z.str("x_zz_earned_txt") ?: "0",
            used = z.str("x_zz_used_txt")?.takeIf { used != 0.0 },
            balance = z.str("x_zz_balance_txt") ?: "0",
            expiry = z.str("x_zz_expiry")?.takeIf { it.isNotBlank() },
        )
    }

    /** The template's address line: "street, street2, city zip" of the company. */
    suspend fun companyAddress(companyId: Int): String? =
        odoo.call("res.company", "read", jsonOf("ids" to listOf(companyId), "fields" to listOf("street", "street2", "city", "zip")))
            .objects().firstOrNull()?.let { addressLine(it.str("street"), it.str("street2"), it.str("city"), it.str("zip")) }

    /** res.company.logo (base64), for printing at the top of the bill when the Setup switch is on. */
    suspend fun companyLogo(companyId: Int): String? =
        odoo.call("res.company", "read", jsonOf("ids" to listOf(companyId), "fields" to listOf("logo")))
            .objects().firstOrNull()?.str("logo")?.takeIf { it.isNotBlank() }

    companion object {
        private val ORDER_FIELDS = listOf(
            "name", "pos_reference", "date_order", "partner_id", "cashier", "config_id",
            "amount_total", "amount_tax", "lines", "payment_ids", "access_token",
        )
        private val ZZ_FIELDS = listOf("x_zz_show", "x_zz_earned_txt", "x_zz_used", "x_zz_used_txt", "x_zz_balance_txt", "x_zz_expiry")

        internal fun addressLine(street: String?, street2: String?, city: String?, zip: String?): String? {
            val place = listOfNotNull(city, zip).filter { it.isNotBlank() }.joinToString(" ")
            return listOfNotNull(street, street2, place).map { it.trim() }.filter { it.isNotBlank() }
                .joinToString(", ").ifBlank { null }
        }

        private fun JsonObject.decimal(key: String): BigDecimal =
            (this[key] as? JsonPrimitive)?.doubleOrNull?.let { BigDecimal(it.toString()) } ?: BigDecimal.ZERO
    }
}
