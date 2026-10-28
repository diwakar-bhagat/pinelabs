package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.data.OutboxDao
import com.bohemian.odoo.pos.data.OutboxEntity
import com.bohemian.odoo.pos.data.SaleSnapshotDao
import com.bohemian.odoo.pos.data.SaleSnapshotEntity
import com.bohemian.odoo.pos.payment.AttemptState
import com.bohemian.odoo.pos.payment.PaymentAttempt
import com.bohemian.odoo.pos.pine.PayMode
import com.bohemian.odoo.pos.pine.TxnDetails
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
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

private class MemSnapshots : SaleSnapshotDao {
    val rows = LinkedHashMap<String, SaleSnapshotEntity>()
    override suspend fun upsert(e: SaleSnapshotEntity) { rows[e.orderUuid] = e }
    override suspend fun get(uuid: String) = rows[uuid]
    override suspend fun delete(uuid: String) { rows.remove(uuid) }
    override suspend fun all() = rows.values.toList()
}

private class MemOutboxRows : OutboxDao {
    val rows = LinkedHashMap<String, OutboxEntity>()
    override suspend fun upsert(e: OutboxEntity) { rows[e.orderUuid] = e }
    override suspend fun due(now: Long) = rows.values.filter { it.nextAttemptAt <= now }
    override fun observeCount(): Flow<Int> = MutableStateFlow(rows.size)
    override suspend fun count() = rows.size
    override suspend fun contains(uuid: String) = uuid in rows
    override suspend fun delete(uuid: String) { rows.remove(uuid) }
    override suspend fun markFailed(uuid: String, error: String, next: Long) {
        rows[uuid] = rows[uuid]!!.let { it.copy(attempts = it.attempts + 1, lastError = error, nextAttemptAt = next) }
    }
}

/** A split bill: Card / UPI parts on the E700 (method 22) and cash (method 2) for the rest. */
class SplitFinalizeTest {
    private lateinit var server: MockWebServer
    private val snapshots = MemSnapshots()
    private val outbox = MemOutboxRows()
    private val sent = mutableListOf<JsonObject>()
    private val attempts = mutableListOf<PaymentAttempt>()

    @Before fun start() {
        server = MockWebServer().apply { start() }
        repeat(5) { server.enqueue(MockResponse().setBody("""{"pos.order": [{"id": 1}]}""")) }
    }
    @After fun stop() { server.shutdown() }

    private fun finalizer(roles: PaymentRoles = PaymentRoles(card = 22, upi = 22, cash = 2)): SaleFinalizer {
        val sync = OrderSyncService(OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" }), outbox,
            onPayloadGenerated = { _, payload -> sent += payload })
        return SaleFinalizer(snapshots, sync, terminalMethodFor = roles::forMode,
            paidAttempts = { uuid -> attempts.filter { it.orderUuid == uuid && it.state.isPaid } }, cashPaymentMethodId = { roles.cash })
    }

    private val cfg = PosConfig(id = 1, name = "ZenZebra", currentSessionId = 391, sessionOpen = true, paymentMethodIds = listOf(2, 22),
        companyName = "ZenZebra", companyId = 1)

    /** ₹500 bill, saved before the first charge, as `prepareSnapshot(split = true)` does. */
    private suspend fun saveBill(f: SaleFinalizer, uuid: String = "o1", split: Boolean = true): SaleSnapshot {
        val line = CartLine("l1", Product(8625, "Cookie Butter Coffee", Paise(50000), null, null, listOf(31)), 1,
            totalIncl = Paise(50000), totalExcl = Paise(47619))
        return SaleSnapshot.of(uuid, cfg, listOf(line), CartTotals(Paise(50000), Paise(47619)), customer = null, split = split)
            .also { f.save(it) }
    }

    private fun paid(ref: String, rupees: Long, mode: PayMode, order: String = "o1") =
        PaymentAttempt(ref, order, Paise.ofRupees(rupees), mode, AttemptState.SUCCESS, details = TxnDetails(rrn = "RRN$ref"),
            createdAt = 0, updatedAt = 0).also { attempts += it }

    private fun payments(payload: JsonObject) = payload["orders"]!!.jsonArray.single().jsonObject["payment_ids"]!!.jsonArray
        .map { it.jsonArray[2].jsonObject }.map { it["payment_method_id"]!!.jsonPrimitive.int to it["amount"]!!.jsonPrimitive.double }

    @Test fun `UPI + card + cash go to Odoo as three payment lines that add up to the bill`() = runTest {
        val f = finalizer()
        val snap = saveBill(f)
        paid("U1", 300, PayMode.UPI); paid("C1", 150, PayMode.CARD)
        f.finalizeSplit(snap)
        assertEquals(listOf(22 to 300.0, 22 to 150.0, 2 to 50.0), payments(sent.single()))
        assertNull(snapshots.rows["o1"]) // finalized: the saved cart is gone, the order is in the outbox / Odoo
    }

    @Test fun `at a store without an E700 method, card, UPI and cash go on its own Cards, UPI and Cash`() = runTest {
        // KLJ in Odoo: Cards 5, UPI 7, a Bank-type Cash 4 (PaymentRoles.defaults).
        val f = finalizer(PaymentRoles(card = 5, upi = 7, cash = 4))
        val snap = saveBill(f)
        paid("U1", 300, PayMode.UPI); paid("C1", 150, PayMode.CARD)
        f.finalizeSplit(snap)
        assertEquals(listOf(7 to 300.0, 5 to 150.0, 4 to 50.0), payments(sent.single()))
    }

    @Test fun `a partly paid split bill is never sent to Odoo from a terminal result`() = runTest {
        val f = finalizer()
        saveBill(f)
        val upi = paid("U1", 300, PayMode.UPI)
        assertNull(f.finalizeTerminal(upi))
        assertTrue(sent.isEmpty())
        assertEquals(listOf(PartlyPaid("o1", Paise.ofRupees(300), Paise.ofRupees(500))), f.partlyPaid())
    }

    @Test fun `terminal parts that cover the whole bill are finalized, e g after a crash`() = runTest {
        val f = finalizer()
        saveBill(f)
        paid("U1", 300, PayMode.UPI)
        val card = paid("C1", 200, PayMode.CARD)
        f.finalizeTerminal(card)
        assertEquals(listOf(22 to 300.0, 22 to 200.0), payments(sent.single()))
        assertTrue(f.partlyPaid().isEmpty())
    }

    @Test fun `on start, a fully paid sale whose order never reached the outbox is recovered`() = runTest {
        val f = finalizer()
        saveBill(f, split = false)
        paid("C1", 500, PayMode.CARD)
        saveBill(f, uuid = "o2", split = false) // a cart that was never paid: left alone
        assertEquals(1, f.recoverPaid())
        assertEquals(listOf(22 to 500.0), payments(sent.single()))
        assertTrue("o2" in snapshots.rows)
    }

    @Test fun `the outbox loop and a live sale never send the same order twice`() = runTest {
        val slow = MockWebServer().apply { start() }
        try {
            // Odoo answers slowly; both flushes reach the order while the first send is still running.
            slow.enqueue(MockResponse().setBody("""{"pos.order": [{"id": 1}]}""").setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS))
            slow.enqueue(MockResponse().setBody("""{"pos.order": [{"id": 1}]}"""))
            val sync = OrderSyncService(OdooJson2Client(slow.url("/").toString().trimEnd('/'), "test", apiKey = { "k" }), outbox)
            outbox.upsert(OutboxEntity("o9", """{"orders": []}""", 50000, 0, null, 0, 0))
            val results = kotlinx.coroutines.coroutineScope {
                listOf(async(kotlinx.coroutines.Dispatchers.IO) { sync.flushAll() }, async(kotlinx.coroutines.Dispatchers.IO) { sync.flushAll() })
                    .map { it.await() }
            }
            assertEquals(1, slow.requestCount)
            assertEquals(1, results.sum())
        } finally {
            slow.shutdown()
        }
    }
}
