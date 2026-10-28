package com.bohemian.odoo.pos.odoo

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** The parts of the till a change in Odoo can touch; each is refreshed on its own. */
enum class LiveArea { PRODUCTS, MENU, TAXES, LOYALTY, NOTES, COMPANY, BILL_TEXT }

/**
 * The newest `write_date` of each watched model, Odoo's own clock (the device clock never
 * matters). Archived records count too: archiving a product or a program is a change.
 */
data class LiveStamps(val byModel: Map<String, String?>) {

    fun changedSince(old: LiveStamps): Set<LiveArea> =
        LiveSync.WATCHED.filter { (model, _) -> byModel[model] != old.byModel[model] }.map { it.value }.toSet()

    /** Products are fetched from here on: the older of the two product stamps of the last look. */
    val productsSince: String? get() = listOfNotNull(byModel["product.product"], byModel["product.template"]).minOrNull()
}

/**
 * Live Odoo data (round 3): one cheap read per watched model (0.15–0.35 s each, in parallel) says
 * what changed since the last look, so the till refreshes only that, every minute, without a
 * restart or the reload button.
 */
class LiveSync(private val odoo: OdooJson2Client) {

    suspend fun read(): LiveStamps = coroutineScope {
        WATCHED.keys.map { model -> async { model to newest(model) } }.awaitAll().toMap().let(::LiveStamps)
    }

    private suspend fun newest(model: String): String? =
        odoo.call(model, "search_read", jsonOf(
            "domain" to if (model == "ir.ui.view") listOf(listOf("key", "=", BillTextService.TEMPLATE_KEY)) else emptyList<Any>(),
            "fields" to listOf("write_date"),
            "order" to "write_date desc, id desc",
            "limit" to 1,
            "context" to mapOf("active_test" to false),
        )).objects().firstOrNull()?.str("write_date")

    companion object {
        /** Model → the part of the till it feeds. */
        val WATCHED: Map<String, LiveArea> = linkedMapOf(
            "product.product" to LiveArea.PRODUCTS,
            "product.template" to LiveArea.PRODUCTS,
            "pos.category" to LiveArea.MENU,
            "account.tax" to LiveArea.TAXES,
            "account.fiscal.position" to LiveArea.TAXES,
            "loyalty.program" to LiveArea.LOYALTY,
            "loyalty.rule" to LiveArea.LOYALTY,
            "loyalty.reward" to LiveArea.LOYALTY,
            "pos.note" to LiveArea.NOTES,
            "res.company" to LiveArea.COMPANY,
            "ir.ui.view" to LiveArea.BILL_TEXT,
        )
    }
}
