package com.bohemian.odoo.pos.odoo

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Odoo Online's 429 (Too Many Requests): wait as told and send again, never fail at once. */
class OdooThrottleTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun client() = OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" })

    @Test fun `a 429 is waited out and the call succeeds`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setBody("[7]"))
        val started = System.currentTimeMillis()
        assertEquals("[7]", client().call("pos.config", "search", jsonOf()).toString())
        assertEquals(2, server.requestCount)
        assertTrue(System.currentTimeMillis() - started >= 1_000)
    }

    @Test fun `Odoo still busy after the retries gives a clear 429`() = runBlocking {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0")) }
        val e = runCatching { client().call("pos.config", "search", jsonOf()) }.exceptionOrNull()
        assertEquals(429, (e as OdooException.Server).status)
        assertEquals(4, server.requestCount)
    }
}
