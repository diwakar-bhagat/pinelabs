package com.bohemian.odoo.pos.payment

import com.bohemian.odoo.pos.core.Paise
import java.net.URLEncoder

/**
 * Where a printed UPI QR sends money. The business has two collection IDs on Pine's account:
 * [primary] is used normally, [fallback] is used when the primary's bank is down.
 *
 * The IDs are business payment identifiers, so they come from device settings and are never
 * committed to this public repo. A printed QR is static, so the choice is made when the bill
 * is printed. It cannot switch by itself after that.
 */
data class UpiPayee(
    val primary: String,
    val fallback: String? = null,
    val useFallback: Boolean = false,
) {
    init {
        require(isValidVpa(primary)) { "primary UPI ID is not a valid VPA" }
        require(fallback == null || isValidVpa(fallback)) { "fallback UPI ID is not a valid VPA" }
    }

    /** The VPA to print on this bill. Falls back only when asked and when a fallback exists. */
    fun activeVpa(): String = if (useFallback && fallback != null) fallback else primary

    companion object {
        private val VPA = Regex("^[A-Za-z0-9._-]{2,256}@[A-Za-z]{2,64}$")
        fun isValidVpa(s: String): Boolean = VPA.matches(s.trim())
    }
}

/**
 * Standard UPI deep link (NPCI spec): upi://pay?pa=&pn=&am=&cu=&tn=. Encoded so the name and
 * note can contain spaces and symbols. The bill reference goes in `tn` so a payment can be
 * matched back to the sale.
 */
object UpiUri {
    fun build(payee: UpiPayee, payeeName: String, amount: Paise, billRef: String): String {
        require(amount.isPositive) { "UPI amount must be positive" }
        val note = billRef.take(80)
        return "upi://pay" +
            "?pa=" + enc(payee.activeVpa()) +
            "&pn=" + enc(payeeName.take(80)) +
            "&am=" + enc(amount.toPlainString()) +
            "&cu=INR" +
            "&tn=" + enc(note)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
