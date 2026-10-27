package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Last catalogue Odoo sent (Phase 5), so the till starts and shows products when Odoo is
 * unreachable. Display only: every line is still priced by Odoo before a sale (I2), so a
 * till without Odoo cannot take payment. Not secret (prices, names, barcodes); kept in
 * the app's private files dir. Written atomically (temp file + rename).
 */
class CatalogCache(private val file: File) {

    @Serializable
    private data class ConfigDto(
        val id: Int, val name: String, val currentSessionId: Int?, val sessionOpen: Boolean,
        val paymentMethodIds: List<Int>, val companyName: String, val companyId: Int?,
        val invoiceJournalId: Int?, val defaultFiscalPositionId: Int?, val fiscalPositionIds: List<Int>,
        val cashControl: Boolean,
        val manualDiscount: Boolean = false, val defaultPresetId: Int? = null, val defaultPresetName: String? = null,
        val noteIds: List<Int> = emptyList(),
        val limitCategories: Boolean = false, val availableCategIds: List<Int> = emptyList(),
    )

    @Serializable
    private data class ProductDto(
        val id: Int, val name: String, val pricePaise: Long, val barcode: String?, val defaultCode: String?,
        val taxIds: List<Int>, val hsnCode: String?,
        val categIds: List<Int> = emptyList(), val writeDate: String? = null,
    )

    @Serializable
    private data class CategoryDto(val id: Int, val name: String, val parentId: Int?, val sequence: Int, val color: Int)

    @Serializable
    private data class NoteDto(val id: Int, val name: String, val color: Int)

    @Serializable
    private data class Snapshot(
        val savedAt: Long, val config: ConfigDto, val products: List<ProductDto>,
        val categories: List<CategoryDto> = emptyList(), val notes: List<NoteDto> = emptyList(),
    )

    data class Cached(
        val savedAt: Long, val config: PosConfig, val products: List<Product>,
        val categories: List<PosCategory> = emptyList(), val notes: List<PosNote> = emptyList(),
    )

    fun save(
        config: PosConfig, products: List<Product>, now: Long = System.currentTimeMillis(),
        categories: List<PosCategory> = emptyList(), notes: List<PosNote> = emptyList(),
    ) {
        val snap = Snapshot(
            savedAt = now,
            config = with(config) {
                ConfigDto(id, name, currentSessionId, sessionOpen, paymentMethodIds, companyName, companyId,
                    invoiceJournalId, defaultFiscalPositionId, fiscalPositionIds, cashControl,
                    manualDiscount, defaultPresetId, defaultPresetName, noteIds, limitCategories, availableCategIds)
            },
            products = products.map { ProductDto(it.id, it.name, it.price.value, it.barcode, it.defaultCode, it.taxIds, it.hsnCode, it.categIds, it.writeDate) },
            categories = categories.map { CategoryDto(it.id, it.name, it.parentId, it.sequence, it.color) },
            notes = notes.map { NoteDto(it.id, it.name, it.color) },
        )
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(snap))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** null when nothing was saved yet or the file is unreadable (never crashes the till). */
    fun load(): Cached? = runCatching {
        val s = json.decodeFromString<Snapshot>(file.readText())
        Cached(
            savedAt = s.savedAt,
            config = with(s.config) {
                PosConfig(id, name, currentSessionId, sessionOpen, paymentMethodIds, companyName, companyId,
                    invoiceJournalId, defaultFiscalPositionId, fiscalPositionIds, cashControl,
                    manualDiscount, defaultPresetId, defaultPresetName, noteIds, limitCategories, availableCategIds)
            },
            products = s.products.map { Product(it.id, it.name, Paise(it.pricePaise), it.barcode, it.defaultCode, it.taxIds, it.hsnCode, it.categIds, it.writeDate) },
            categories = s.categories.map { PosCategory(it.id, it.name, it.parentId, it.sequence, it.color) },
            notes = s.notes.map { PosNote(it.id, it.name, it.color) },
        )
    }.getOrNull()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
