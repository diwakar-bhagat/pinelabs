package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.pine.PayMode
import com.bohemian.odoo.pos.pine.TxnDetails
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** F2: the fields of Odoo the till depends on, checked when Odoo's version changes. */
class OdooContractTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun odoo() = OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" })

    private val chai = Product(8202, "Cardamom Chai", Paise.ofRupees(60), null, null, listOf(30))

    /** A card sale with every terminal detail, so every pos.payment key the till can send is there. */
    private fun cardSale() = PaidSale(
        orderUuid = "u-1", sessionId = 9,
        lines = listOf(CartLine("l1", chai, 1, Paise.ofRupees(60), Paise(5714), note = listOf(LineNote("Less sugar")))),
        totals = CartTotals(Paise.ofRupees(60), Paise(5714)), customer = Customer(83, "A Customer", "+919000000000"),
        payments = listOf(PaymentLineData(22, Paise.ofRupees(60), PayMode.CARD, "SWN0261030120000ABC",
            TxnDetails(transactionId = "T1", rrn = "R1", authCode = "A1", last4 = "4242", cardType = "VISA", acquirer = "HDFC"))),
        paidAtMillis = 0,
    )

    @Test fun `every key a sale sends is in the contract`() {
        assertEquals(emptyList<String>(), OdooContract.uncovered(OrderSyncService.buildPayload(cardSale())))
    }

    @Test fun `a key the contract does not list is caught`() {
        val payload = jsonOf("orders" to listOf(mapOf("uuid" to "u", "lines" to listOf(listOf(0, 0, mapOf("qty" to 1, "x_new" to 2))))))
        assertEquals(listOf("pos.order.line.x_new"), OdooContract.uncovered(payload))
    }

    private fun fieldsGet(dropped: Map<String, String> = emptyMap(), version: String = "19.0+e") = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!
            if (path == "/web/version") return MockResponse().setBody("""{"version_info": [19, 0, 0, "final", 0, "e"], "version": "$version"}""")
            val model = path.removePrefix("/json/2/").substringBefore('/')
            val fields = OdooContract.REQUIRED.getValue(model).filter { dropped[model] != it }
            return MockResponse().setBody(fields.joinToString(",", "{", "}") { "\"$it\": {\"type\": \"char\"}" })
        }
    }

    @Test fun `a new Odoo version with every field passes, and the same version is not checked again`() = runBlocking {
        server.dispatcher = fieldsGet()
        assertEquals(OdooContractCheck.Result.Ok("19.0+e"), OdooContractCheck(odoo()).run(verified = null))
        val calls = server.requestCount
        assertEquals(OdooContractCheck.Result.Ok("19.0+e"), OdooContractCheck(odoo()).run(verified = "19.0+e"))
        assertEquals(calls + 1, server.requestCount) // only /web/version
    }

    @Test fun `an upgrade that drops a field the till sends is Broken`() = runBlocking {
        server.dispatcher = fieldsGet(mapOf("pos.payment" to "card_no"), version = "saas~19.1+e")
        val r = OdooContractCheck(odoo()).run(verified = "19.0+e")
        assertEquals(OdooContractCheck.Result.Broken("saas~19.1+e", listOf("pos.payment.card_no")), r)
    }

    @Test fun `Odoo out of reach proves nothing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(OdooContractCheck(odoo()).run(verified = "19.0+e") is OdooContractCheck.Result.Unknown)
    }
}
