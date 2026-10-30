package com.bohemian.odoo.pos.odoo

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** F5: one row per till in Odoo's till list, created once, then written. */
class TillStatusTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun status() = TillStatus(OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" }))
    private val report = TillStatus.Report("1340055807", "SWN", "0.7.0", outbox = 2, rejected = 1, unresolved = 0,
        notSentAtOnce = 3, callsPerMinute = 1.4, throttledToday = 0, lastError = "No open session available.", keyExpires = "2027-01-20")

    private fun path() = server.takeRequest().path!!.removePrefix("/json/2/")

    @Test fun `the first report creates the till's row, later ones write it`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[7]"))
        server.enqueue(MockResponse().setBody("true"))
        val s = status()
        assertTrue(s.send(report, "2026-10-30 10:00:00"))
        assertTrue(s.send(report.copy(outbox = 0), "2026-10-30 10:15:00"))
        assertEquals("x_e700_till/search", path())
        val create = server.takeRequest()
        assertEquals("/json/2/x_e700_till/create", create.path)
        assertTrue(create.body.readUtf8().contains("\"x_name\":\"1340055807\""))
        assertEquals("x_e700_till/write", path())
    }

    @Test fun `a till whose row exists finds it and writes it`() = runBlocking {
        server.enqueue(MockResponse().setBody("[12]"))
        server.enqueue(MockResponse().setBody("true"))
        assertTrue(status().send(report, "2026-10-30 10:00:00"))
        assertEquals("x_e700_till/search", path())
        assertEquals("x_e700_till/write", path())
    }

    @Test fun `without the model in Odoo the till stops trying, silently`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"name": "werkzeug.exceptions.NotFound", "message": "the model 'x_e700_till' does not exist"}"""))
        val s = status()
        assertFalse(s.send(report, "2026-10-30 10:00:00"))
        assertFalse(s.send(report, "2026-10-30 10:15:00"))
        assertEquals(1, server.requestCount)
    }
}
