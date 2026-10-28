package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.PosLog
import com.bohemian.odoo.pos.receipt.BillText
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The fixed words of the bill, read from Odoo's own "ZenZebra Bill" report template (the PDF the
 * customer gets on WhatsApp), so a change to the terms in Odoo reaches the paper bill too.
 */
class BillTextService(private val odoo: OdooJson2Client) {

    /** The template's words, or null when it cannot be read or understood (the caller keeps what it has). */
    suspend fun load(): BillText? {
        val arch = odoo.call("ir.ui.view", "search_read", jsonOf(
            "domain" to listOf(listOf("key", "=", TEMPLATE_KEY)),
            "fields" to listOf("arch_db"),
            "limit" to 1,
        )).objects().firstOrNull()?.str("arch_db") ?: return null.also { PosLog.w("BILL_TEMPLATE_MISSING", "key" to TEMPLATE_KEY) }
        return parse(arch).also { if (it == null) PosLog.w("BILL_TEMPLATE_UNREADABLE", "key" to TEMPLATE_KEY, "chars" to arch.length) }
    }

    companion object {
        /** The QWeb template of the "ZenZebra Bill" report (ir.ui.view key). */
        const val TEMPLATE_KEY = "x_zenzebra.pos_bill"

        /**
         * The words by their place in the template, not by their text: the header's first two plain
         * lines (brand, legal name), the ZZ Cash box's note, and the footer's plain lines (the bold
         * one is the closing line). Lines with an Odoo field in them (address, store, bill ref) are
         * filled per order and skipped. Null when a part is missing, so a half-read bill never prints.
         */
        fun parse(arch: String): BillText? = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                // A template never has a DOCTYPE: refuse one, so no external entity is ever resolved.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }
            val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(arch)))
            val page = doc.getElementsByTagName("div").elements().firstOrNull { it.getAttribute("class") == "page" } ?: return null
            val blocks = page.childElements().filter { it.tagName == "div" && it.style().contains("text-align:center") }
            val header = blocks.firstOrNull()?.childElements()?.filter { it.isPlain() }?.map { it.lines().joinToString(" ") }.orEmpty()
            val footer = blocks.drop(1).lastOrNull()?.childElements()?.filter { it.isPlain() }.orEmpty()
            val zzBox = doc.getElementsByTagName("div").elements().firstOrNull { it.getAttribute("t-if") == "o.x_zz_show" }
            val text = BillText(
                brand = header.getOrNull(0) ?: return null,
                legalName = header.getOrNull(1) ?: return null,
                terms = footer.filter { !it.style().contains("font-weight:bold") }.map { it.lines().joinToString(" ") }.ifEmpty { return null },
                thanks = footer.firstOrNull { it.style().contains("font-weight:bold") }?.lines()?.joinToString(" ") ?: return null,
                // The note closes the box, after the figures; the box's title above them is not it.
                zzExplainer = zzBox?.childElements()?.takeIf { it.size > 1 }?.last()?.takeIf { it.isPlain() }?.lines().orEmpty(),
            )
            text.takeIf { it.brand.isNotBlank() && it.legalName.isNotBlank() && it.thanks.isNotBlank() }
        }.getOrNull()

        private fun org.w3c.dom.NodeList.elements(): List<Element> = (0 until length).mapNotNull { item(it) as? Element }
        private fun Element.childElements(): List<Element> = childNodes.elements()
        private fun Element.style(): String = getAttribute("style").replace(" ", "")

        /** Fixed words only: no QWeb attribute here or below, and no markup but line breaks. */
        private fun Element.isPlain(): Boolean {
            if ((0 until attributes.length).any { attributes.item(it).nodeName.startsWith("t-") }) return false
            if (childElements().any { it.tagName != "br" }) return false
            return textContent.isNotBlank()
        }

        /** The text split at each <br/>, whitespace collapsed. */
        private fun Element.lines(): List<String> {
            val out = mutableListOf(StringBuilder())
            for (i in 0 until childNodes.length) {
                val n = childNodes.item(i)
                if (n is Element && n.tagName == "br") out += StringBuilder() else if (n.nodeType == Node.TEXT_NODE) out.last().append(n.textContent)
            }
            return out.map { it.toString().replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
        }
    }
}
