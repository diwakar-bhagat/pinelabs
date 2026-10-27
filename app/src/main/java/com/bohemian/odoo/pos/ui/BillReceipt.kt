package com.bohemian.odoo.pos.ui

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.odoo.OdooBill
import com.bohemian.odoo.pos.odoo.PaidSale
import com.bohemian.odoo.pos.odoo.formatBillMillis
import com.bohemian.odoo.pos.pine.PayMode
import com.bohemian.odoo.pos.receipt.ReceiptData
import com.bohemian.odoo.pos.receipt.ReceiptItem
import com.bohemian.odoo.pos.receipt.ReceiptPayment
import com.bohemian.odoo.pos.receipt.ReceiptZzCash
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.TimeZone

/**
 * What goes on the paper bill. Normally the order exactly as Odoo has it ([fromOdoo]): the same
 * bill number, lines, taxes, payments and ZZ Cash as the PDF Odoo sends on WhatsApp. Only while
 * the order waits in the outbox does the till print its own copy of the sale ([fromSale]).
 */
internal object BillReceipt {

    fun fromOdoo(b: OdooBill, eBillUrl: String?, tz: TimeZone = TimeZone.getDefault()) = ReceiptData(
        storeName = b.storeName,
        companyAddress = null,
        billNo = b.name,
        dateTime = b.localDate(tz),
        customerName = b.customerName,
        customerPhone = b.customerPhone,
        servedBy = b.cashier,
        items = b.lines.map { l ->
            ReceiptItem(
                name = l.name,
                qty = qtyText(l.qty),
                rate = rate(l.totalIncl, l.qty),
                amount = l.totalIncl,
                taxes = l.taxNames.joinToString(", ").ifBlank { null },
                discountPct = l.discount.takeIf { it.signum() != 0 }?.stripTrailingZeros()?.toPlainString(),
            )
        },
        taxable = b.amountTotal - b.amountTax,
        tax = b.amountTax,
        total = b.amountTotal,
        payments = b.payments.map { ReceiptPayment(it.method, it.amount) },
        zzCash = b.zzCash?.let { ReceiptZzCash(it.earned, it.used, it.balance, it.expiry) },
        billRef = b.posReference,
        eBillUrl = eBillUrl,
    )

    /** The till's own copy, for a sale Odoo does not have yet; [billNo] is the till reference. */
    fun fromSale(sale: PaidSale, storeName: String, billNo: String, tz: TimeZone = TimeZone.getDefault()) = ReceiptData(
        storeName = storeName,
        companyAddress = null,
        billNo = billNo,
        dateTime = formatBillMillis(sale.paidAtMillis, tz),
        customerName = sale.customer?.name,
        customerPhone = sale.customer?.phone,
        servedBy = null,
        items = sale.lines.map { l ->
            val amount = l.totalIncl ?: Paise(l.product.price.value * l.qty)
            ReceiptItem(l.product.name, "${l.qty}", rate(amount, BigDecimal(l.qty)), amount)
        } + sale.redemption?.parts.orEmpty().mapNotNull { p ->
            p.totalIncl?.let { ReceiptItem(sale.redemption!!.reward.description, "1", it, it) }
        },
        taxable = sale.totals.totalExcl,
        tax = sale.totals.tax,
        total = sale.totals.totalIncl,
        payments = sale.payments.map { ReceiptPayment(methodName(it.mode), it.amount) },
        pendingInOdoo = true,
    )

    private fun methodName(mode: PayMode?) = when (mode) {
        null -> "Cash"
        PayMode.CARD -> "Card"
        PayMode.UPI -> "UPI"
        PayMode.BHARAT_QR -> "Bharat QR"
    }

    /** The template's rate: amount ÷ qty, two decimals. */
    fun rate(amount: Paise, qty: BigDecimal): Paise =
        if (qty.signum() == 0) Paise.ZERO else Paise.fromRupees(amount.toRupees().divide(qty, 2, RoundingMode.HALF_UP))

    /** "1" for whole units, "0.5" otherwise (the template's `int(l.qty) if l.qty == int(l.qty)`). */
    fun qtyText(qty: BigDecimal): String = qty.stripTrailingZeros().let { if (it.scale() <= 0) it.toBigInteger().toString() else it.toPlainString() }
}
