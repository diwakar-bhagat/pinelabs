package com.bohemian.odoo.pos.receipt

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.pine.PrintLine

/**
 * The fixed words of Odoo's "ZenZebra Bill" report (QWeb `x_zenzebra.pos_bill`, ir.ui.view 3800,
 * last changed 2026-10-09): the PDF sent to customers on WhatsApp. The paper bill prints the same
 * words. When the report's text changes in Odoo, change it here too.
 */
object ZenZebraBill {
    const val BRAND = "ZENZEBRA"
    const val LEGAL_NAME = "Bohemian Curations Private Limited"
    val TERMS = listOf(
        "Prices inclusive of GST. ZZ Cash: Rs 1 = Rs 1 off, not transferable or encashable, programme terms may change.",
        "Exchange within 7 days with this bill: clothing unworn with tags, other items sealed and unused. " +
            "No cash refunds. No exchange on food, beverages, cosmetics, fragrance, jewellery, innerwear or discounted items. " +
            "Cashback earned on an exchanged item is reversed; cashback used on it is re-credited.",
    )
    const val THANKS = "Thanks for stopping by. See you at the breakspot."
    val ZZ_EXPLAINER = listOf(
        "You earn 6% on drinks & cafe, 8% on ZenZebra own-label & jewellery, 5% on beauty, fragrance, bags & clothing, " +
            "2% on snacks. Bills of Rs 150+ earn 1.25x, Rs 500+ earn 1.5x.",
        "Use your balance in Rs 50 chunks on any bill of Rs 100 or more. It lasts 30 days from your last purchase; " +
            "every purchase keeps it alive for 30 more.",
    )
}

/** A row of the bill's item table (Item / Qty / Rate / Amount); [taxes] prints under the name. */
data class ReceiptItem(
    val name: String,
    /** "1", or "0.5" for a part quantity, as the template prints it. */
    val qty: String,
    val rate: Paise,
    val amount: Paise,
    val taxes: String? = null,
    /** "10" for a 10 % line discount; null when none. */
    val discountPct: String? = null,
)

data class ReceiptPayment(val method: String, val amount: Paise)

/** What the E700 adds for a card / UPI payment (not on Odoo's PDF; the customer needs it for disputes). */
data class TerminalSlip(val mode: String, val cardLast4: String?, val rrn: String?, val authCode: String?)

data class ReceiptZzCash(val earned: String, val used: String?, val balance: String, val expiry: String?)

/**
 * One bill, in the order of Odoo's "ZenZebra Bill" report. Filled from the order as Odoo has it
 * ([com.bohemian.odoo.pos.odoo.OdooBill]); from the till's own sale only while Odoo does not have
 * the order yet ([pendingInOdoo]).
 */
data class ReceiptData(
    /** pos.config name: "Store: ZenZebra". */
    val storeName: String,
    val companyAddress: String?,
    /** pos.order.name ("ZenZebra - 000020"), or the till's reference while the order is not in Odoo. */
    val billNo: String,
    val dateTime: String,
    val customerName: String?,
    val customerPhone: String?,
    val servedBy: String?,
    val items: List<ReceiptItem>,
    /** Taxable value = total − GST, as the template computes it. */
    val taxable: Paise,
    val tax: Paise,
    val total: Paise,
    val payments: List<ReceiptPayment>,
    val terminal: TerminalSlip? = null,
    val zzCash: ReceiptZzCash? = null,
    /** pos.order.pos_reference ("Bill ref: 260-1-000020"). */
    val billRef: String? = null,
    /** The order is still in the outbox: Odoo's bill number and ZZ Cash come with the WhatsApp bill. */
    val pendingInOdoo: Boolean = false,
    val reprint: Boolean = false,
    /** E-bill link (Odoo ticket URL) printed as a QR code. Null when the order is not yet in Odoo. */
    val eBillUrl: String? = null,
    /** Company logo (base64 PNG) for the top of the bill; null unless switched on in Setup. */
    val logoBase64: String? = null,
)

/**
 * Pure text layout for the E700 printer, mirroring Odoo's bill. Pine: line width is 24, 32 or 48
 * characters. Guarantees: no line exceeds [width]; long names and values wrap; amounts right-aligned.
 */
class ReceiptFormatter(private val width: Int) {
    init { require(width in setOf(24, 32, 48)) { "printer width must be 24, 32 or 48" } }

    /** Qty / Rate / Amount column widths for this paper. */
    private val qtyW = when (width) { 24 -> 4; 32 -> 5; else -> 7 }
    private val rateW = when (width) { 24 -> 9; 32 -> 11; else -> 13 }
    private val amountW = when (width) { 24 -> 10; 32 -> 12; else -> 14 }

    fun format(r: ReceiptData): List<PrintLine> = buildList {
        fun center(s: String, bold: Boolean = false) = wrap(s).forEach { add(PrintLine(it.centerIn(width), center = true, bold = bold)) }
        fun left(s: String, indent: Int = 0) =
            wrapAt(s, width - indent).forEach { add(PrintLine(" ".repeat(indent) + it)) }
        fun kv(k: String, v: String, bold: Boolean = false) = keyValue(k, v).forEach { add(PrintLine(it, bold = bold)) }
        fun rule(c: Char = '-') = add(PrintLine(c.toString().repeat(width)))
        fun rs(p: Paise) = "Rs " + p.toPlainString()

        r.logoBase64?.let { add(PrintLine("", center = true, imageBase64 = it)) }
        if (r.reprint) center("** REPRINT **", bold = true)
        center(ZenZebraBill.BRAND, bold = true)
        center(ZenZebraBill.LEGAL_NAME)
        r.companyAddress?.let { center(it) }
        center("Store: ${r.storeName}", bold = true)
        rule()
        kv("Bill No.", r.billNo, bold = true)
        kv("Date", r.dateTime)
        r.customerName?.takeIf { it.isNotBlank() }?.let { name ->
            kv("Customer", name + (r.customerPhone?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""))
        }
        r.servedBy?.takeIf { it.isNotBlank() }?.let { kv("Served by", it) }
        rule()
        // 24-char paper has no room for "Item" beside the number columns: it goes on its own line.
        if (width - (qtyW + rateW + amountW) >= "Item".length) add(PrintLine(columns("Item", "Qty", "Rate", "Amount")))
        else { add(PrintLine("Item")); add(PrintLine(columns("", "Qty", "Rate", "Amount"))) }
        rule('=')
        for (item in r.items) {
            left(item.name)
            item.discountPct?.let { left("Discount $it%", indent = 2) }
            item.taxes?.takeIf { it.isNotBlank() }?.let { left(it, indent = 2) }
            add(PrintLine(columns("", item.qty, item.rate.toPlainString(), item.amount.toPlainString())))
        }
        rule()
        kv("Taxable value", rs(r.taxable))
        kv("GST", rs(r.tax))
        rule('=')
        kv("TOTAL", rs(r.total), bold = true)
        r.payments.forEach { kv("Paid via ${it.method}", rs(it.amount)) }
        r.terminal?.let { t ->
            kv("Mode", t.mode)
            t.cardLast4?.let { kv("Card", "**** $it") }
            t.rrn?.let { kv("RRN", it) }
            t.authCode?.let { kv("Auth", it) }
        }
        r.zzCash?.let { z ->
            rule()
            center("ZZ Cash", bold = true)
            kv("Earned on this bill", "+ Rs ${z.earned}")
            z.used?.let { kv("Used on this bill", "- Rs $it") }
            kv("Balance", "Rs ${z.balance}", bold = true)
            z.expiry?.let { kv("Valid till", it) }
            ZenZebraBill.ZZ_EXPLAINER.forEach { left(it) }
        }
        rule()
        ZenZebraBill.TERMS.forEach { center(it) }
        center(ZenZebraBill.THANKS, bold = true)
        r.billRef?.let { center("Bill ref: $it") }
        if (r.pendingInOdoo) center("Odoo bill follows on WhatsApp.")
        r.eBillUrl?.let {
            add(PrintLine("", center = true, qrText = it))
            center("Scan for your e-bill")
        }
    }

    /** "Taxable value ...... Rs 4.76"; when both do not fit, the label, then the value right-aligned below. */
    internal fun keyValue(label: String, value: String): List<String> {
        if (label.length + 1 + value.length <= width) return listOf(twoCol(label, value))
        return listOf(label.take(width)) + wrap(value).map { it.padStart(width) }
    }

    /** "Subtotal ........ 490.00" — value always fully visible, label truncated if needed. */
    internal fun twoCol(label: String, value: String): String {
        val v = value.take(width)
        val room = width - v.length - 1
        if (room <= 0) return v.padStart(width)
        val l = if (label.length > room) label.take(room) else label
        return l + " ".repeat(width - l.length - v.length) + v
    }

    /** One table row: [first] left, then Qty, Rate, Amount right-aligned in their columns. */
    internal fun columns(first: String, qty: String, rate: String, amount: String): String {
        val nums = qty.padStart(qtyW) + rate.padStart(rateW) + amount.padStart(amountW)
        val room = (width - nums.length).coerceAtLeast(0)
        return (first.take(room).padEnd(room) + nums).takeLast(width)
    }

    /** Word-wrap to [width]; words longer than a line are hard-split. */
    internal fun wrap(text: String): List<String> = wrapAt(text, width)

    private fun wrapAt(text: String, w: Int): List<String> {
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (word in text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            var rest = word
            while (rest.length > w) {
                if (line.isNotEmpty()) { out += line.toString(); line = StringBuilder() }
                out += rest.take(w); rest = rest.drop(w)
            }
            when {
                line.isEmpty() -> line.append(rest)
                line.length + 1 + rest.length <= w -> line.append(' ').append(rest)
                else -> { out += line.toString(); line = StringBuilder(rest) }
            }
        }
        if (line.isNotEmpty() || out.isEmpty()) out += line.toString()
        return out
    }

    private fun String.centerIn(w: Int): String {
        val pad = (w - length).coerceAtLeast(0)
        return " ".repeat(pad / 2) + this
    }
}
