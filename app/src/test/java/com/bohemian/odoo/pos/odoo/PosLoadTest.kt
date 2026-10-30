package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Odoo's POS loader (`pos.session.load_data`) as the till holds it. The records are shaped like the live
 * reply of 31 Oct (plain ids for links, `_`-keys on pos.config, special products not available in POS);
 * names and numbers are made up.
 */
class PosLoadTest {

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private val session = """[{"id": 448, "state": "opened", "config_id": 1}]"""

    private fun config(serverDate: String) = """[{"id": 1, "name": "ZenZebra", "current_session_id": 448, "current_session_state": "opened",
        "payment_method_ids": [2, 22], "company_id": 1, "invoice_journal_id": 9, "default_fiscal_position_id": false,
        "fiscal_position_ids": [], "cash_control": true, "manual_discount": false, "default_preset_id": 3, "note_ids": [],
        "limit_categories": false, "iface_available_categ_ids": [], "pricelist_id": false,
        "_data_server_date": "$serverDate", "_server_version": "19.0"}]"""

    private fun chaiTemplate(price: Double) = """{"id": 100, "display_name": "Cardamom Chai", "list_price": $price, "taxes_id": [30], "pos_categ_ids": [4],
         "l10n_in_hsn_code": "0902", "write_date": "2026-10-30 10:00:00", "available_in_pos": true, "product_variant_ids": [200], "image_128": false}"""

    private val zzTemplate = """{"id": 101, "display_name": "ZZ Cash", "list_price": 0.0, "taxes_id": [], "pos_categ_ids": [],
         "l10n_in_hsn_code": false, "write_date": "2026-09-01 10:00:00", "available_in_pos": false, "product_variant_ids": [201], "image_128": false}"""

    private fun full(chaiPrice: Double = 60.0, taxAmount: Double = 5.0, serverDate: String = "2026-10-31 05:00:00") = json("""
    {"pos.session": $session,
     "pos.config": ${config(serverDate)},
     "pos.preset": [{"id": 3, "name": "Pickup"}],
     "res.company": [{"id": 1, "name": "Bohemian Curations", "street": "1 Road", "city": "Delhi", "zip": "110001"}],
     "pos.payment.method": [{"id": 2, "name": "Cash", "is_cash_count": true, "use_payment_terminal": false, "type": "cash", "payment_method_type": "none"},
        {"id": 22, "name": "Pine Labs E700 (A2A)", "is_cash_count": false, "use_payment_terminal": false, "type": "bank", "payment_method_type": "none"}],
     "pos.category": [{"id": 5, "name": "Bites", "parent_id": false, "sequence": 2, "color": 3},
        {"id": 4, "name": "Chai & Coffee", "parent_id": false, "sequence": 1, "color": 1}],
     "product.template": [${chaiTemplate(chaiPrice)}, $zzTemplate],
     "product.product": [
        {"id": 200, "display_name": "Cardamom Chai", "lst_price": $chaiPrice, "barcode": "8900001", "default_code": "CH1", "product_tmpl_id": 100},
        {"id": 201, "display_name": "ZZ Cash", "lst_price": 0.0, "barcode": false, "default_code": false, "product_tmpl_id": 101}],
     "account.tax": [{"id": 30, "name": "5% GST S", "amount": $taxAmount, "amount_type": "group", "fiscal_position_ids": []}],
     "account.fiscal.position": [],
     "pos.note": [{"id": 1, "name": "Less sugar", "color": 2}],
     "product.pricelist": [], "product.pricelist.item": [],
     "loyalty.program": [{"id": 4, "name": "ZZ Cash", "program_type": "loyalty"}],
     "loyalty.rule": [{"id": 9, "program_id": 4, "mode": "auto", "reward_point_mode": "money", "reward_point_amount": 0.05,
        "minimum_amount": 0.0, "minimum_amount_tax_mode": "incl", "minimum_qty": 0, "any_product": true, "valid_product_ids": []}],
     "loyalty.reward": []}
    """)

    @Test fun `a full load gives the config, the products the till sells and everything around them`() {
        val cat = PosCatalog()
        assertTrue(cat.merge(full(), full = true).any)
        val cfg = cat.config()!!
        assertEquals(448, cfg.currentSessionId)
        assertTrue(cfg.sessionOpen)
        assertEquals("Bohemian Curations", cfg.companyName)
        assertEquals("Pickup", cfg.defaultPresetName)
        assertEquals(listOf(2, 22), cfg.paymentMethodIds)
        assertNull(cfg.pricelistId)
        val p = cat.products().single() // the special "ZZ Cash" product is not a tile
        assertEquals(Product(200, "Cardamom Chai", Paise.ofRupees(60), "8900001", "CH1", listOf(30), "0902", listOf(4), "2026-10-30 10:00:00"), p)
        assertEquals(listOf("Chai & Coffee", "Bites"), cat.categories(cfg).map { it.name })
        assertEquals(mapOf(30 to "5% GST S"), cat.taxNames())
        assertEquals(listOf("Less sugar"), cat.notes().map { it.name })
        assertEquals(1, cat.loyaltyRules(4).size)
        assertEquals(448, cat.sessionId)
    }

    @Test fun `a quiet poll is no change, though Odoo re-sends the config and its special products`() {
        val cat = PosCatalog().apply { merge(full(), full = true) }
        val quiet = json("""{"pos.session": $session, "pos.config": ${config("2026-10-31 05:02:00")}, "product.template": [$zzTemplate]}""")
        assertFalse(cat.merge(quiet, full = false).any)
    }

    @Test fun `a template price change reprices its variant even when Odoo leaves the variant out`() {
        val cat = PosCatalog().apply { merge(full(), full = true) }
        val change = json("""{"product.template": [${chaiTemplate(65.0)}]}""")
        val c = cat.merge(change, full = false)
        assertTrue(c.products)
        assertFalse(c.taxes)
        assertEquals(Paise.ofRupees(65), cat.products().single().price)
    }

    @Test fun `a variant Odoo did not send is read, and products no longer sold are dropped`() {
        val cat = PosCatalog().apply { merge(full(), full = true) }
        val back = json("""{"product.template": [{"id": 102, "display_name": "Ginger Chai", "list_price": 50.0, "taxes_id": [30],
            "pos_categ_ids": [4], "l10n_in_hsn_code": "0902", "write_date": "2026-10-31 05:01:00", "available_in_pos": true, "product_variant_ids": [202]}]}""")
        cat.merge(back, full = false)
        assertEquals(listOf(202), cat.missingVariants())
        cat.addVariants(listOf(json("""{"id": 202, "display_name": "Ginger Chai", "lst_price": 50.0, "barcode": false, "default_code": false, "product_tmpl_id": 102}""")))
        assertEquals(listOf("Cardamom Chai", "Ginger Chai"), cat.products().map { it.name })
        assertEquals(1, cat.keepOnlyProducts(setOf(200))) // Ginger Chai taken out of the POS in Odoo
        assertEquals(listOf(200), cat.products().map { it.id })
    }

    @Test fun `the tax fingerprint changes with a tax, not with anything else`() {
        val a = PosCatalog().apply { merge(full(), full = true) }.taxFingerprint()
        assertEquals(a, PosCatalog().apply { merge(full(chaiPrice = 70.0), full = true) }.taxFingerprint())
        assertNotEquals(a, PosCatalog().apply { merge(full(taxAmount = 12.0), full = true) }.taxFingerprint())
    }

    @Test fun `the next since is Odoo's clock less five minutes, and never goes back`() {
        val at = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse("2026-10-31 05:10:00")!!.time
        assertEquals("2026-10-31 05:05:00", PosLoad.nextSince(null, at))
        assertEquals("2026-10-31 05:06:00", PosLoad.nextSince("2026-10-31 05:06:00", at))
        assertEquals("2026-10-31 05:00:00", PosLoad.nextSince("2026-10-31 05:00:00", null))
    }
}
