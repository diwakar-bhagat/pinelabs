package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.receipt.BillText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The bill's fixed words, read from Odoo's "ZenZebra Bill" template (ir.ui.view 3800 as of 2026-10-28). */
class BillTextServiceTest {
    private val live = javaClass.classLoader!!.getResource("zenzebra_bill_template.xml")!!.readText()

    @Test fun `the live template gives exactly the words the bill prints today`() =
        assertEquals(BillText.BUNDLED, BillTextService.parse(live))

    @Test fun `new terms in Odoo reach the paper bill`() {
        val changed = live.replace("Exchange within 7 days", "Exchange within 10 days")
            .replace("Thanks for stopping by.", "Thank you for visiting.")
        val text = BillTextService.parse(changed)!!
        assertEquals(true, text.terms[1].startsWith("Exchange within 10 days"))
        assertEquals("Thank you for visiting. See you at the breakspot.", text.thanks)
        assertEquals(BillText.BUNDLED.brand, text.brand)
    }

    @Test fun `a template without the ZZ Cash note prints none`() {
        val start = live.indexOf("<div style=\"font-size:8.5px")
        val end = live.indexOf("</div>", start) + "</div>".length
        assertEquals(emptyList<String>(), BillTextService.parse(live.removeRange(start, end))!!.zzExplainer)
    }

    @Test fun `a template the till cannot read gives nothing, so the last good words stay`() {
        assertNull(BillTextService.parse("<t t-name=\"x\"><div class=\"page\"/></t>"))
        assertNull(BillTextService.parse("not xml"))
        assertNull(BillTextService.parse("<!DOCTYPE t [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><t>&x;</t>"))
    }
}
