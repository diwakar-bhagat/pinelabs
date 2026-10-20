package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise

data class PosConfig(
    val id: Int,
    val name: String,
    val currentSessionId: Int?,
    val sessionOpen: Boolean,
    val paymentMethodIds: List<Int>,
    val companyName: String,
)

data class PaymentMethod(val id: Int, val name: String, val isCash: Boolean, val terminal: String?)

data class Product(
    val id: Int,
    val name: String,
    val price: Paise,          // lst_price (tax-included for GST-included taxes)
    val barcode: String?,
    val defaultCode: String?,
    val taxIds: List<Int>,
)

data class Customer(val id: Int, val name: String, val phone: String?)

data class CartLine(
    val uuid: String,
    val product: Product,
    val qty: Int,
    /** Filled by PricingService from Odoo account.tax.compute_all. */
    val totalIncl: Paise? = null,
    val totalExcl: Paise? = null,
) {
    val isPriced: Boolean get() = totalIncl != null && totalExcl != null
}

data class CartTotals(val totalIncl: Paise, val totalExcl: Paise) {
    val tax: Paise get() = totalIncl - totalExcl
}
