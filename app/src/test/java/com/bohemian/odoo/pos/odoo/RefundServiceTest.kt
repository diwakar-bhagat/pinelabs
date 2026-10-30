package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.data.OutboxDao
import com.bohemian.odoo.pos.data.OutboxEntity
import com.bohemian.odoo.pos.data.OutboxProblem
import com.bohemian.odoo.pos.payment.VoidAttempt
import com.bohemian.odoo.pos.payment.VoidState
import com.bohemian.odoo.pos.pine.TxnDetails
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class InMemoryOutbox : OutboxDao {
    val rows = LinkedHashMap<String, OutboxEntity>()
    override suspend fun upsert(e: OutboxEntity) { rows[e.orderUuid] = e }
    override suspend fun due(now: Long) = rows.values.filter { it.nextAttemptAt <= now }
    override fun observeCount(): Flow<Int> = MutableStateFlow(rows.size)
    override suspend fun count() = rows.size
    override suspend fun contains(uuid: String) = uuid in rows
    override suspend fun delete(uuid: String) { rows.remove(uuid) }
    override suspend fun markFailed(uuid: String, error: String, next: Long, kind: String) {
        rows[uuid] = rows[uuid]!!.let { it.copy(attempts = it.attempts + 1, lastError = error, nextAttemptAt = next, errorKind = kind) }
    }
    override suspend fun markWaiting(uuid: String, error: String, next: Long, kind: String) {
        rows[uuid] = rows[uuid]!!.let { it.copy(lastError = error, nextAttemptAt = next, errorKind = kind) }
    }
    override suspend fun retryNow(kind: String) { rows.replaceAll { _, r -> if (r.errorKind == kind) r.copy(nextAttemptAt = 0) else r } }
    override fun observeProblems(): Flow<List<OutboxProblem>> = MutableStateFlow(emptyList())
}

class RefundServiceTest {

    // Two shirts at ₹699 incl. 5% GST, one snack at ₹140 incl. 12% GST = ₹1,538
    private val orderJson = """{"id": 501, "uuid": "sale-uuid-1", "partner_id": [83, "Priya"], "fiscal_position_id": false,
        "to_invoice": false, "is_refund": false, "amount_total": 1538.0, "lines": [9001, 9002]}"""
    private fun linesJson(refundedQty: Double = 0.0, reward: Boolean = false) = """[
        {"id": 9001, "product_id": [12, "TEE"], "full_product_name": "29 TEE orange S", "qty": 2.0, "price_unit": 699.0,
         "discount": 0.0, "tax_ids": [5], "price_subtotal": 1331.43, "price_subtotal_incl": 1398.0, "refunded_qty": $refundedQty, "is_reward_line": $reward},
        {"id": 9002, "product_id": [40, "Makino"], "full_product_name": "Makino Corn Munch", "qty": 1.0, "price_unit": 140.0,
         "discount": 0.0, "tax_ids": [7], "price_subtotal": 125.0, "price_subtotal_incl": 140.0, "refunded_qty": 0.0, "is_reward_line": false}
    ]"""

    private fun order(lines: String = linesJson()) = RefundService.parseOrder(
        Json.parseToJsonElement(orderJson).jsonObject,
        Json.parseToJsonElement(lines).jsonArray.map { it.jsonObject },
    )

    private val void = VoidAttempt(
        billingRef = "KLJX261023120000777", saleRef = "KLJX261023115900111", orderUuid = "sale-uuid-1",
        amount = Paise(153800), state = VoidState.VOIDED, details = TxnDetails(transactionId = "VTXN9"),
        createdAt = 0, updatedAt = 0,
    )

    // ------------------------------------------------------------ eligibility

    @Test fun `a fully card-paid untouched bill is eligible`() {
        assertNull(RefundService.eligibility(order(), Paise(153800)))
    }

    @Test fun `split bill is refused because the card covered only part`() {
        assertTrue(RefundService.eligibility(order(), Paise(100000))!!.contains("split"))
    }

    @Test fun `ZZ Cash redemption is refused so points are restored in Odoo`() {
        assertTrue(RefundService.eligibility(order(linesJson(reward = true)), Paise(153800))!!.contains("ZZ Cash"))
    }

    @Test fun `already partly refunded bill is refused`() {
        assertTrue(RefundService.eligibility(order(linesJson(refundedQty = 1.0)), Paise(153800))!!.contains("already refunded"))
    }

    @Test fun `fractional quantity is refused`() {
        val lines = linesJson().replace("\"qty\": 1.0", "\"qty\": 1.5")
        assertTrue(RefundService.eligibility(order(lines), Paise(153800)) != null)
    }

    // ------------------------------------------------------------ payload

    private fun payload(): JsonObject =
        (RefundService.buildPayload(order(), void, sessionId = 33, paymentMethodId = 8, cardLast4 = "4242", nowMillis = 0)["orders"] as JsonArray)[0].jsonObject

    @Test fun `refund order is negative, flagged, and linked line by line`() {
        val o = payload()
        assertEquals("true", o["is_refund"].toString())
        assertEquals("33", o["session_id"].toString())
        assertEquals("\"paid\"", o["state"].toString())
        assertEquals("83", o["partner_id"].toString())
        assertEquals("-1538.00", o["amount_total"].toString())
        assertEquals("-1538.00", o["amount_paid"].toString())
        assertEquals("-81.57", o["amount_tax"].toString()) // (1398 + 140) - (1331.43 + 125)

        val lines = o["lines"]!!.jsonArray.map { it.jsonArray[2].jsonObject }
        assertEquals(listOf("9001", "9002"), lines.map { it["refunded_orderline_id"].toString() })
        assertEquals(listOf("-2", "-1"), lines.map { it["qty"].toString() })
        assertEquals(listOf("699.00", "140.00"), lines.map { it["price_unit"].toString() })
        assertEquals(listOf("-1398.00", "-140.00"), lines.map { it["price_subtotal_incl"].toString() })
        assertEquals("[[6,0,[5]]]", lines[0]["tax_ids"].toString())
    }

    @Test fun `refund payment is negative on the terminal method and carries the void ref`() {
        val p = payload()["payment_ids"]!!.jsonArray.single().jsonArray[2].jsonObject
        assertEquals("8", p["payment_method_id"].toString())
        assertEquals("-1538.00", p["amount"].toString())
        assertEquals("\"KLJX261023120000777\"", p["payment_ref_no"].toString())
        assertEquals("\"VTXN9\"", p["transaction_id"].toString())
        assertEquals("\"4242\"", p["card_no"].toString())
    }

    @Test fun `retrying the same void builds the same uuids so Odoo dedupes it`() {
        val a = payload(); val b = payload()
        assertEquals(a["uuid"], b["uuid"])
        assertEquals(a["lines"], b["lines"])
        assertEquals(a["payment_ids"], b["payment_ids"])
        assertEquals(RefundService.refundUuid(void.billingRef), a["uuid"].toString().trim('"'))
        assertTrue(RefundService.refundUuid("A") != RefundService.refundUuid("B"))
    }

    // ------------------------------------------------------------ flow against a mock Odoo

    private lateinit var server: MockWebServer
    private val outbox = InMemoryOutbox()

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun service(): RefundService {
        val odoo = OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" })
        return RefundService(odoo, OrderSyncService(odoo, outbox, clock = { 0 }), cardPaymentMethodId = { 8 }, clock = { 0 })
    }

    @Test fun `refund is read from Odoo, sent once, and leaves nothing in the outbox`() = runTest {
        server.enqueue(MockResponse().setBody("[$orderJson]"))
        server.enqueue(MockResponse().setBody(linesJson()))
        server.enqueue(MockResponse().setBody("""{"pos.order": []}"""))

        val r = service().refundVoidedSale(void, sessionId = 33, cardLast4 = "4242")

        assertEquals(RefundResult.Recorded(RefundService.refundUuid(void.billingRef), syncedNow = true), r)
        assertEquals(listOf("search_read", "read", "sync_from_ui"), (0 until 3).map { server.takeRequest().path!!.substringAfterLast('/') })
        assertTrue(outbox.rows.isEmpty())
    }

    @Test fun `refund that fails to sync stays in the outbox for retry`() = runTest {
        server.enqueue(MockResponse().setBody("[$orderJson]"))
        server.enqueue(MockResponse().setBody(linesJson()))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message": "boom"}"""))

        val r = service().refundVoidedSale(void, sessionId = 33, cardLast4 = null)

        assertEquals(RefundResult.Recorded(RefundService.refundUuid(void.billingRef), syncedNow = false), r)
        assertEquals(-153800L, outbox.rows.values.single().amountPaise)
    }

    @Test fun `sale not yet in Odoo is not refunded`() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        assertTrue(service().refundVoidedSale(void, sessionId = 33, cardLast4 = null) is RefundResult.NotEligible)
        assertEquals(1, server.requestCount)
        assertTrue(outbox.rows.isEmpty())
    }

    @Test fun `no open session means no refund is attempted`() = runTest {
        assertTrue(service().refundVoidedSale(void, sessionId = null, cardLast4 = null) is RefundResult.NotEligible)
        assertEquals(0, server.requestCount)
    }
}
