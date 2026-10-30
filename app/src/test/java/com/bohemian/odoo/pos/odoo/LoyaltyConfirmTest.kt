package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.data.LoyaltyConfirmDao
import com.bohemian.odoo.pos.data.LoyaltyConfirmEntity
import com.bohemian.odoo.pos.data.OutboxDao
import com.bohemian.odoo.pos.data.OutboxEntity
import com.bohemian.odoo.pos.data.OutboxProblem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class MemOutbox : OutboxDao {
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

private class MemLoyalty : LoyaltyConfirmDao {
    val rows = LinkedHashMap<String, LoyaltyConfirmEntity>()
    override suspend fun upsert(e: LoyaltyConfirmEntity) { rows[e.orderUuid] = e }
    override suspend fun get(uuid: String) = rows[uuid]
    override suspend fun due(now: Long) = rows.values.filter { it.nextAttemptAt <= now }
    override suspend fun count() = rows.size
    override suspend fun delete(uuid: String) { rows.remove(uuid) }
    override suspend fun markFailed(uuid: String, error: String, next: Long, kind: String) {
        rows[uuid] = rows[uuid]!!.let { it.copy(attempts = it.attempts + 1, lastError = error, nextAttemptAt = next, errorKind = kind) }
    }
}

/** ZZ Cash is booked with confirm_coupon_programs right after sync, and survives any failure until Odoo answers. */
class LoyaltyConfirmTest {
    private lateinit var server: MockWebServer
    private val outbox = MemOutbox()
    private val loyalty = MemLoyalty()

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun sync(): OrderSyncService {
        val odoo = OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" })
        return OrderSyncService(odoo, outbox, loyalty, clock = { 0 })
    }

    private val chai = Product(8201, "Adrak Elaichi Chai", Paise.ofRupees(20), null, null, listOf(30))
    private fun sale(customer: Customer? = Customer(83, "Diwakar", "+917048980055")) = PaidSale(
        orderUuid = "order-1", sessionId = 9,
        lines = listOf(CartLine("l1", chai, 1, Paise.ofRupees(20), Paise(1905))),
        totals = CartTotals(Paise.ofRupees(20), Paise(1905)), customer = customer,
        payments = listOf(PaymentLineData(2, Paise.ofRupees(20), null, null, null)),
        paidAtMillis = 0, loyaltyEarned = Paise(120), loyaltyCardId = 535, loyaltyProgramId = 4,
    )

    private fun path() = server.takeRequest().path!!.substringAfterLast('/')

    @Test fun `points are booked on the program the sale was made under`() {
        val data = OrderSyncService.couponData(sale().copy(loyaltyProgramId = 12))!!
        assertEquals(12, data["535"]!!.jsonObject["program_id"]!!.jsonPrimitive.int)
    }

    @Test fun `no program on the sale books no points`() {
        assertNull(OrderSyncService.couponData(sale().copy(loyaltyProgramId = null)))
    }

    @Test fun `points are confirmed on the synced order right after sync`() = runTest {
        server.enqueue(MockResponse().setBody("""{"pos.order": []}"""))
        server.enqueue(MockResponse().setBody("""[{"id": 7001}]"""))
        server.enqueue(MockResponse().setBody("""{"coupon_updates": []}"""))

        assertTrue(sync().submit(sale()))

        assertEquals("sync_from_ui", path())
        assertEquals("search_read", path())
        val confirm = server.takeRequest()
        assertEquals("confirm_coupon_programs", confirm.path!!.substringAfterLast('/'))
        val body = Json.parseToJsonElement(confirm.body.readUtf8()).jsonObject
        assertEquals(7001, body["ids"]!!.jsonArray.single().jsonPrimitive.int)
        assertEquals("1.20", body["coupon_data"]!!.jsonObject["535"]!!.jsonObject["points_earned"]!!.jsonPrimitive.content)
        assertTrue(outbox.rows.isEmpty())
        assertTrue(loyalty.rows.isEmpty())
    }

    @Test fun `the order id in Odoo's sync reply books the points without a search`() = runTest {
        server.enqueue(MockResponse().setBody("""{"pos.order": [{"id": 7002, "uuid": "order-1"}], "pos.order.line": [], "pos.payment": []}"""))
        server.enqueue(MockResponse().setBody("""{"coupon_updates": []}"""))
        val sync = sync()
        assertTrue(sync.submit(sale()))
        assertEquals("sync_from_ui", path())
        val confirm = server.takeRequest()
        assertEquals("confirm_coupon_programs", confirm.path!!.substringAfterLast('/'))
        assertEquals(7002, Json.parseToJsonElement(confirm.body.readUtf8()).jsonObject["ids"]!!.jsonArray.single().jsonPrimitive.int)
        assertEquals(2, server.requestCount)
        assertEquals(7002, OrderSyncService.orderIdIn(sync.syncReply("order-1")!!, "order-1"))
    }

    @Test fun `a failed confirm keeps the row and the next flush books it`() = runTest {
        server.enqueue(MockResponse().setBody("""{"pos.order": []}"""))
        server.enqueue(MockResponse().setBody("""[{"id": 7001}]"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message": "boom"}"""))
        assertTrue(sync().submit(sale()))           // the order itself is in Odoo
        assertEquals(1, loyalty.rows["order-1"]!!.attempts)

        server.enqueue(MockResponse().setBody("""[{"id": 7001}]"""))
        server.enqueue(MockResponse().setBody("""{"coupon_updates": []}"""))
        sync().flushAll()
        assertTrue(loyalty.rows.isEmpty())
    }

    @Test fun `points wait while the order is still in the outbox`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message": "down"}"""))
        assertTrue(!sync().submit(sale()))
        assertEquals(1, loyalty.rows.size)
        assertEquals(1, server.requestCount) // no confirm without the order
    }

    @Test fun `with no open session the order waits without counting attempts and goes once a session opens`() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody(
            """{"name": "odoo.exceptions.UserError", "message": "No open session available. Please open a new session to capture the order."}"""))
        val sync = sync()
        assertTrue(!sync.submit(sale(customer = null)))
        val row = outbox.rows["order-1"]!!
        assertEquals("NO_SESSION", row.errorKind)
        assertEquals(0, row.attempts)
        assertEquals(OrderSyncService.WAIT_MS, row.nextAttemptAt)
        sync.retryNow(OdooErrorKind.NO_SESSION)
        assertEquals(0L, outbox.rows["order-1"]!!.nextAttemptAt)
    }

    @Test fun `an order Odoo refuses is kept, counted and tried again hourly`() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody(
            """{"name": "odoo.exceptions.ValidationError", "message": "The paid amount is different from the total amount of the order."}"""))
        assertTrue(!sync().submit(sale(customer = null)))
        val row = outbox.rows["order-1"]!!
        assertEquals("REJECTED", row.errorKind)
        assertEquals(1, row.attempts)
        assertEquals(OrderSyncService.REJECTED_RETRY_MS, row.nextAttemptAt)
        assertEquals("The paid amount is different from the total amount of the order.", row.lastError)
    }

    @Test fun `walk-in sale books nothing`() = runTest {
        server.enqueue(MockResponse().setBody("""{"pos.order": []}"""))
        assertTrue(sync().submit(sale(customer = null)))
        assertEquals(1, server.requestCount)
        assertTrue(loyalty.rows.isEmpty())
    }
}
