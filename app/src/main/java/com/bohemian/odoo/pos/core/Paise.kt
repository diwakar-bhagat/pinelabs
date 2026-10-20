package com.bohemian.odoo.pos.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Money in the lowest INR unit. HARD RULE 1: never use Double/Float for amounts.
 * Odoo sends floats; convert once at the boundary with [fromOdoo].
 */
@JvmInline
value class Paise(val value: Long) : Comparable<Paise> {

    operator fun plus(other: Paise) = Paise(Math.addExact(value, other.value))
    operator fun minus(other: Paise) = Paise(Math.subtractExact(value, other.value))
    operator fun times(qty: Int) = Paise(Math.multiplyExact(value, qty.toLong()))
    override fun compareTo(other: Paise): Int = value.compareTo(other.value)

    val isZero: Boolean get() = value == 0L
    val isPositive: Boolean get() = value > 0L

    /** Rupees as an exact decimal, e.g. 52800 -> 528.00 */
    fun toRupees(): BigDecimal = BigDecimal.valueOf(value, 2)

    /** "528.00" */
    fun toPlainString(): String = toRupees().toPlainString()

    /** "₹528.00" (negative: "-₹5.00") */
    fun format(): String = if (value < 0) "-₹" + Paise(-value).toPlainString() else "₹" + toPlainString()

    override fun toString(): String = format()

    companion object {
        val ZERO = Paise(0)

        fun ofRupees(rupees: Long) = Paise(Math.multiplyExact(rupees, 100L))

        fun fromRupees(rupees: BigDecimal): Paise =
            Paise(rupees.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact())

        /**
         * Odoo monetary floats -> Paise. Goes through the shortest decimal string
         * (Double.toString) so 1.15 becomes 115, not 114.999…
         */
        fun fromOdoo(value: Double): Paise = fromRupees(BigDecimal(value.toString()))

        /** Parse "528", "528.5", "528.50" (cashier input). Returns null when invalid. */
        fun parse(text: String): Paise? =
            text.trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
                ?.takeIf { it.scale() <= 2 }
                ?.let { fromRupees(it) }
    }
}

fun Iterable<Paise>.sum(): Paise = fold(Paise.ZERO) { acc, p -> acc + p }
