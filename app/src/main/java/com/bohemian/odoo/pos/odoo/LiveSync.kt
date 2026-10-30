package com.bohemian.odoo.pos.odoo

/** The parts of the till a change in Odoo can touch; each is refreshed on its own. */
enum class LiveArea { PRODUCTS, MENU, TAXES, LOYALTY, NOTES, COMPANY, BILL_TEXT, PRICELIST }

/**
 * The newest `write_date` of each watched model, Odoo's own clock (the device clock never
 * matters). Archived records count too: archiving a product or a program is a change.
 */
data class LiveStamps(val byModel: Map<String, String?>) {

    /** The parts to refresh: only models read this time ([byModel] may hold some of them) count. */
    fun changedSince(old: LiveStamps): Set<LiveArea> =
        LiveSync.WATCHED.filter { (model, _) -> model in byModel && byModel[model] != old.byModel[model] }.map { it.value }.toSet()

    /** The last look at every model: these newer reads over [old]'s. */
    fun over(old: LiveStamps): LiveStamps = LiveStamps(old.byModel + byModel)

    /** Odoo's newest change to taxes and fiscal positions: the saved prices are valid under this stamp only. */
    val taxStamp: String? get() = byModel["account.tax"]?.let { tax -> tax + "|" + byModel["account.fiscal.position"].orEmpty() }

    /** Products are fetched from here on: the older of the two product stamps of the last look. */
    val productsSince: String? get() = listOfNotNull(byModel["product.product"], byModel["product.template"]).minOrNull()
}

/**
 * Live Odoo data: one cheap read per watched model says what changed since the last look, so the
 * till refreshes only that, without a restart or the reload button. Odoo Online accepts about one
 * call a second with no parallel calls, so the models are read one after another and not all every
 * time: prices every 5 minutes, the rest every 15 ([everyMs], [due]).
 */
class LiveSync(private val odoo: OdooJson2Client) {

    suspend fun read(models: Collection<String> = WATCHED.keys): LiveStamps =
        LiveStamps(models.associateWith { newest(it) })

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
            "product.pricelist.item" to LiveArea.PRICELIST,
        )

        /** Prices reach the till within 5 minutes; menu, taxes, offers, notes, company and bill text within 15. */
        fun everyMs(model: String): Long = if (model == "product.product" || model == "product.template") 5 * 60_000L else 15 * 60_000L

        /** The models to look at now, given when each was last read. */
        fun due(lastRead: Map<String, Long>, nowMs: Long): List<String> =
            WATCHED.keys.filter { m -> lastRead[m]?.let { nowMs - it >= everyMs(m) } ?: true }
    }
}
