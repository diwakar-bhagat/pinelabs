package com.bohemian.odoo.pos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupPinTest {
    private class MemStore : PinStore {
        override var pinHash: String? = null
        override val pinSalt = "salt-of-this-till"
        override var pinFailures = 0
        override var pinLockedUntil = 0L
    }

    private var now = 1_000_000L
    private val store = MemStore()
    private val pin = SetupPin(store) { now }

    @Test fun `1234 opens Setup until it is changed`() {
        assertEquals(SetupPin.Check.Ok, pin.check("1234"))
        assertEquals(SetupPin.Check.Wrong(SetupPin.MAX_TRIES - 1), pin.check("0000"))
    }

    @Test fun `a changed PIN is stored only as a salted hash`() {
        assertNull(pin.change("1234", "2580", "2580"))
        assertEquals(SetupPin.Check.Ok, pin.check("2580"))
        assertTrue(pin.check("1234") is SetupPin.Check.Wrong)
        assertNotEquals("2580", store.pinHash)
        assertEquals(64, store.pinHash!!.length)
    }

    @Test fun `changing needs the current PIN and a well-formed new one`() {
        assertEquals("The new PIN must be 4 to 6 digits.", pin.change("1234", "12", "12"))
        assertEquals("The two new PINs are not the same.", pin.change("1234", "2580", "2581"))
        assertTrue(pin.change("9999", "2580", "2580")!!.startsWith("The current PIN is wrong"))
        assertEquals(SetupPin.Check.Ok, pin.check("1234")) // nothing changed
    }

    @Test fun `five wrong PINs lock it for a minute, also for the right PIN`() {
        repeat(SetupPin.MAX_TRIES - 1) { assertTrue(pin.check("0000") is SetupPin.Check.Wrong) }
        assertEquals(SetupPin.Check.Locked(60), pin.check("0000"))
        assertTrue(pin.check("1234") is SetupPin.Check.Locked)
        now += SetupPin.LOCK_MS
        assertEquals(SetupPin.Check.Ok, pin.check("1234"))
    }

    @Test fun `a right PIN resets the wrong count`() {
        repeat(3) { pin.check("0000") }
        assertEquals(SetupPin.Check.Ok, pin.check("1234"))
        assertEquals(0, store.pinFailures)
    }
}
