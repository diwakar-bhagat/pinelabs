package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.data.LoyaltyConfirmDao
import com.bohemian.odoo.pos.data.LoyaltyConfirmEntity
import com.bohemian.odoo.pos.data.OutboxDao
import com.bohemian.odoo.pos.data.OutboxEntity
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class MemOutbox : OutboxDao {
    val rows = LinkedHashMap<String, OutboxEntity>()
    override suspend fun upsert(e: OutboxEntity) { rows[e.orderUuid] = e }
    override suspend fun due(now: Long) = rows.values.filter { it.nextAttemptAt <= now }
    override fun observeCount(): Flow<Int> = MutableStateFlow(rows.size)
    override suspend fun count() = rows.size
    override suspend fun delete(uuid: String) { rows.remove(uuid) }
    override suspend fun markFailed(uuid: String, error: String, next: Long) {
        rows[uuid] = rows[uuid]!!.let { it.copy(attempts = it.attempts + 1, lastError = error, nextAttemptAt = next) }
    }
}

private class MemLoyalty : LoyaltyConfirmDao {
    val rows = LinkedHashMap<String, LoyaltyConfirmEntity>()
    override suspend fun upsert(e: LoyaltyConfirmEntity) { rows[e.orderUuid] = e }
    override suspend fun get(uuid: String) = rows[uuid]
    override suspend fun due(now: Long) = rows.values.filter { it.nextAttemptAt <= now }
    override suspend fun count() = rows.size
    override suspend fun delete(uuid: String) { rows.remove(uuid) }
    override suspend fun markFailed(uuid: String, error: String, next: Long) {
        rows[uuid] = rows[uuid]!!.let { it.copy(attempts = it.attempts + 1, lastError = error, nextAttemptAt = next) }
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
        paidAtMillis = 0, loyaltyEarned = Paise(120), loyaltyCardId = 535,
    )

    private fun path() = server.takeRequest().path!!.substringAfterLast('/')

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

    @Test fun `walk-in sale books nothing`() = runTest {
        server.enqueue(MockResponse().setBody("""{"pos.order": []}"""))
        assertTrue(sync().submit(sale(customer = null)))
        assertEquals(1, server.requestCount)
        assertTrue(loyalty.rows.isEmpty())
    }
}
