package com.bohemian.odoo.pos.receipt

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.pine.PrintLine

data class ZReportMethod(val name: String, val count: Int, val amount: Paise)

data class ZReportCash(val name: String, val opening: Paise, val sales: Paise, val moves: Paise, val expected: Paise, val counted: Paise?) {
    val difference: Paise? get() = counted?.let { it - expected }
}

/** End-of-day session report. All figures come from Odoo (get_closing_control_data). */
data class ZReportData(
    val storeName: String,
    val configName: String,
    val sessionName: String,
    val printedAt: String,
    val orderCount: Int,
    val orderTotal: Paise,
    val cash: ZReportCash?,
    val methods: List<ZReportMethod>,
    val notes: String?,
)

/** Pure text layout of the Z-report, same width rules as [ReceiptFormatter]. */
class ZReportFormatter(private val width: Int) {
    private val layout = ReceiptFormatter(width)

    fun format(z: ZReportData): List<PrintLine> = buildList {
        fun center(s: String, bold: Boolean = false) = layout.wrap(s).forEach { add(PrintLine(it, center = true, bold = bold)) }
        fun left(s: String) = layout.wrap(s).forEach { add(PrintLine(it)) }
        fun kv(k: String, v: String) = add(PrintLine(layout.twoCol(k, v)))
        fun rule(c: Char = '-') = add(PrintLine(c.toString().repeat(width)))

        center("Z-REPORT", bold = true)
        center(z.storeName, bold = true) // "ZenZebra" as written
        center(z.configName)
        rule('=')
        left("Session: ${z.sessionName}")
        left("Printed: ${z.printedAt}")
        rule()
        kv("Orders", z.orderCount.toString())
        kv("Sales total", z.orderTotal.toPlainString())
        rule()
        z.cash?.let { c ->
            left(c.name.uppercase())
            kv("  Opening", c.opening.toPlainString())
            kv("  Cash sales", c.sales.toPlainString())
            if (!c.moves.isZero) kv("  Cash in/out", c.moves.toPlainString())
            kv("  Expected", c.expected.toPlainString())
            c.counted?.let { kv("  Counted", it.toPlainString()) }
            c.difference?.let { kv("  Difference", it.toPlainString()) }
            rule()
        }
        for (m in z.methods) {
            left(m.name.uppercase())
            kv("  ${m.count} payment(s)", m.amount.toPlainString())
        }
        if (z.methods.isNotEmpty()) rule()
        z.notes?.takeIf { it.isNotBlank() }?.let { left("Notes: $it"); rule() }
        center("Cashier sign: ____________")
    }
}
