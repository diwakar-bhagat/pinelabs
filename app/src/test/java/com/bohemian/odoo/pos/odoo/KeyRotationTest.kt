package com.bohemian.odoo.pos.odoo

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/** F4: each till's own key, made at Setup and renewed before it expires; a crash at any step keeps a working key. */
class KeyRotationTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private class MemKeys(override var key: String? = null, override var expiresOn: String? = null, override var pending: String? = null) : KeyRotation.KeyStore

    private val t0 = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2026-10-30")!!.time

    private fun rotation(store: KeyRotation.KeyStore, at: Long = t0) = KeyRotation(
        clientFor = { key -> OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { key }) }, store = store, now = { at })

    private fun take() = server.takeRequest().let { (it.getHeader("Authorization") ?: "") to (it.path!!.removePrefix("/json/2/") + " " + it.body.readUtf8()) }

    @Test fun `enrolling makes the till's own key from the enrollment key`() = runBlocking {
        server.enqueue(MockResponse().setBody("\"OWN-KEY\""))
        server.enqueue(MockResponse().setBody("{\"uid\": 40}"))
        val keys = MemKeys()
        assertEquals("2027-01-23", rotation(keys).enroll("ENROLL", "E700 SWN 1340055807"))
        assertEquals("OWN-KEY", keys.key)
        assertEquals("2027-01-23", keys.expiresOn)
        val (auth, call) = take()
        assertEquals("bearer ENROLL", auth)
        assertTrue(call, call.startsWith("res.users.apikeys/generate") && "\"expiration_date\":\"2027-01-23\"" in call && "\"scope\":\"rpc\"" in call)
        assertEquals("bearer OWN-KEY", take().first) // checked before the till depends on it
    }

    @Test fun `a key far from expiry is left alone, a hand-entered key is never touched`() = runBlocking {
        assertEquals(KeyRotation.Outcome.NotDue, rotation(MemKeys("K", "2027-01-01")).rotateIfDue("n"))
        assertEquals(KeyRotation.Outcome.NotDue, rotation(MemKeys("K", null)).rotateIfDue("n"))
        assertEquals(0, server.requestCount)
    }

    @Test fun `14 days before expiry the key is renewed, then the old one revoked with the new one`() = runBlocking {
        server.enqueue(MockResponse().setBody("\"NEW\""))
        server.enqueue(MockResponse().setBody("{\"uid\": 40}"))
        server.enqueue(MockResponse().setBody("true"))
        val keys = MemKeys("OLD", "2026-11-10")
        assertEquals(KeyRotation.Outcome.Rotated("2027-01-23"), rotation(keys).rotateIfDue("E700 SWN 1"))
        assertEquals("NEW", keys.key)
        assertNull(keys.pending)
        assertEquals("bearer OLD", take().first)
        assertEquals("bearer NEW", take().first)
        val (auth, revoke) = take()
        assertEquals("bearer NEW", auth)
        assertTrue(revoke, revoke.startsWith("res.users.apikeys/revoke") && "\"key\":\"OLD\"" in revoke)
    }

    @Test fun `a failure after the new key is made keeps the old key and finishes next time`() = runBlocking {
        server.enqueue(MockResponse().setBody("\"NEW\""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("{\"name\": \"builtins.Exception\", \"message\": \"boom\"}"))
        val keys = MemKeys("OLD", "2026-11-10")
        assertTrue(rotation(keys).rotateIfDue("n") is KeyRotation.Outcome.Failed)
        assertEquals("OLD", keys.key) // still working
        assertEquals("NEW", keys.pending) // kept, not lost
        server.enqueue(MockResponse().setBody("{\"uid\": 40}"))
        server.enqueue(MockResponse().setBody("true"))
        assertEquals(KeyRotation.Outcome.Rotated("2027-01-23"), rotation(keys).rotateIfDue("n"))
        assertEquals("NEW", keys.key)
        assertEquals(4, server.requestCount) // no second key generated
    }
}
