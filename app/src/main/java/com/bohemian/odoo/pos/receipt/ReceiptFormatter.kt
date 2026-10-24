package com.bohemian.odoo.pos.receipt

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.pine.PrintLine

data class ReceiptItem(val name: String, val qty: Int, val unitPrice: Paise, val total: Paise)

data class ReceiptData(
    val storeName: String,
    val storeLines: List<String>,          // address, GSTIN…
    val orderRef: String,
    val dateTime: String,
    val items: List<ReceiptItem>,
    val subtotalExcl: Paise,
    val tax: Paise,
    val total: Paise,
    val paymentLabel: String,              // "CARD", "UPI", "BHARAT QR", "CASH"
    val paymentRef: String?,               // RRN / txn id
    val authCode: String?,
    val cardLast4: String?,
    val customerName: String?,
    val footer: List<String>,
    val reprint: Boolean = false,
    /** E-bill link (Odoo ticket URL) printed as a QR code. Null when the order is not yet in Odoo. */
    val eBillUrl: String? = null,
)

/**
 * Pure text layout for the E700 printer. Pine: line width is 24, 32 or 48 characters.
 * Guarantees: no line exceeds [width]; long names wrap; amounts right-aligned.
 */
class ReceiptFormatter(private val width: Int) {
    init { require(width in setOf(24, 32, 48)) { "printer width must be 24, 32 or 48" } }

    fun format(r: ReceiptData): List<PrintLine> = buildList {
        fun center(s: String, bold: Boolean = false) = wrap(s).forEach { add(PrintLine(it.centerIn(width), center = true, bold = bold)) }
        fun left(s: String) = wrap(s).forEach { add(PrintLine(it)) }
        fun kv(k: String, v: String) = add(PrintLine(twoCol(k, v)))
        fun rule(c: Char = '-') = add(PrintLine(c.toString().repeat(width)))

        if (r.reprint) center("** REPRINT **", bold = true)
        center(r.storeName.uppercase(), bold = true)
        r.storeLines.forEach { center(it) }
        rule('=')
        left("Bill: ${r.orderRef}")
        left("Date: ${r.dateTime}")
        r.customerName?.takeIf { it.isNotBlank() }?.let { left("Customer: $it") }
        rule()
        for (item in r.items) {
            wrap(item.name).forEach { l -> add(PrintLine(l)) }
            kv("  ${item.qty} x ${item.unitPrice.toPlainString()}", item.total.toPlainString())
        }
        rule()
        kv("Subtotal (excl. tax)", r.subtotalExcl.toPlainString())
        kv("GST", r.tax.toPlainString())
        rule()
        kv("TOTAL", "Rs " + r.total.toPlainString())
        rule()
        kv("Paid by", r.paymentLabel)
        r.cardLast4?.let { kv("Card", "**** $it") }
        r.paymentRef?.let { kv("Ref", it) }
        r.authCode?.let { kv("Auth", it) }
        rule('=')
        r.eBillUrl?.let {
            add(PrintLine("", center = true, qrText = it))
            center("Scan for your e-bill")
        }
        r.footer.forEach { center(it) }
    }

    /** "Subtotal ........ 490.00" — value always fully visible, label truncated if needed. */
    internal fun twoCol(label: String, value: String): String {
        val v = value.take(width)
        val room = width - v.length - 1
        if (room <= 0) return v.padStart(width)
        val l = if (label.length > room) label.take(room) else label
        return l + " ".repeat(width - l.length - v.length) + v
    }

    /** Word-wrap to [width]; words longer than a line are hard-split. */
    internal fun wrap(text: String): List<String> {
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (word in text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            var w = word
            while (w.length > width) {
                if (line.isNotEmpty()) { out += line.toString(); line = StringBuilder() }
                out += w.take(width); w = w.drop(width)
            }
            when {
                line.isEmpty() -> line.append(w)
                line.length + 1 + w.length <= width -> line.append(' ').append(w)
                else -> { out += line.toString(); line = StringBuilder(w) }
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
