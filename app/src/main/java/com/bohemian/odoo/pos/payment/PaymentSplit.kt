package com.bohemian.odoo.pos.payment

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.odoo.PaymentMethod

/**
 * Split payment on one bill, following the Odoo POS payment screen
 * (addons/point_of_sale/static/src/app/screens/payment_screen/payment_screen.js, Odoo 19.0):
 *
 * - Due = total − paid. A line can be added in any method; it is pre-filled with the due amount.
 * - A non-cash line cannot exceed the due amount ("The amount cannot be higher than the due
 *   amount if you don't have a cash payment method configured").
 * - Only cash can overpay. The overpay is the change handed back.
 * - The bill is complete when paid ≥ total.
 *
 * Immutable: every change returns a new [PaymentSplit], so the UI state can be a plain StateFlow.
 */
data class SplitLine(val uuid: String, val method: PaymentMethod, val amount: Paise)

data class PaymentSplit(
    val total: Paise,
    val lines: List<SplitLine> = emptyList(),
    val selectedUuid: String? = null,
) {
    val paid: Paise get() = lines.fold(Paise.ZERO) { acc, l -> acc + l.amount }

    /** Odoo's remainingDue: negative means change is owed. */
    val due: Paise get() = total - paid

    /** Amount still to collect (zero once the bill is covered). */
    val remainingDue: Paise get() = if (due.isPositive) due else Paise.ZERO

    /** Cash handed back. Only cash can overpay, so this is only ever positive with a cash line. */
    val change: Paise get() = if (due.isPositive) Paise.ZERO else Paise(-due.value)

    val isComplete: Boolean get() = paid >= total && total.isPositive

    val selected: SplitLine? get() = lines.firstOrNull { it.uuid == selectedUuid }

    /**
     * Add a payment line and select it. [amount] defaults to the remaining due, as Odoo does.
     * Returns a [Result] error when the amount breaks the rules, so the UI can say why.
     */
    fun add(uuid: String, method: PaymentMethod, amount: Paise? = null): Result<PaymentSplit> {
        if (lines.any { it.uuid == uuid }) return Result.failure(IllegalArgumentException("duplicate line"))
        val value = amount ?: remainingDue
        val checked = validate(method, value, currentLine = null) ?: return Result.failure(IllegalArgumentException(error(method, value)))
        return Result.success(copy(lines = lines + SplitLine(uuid, method, checked), selectedUuid = uuid))
    }

    /** Set the amount of an existing line. Same rules as [add]. */
    fun setAmount(uuid: String, amount: Paise): Result<PaymentSplit> {
        val line = lines.firstOrNull { it.uuid == uuid }
            ?: return Result.failure(IllegalArgumentException("no such line"))
        val checked = validate(line.method, amount, currentLine = line) ?: return Result.failure(IllegalArgumentException(error(line.method, amount)))
        return Result.success(copy(lines = lines.map { if (it.uuid == uuid) it.copy(amount = checked) else it }))
    }

    /** Quick buttons (+10, +20, +50): add to the selected line, as the Odoo numpad does. */
    fun quickAdd(deltaRupees: Long): Result<PaymentSplit> {
        val line = selected ?: return Result.failure(IllegalArgumentException("select a payment line first"))
        return setAmount(line.uuid, Paise(line.amount.value + Paise.ofRupees(deltaRupees).value))
    }

    fun remove(uuid: String): PaymentSplit {
        val rest = lines.filterNot { it.uuid == uuid }
        val sel = if (selectedUuid == uuid) rest.lastOrNull()?.uuid else selectedUuid
        return copy(lines = rest, selectedUuid = sel)
    }

    fun select(uuid: String): PaymentSplit =
        if (lines.any { it.uuid == uuid }) copy(selectedUuid = uuid) else this

    /** Null when the amount is allowed; otherwise the line stays unchanged. */
    private fun validate(method: PaymentMethod, amount: Paise, currentLine: SplitLine?): Paise? {
        if (amount.value < 0) return null
        if (method.isCash) return amount // cash may overpay → change
        // Non-cash: cannot exceed what is still due, counting this line's own current amount.
        val allowed = remainingDue + (currentLine?.amount ?: Paise.ZERO)
        return if (amount <= allowed) amount else null
    }

    private fun error(method: PaymentMethod, amount: Paise): String = when {
        amount.value < 0 -> "Amount cannot be negative"
        !method.isCash -> "The amount cannot be higher than the due amount if you don't have a cash payment method configured."
        else -> "Amount rejected"
    }
}
