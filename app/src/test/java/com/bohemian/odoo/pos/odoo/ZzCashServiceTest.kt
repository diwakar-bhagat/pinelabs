package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZzCashServiceTest {

    private fun card(id: Int, points: Double) = JsonObject(mapOf(
        "id" to JsonPrimitive(id),
        "points" to JsonPrimitive(points),
        "code" to JsonPrimitive("0445-91a9-4581"),
    ))

    @Test fun `no card means no balance`() {
        assertNull(ZzCashService.fromRows(partnerId = 83, rows = emptyList()))
    }

    @Test fun `reads card id and points in paise`() {
        val b = ZzCashService.fromRows(83, listOf(card(277, 501.84)))!!
        assertEquals(277, b.cardId)
        assertEquals(83, b.partnerId)
        assertEquals(Paise(50184), b.points)
    }

    @Test fun `redeemable is whole rupees only`() {
        val b = ZzCashService.fromRows(83, listOf(card(277, 501.84)))!!
        assertEquals(Paise(50100), b.redeemable)
    }

    @Test fun `exact rupees are fully redeemable`() {
        val b = ZzCashService.fromRows(1, listOf(card(5, 150.0)))!!
        assertEquals(Paise(15000), b.redeemable)
    }

    @Test fun `under one rupee redeems nothing`() {
        val b = ZzCashService.fromRows(1, listOf(card(5, 0.99)))!!
        assertEquals(Paise.ZERO, b.redeemable)
    }

    @Test fun `negative balance is shown as zero`() {
        val b = ZzCashService.fromRows(1, listOf(card(5, -12.5)))!!
        assertEquals(Paise.ZERO, b.points)
        assertEquals(Paise.ZERO, b.redeemable)
    }

    @Test fun `float noise from Odoo does not leak into paise`() {
        // Odoo stores 501.84000000000003 for 501.84 after arithmetic.
        val b = ZzCashService.fromRows(1, listOf(card(5, 501.84000000000003)))!!
        assertEquals(Paise(50184), b.points)
    }
}
