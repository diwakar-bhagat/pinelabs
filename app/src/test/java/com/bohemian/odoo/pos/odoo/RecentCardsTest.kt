package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** A waiting bill reopened within 2 minutes reuses the last card read (0.9.1). */
class RecentCardsTest {
    private var clock = 1_000_000L
    private val cards = RecentCards { clock }
    private val card = ZzCard(ZzCashBalance(partnerId = 83, cardId = 277, points = Paise(50184)), customer = null)

    @Test fun `a read within 2 minutes is reused, an older one is not`() {
        cards.put(83, 4, card)
        clock += 119_000
        assertEquals(Paise(50184), cards.fresh(83, 4)!!.card!!.balance.points)
        clock += 1_000
        assertNull(cards.fresh(83, 4))
    }

    @Test fun `no card is reused as no card`() {
        cards.put(84, 4, null)
        val hit = cards.fresh(84, 4)
        assertNotNull(hit)
        assertNull(hit!!.card)
    }

    @Test fun `paying forgets the customer, and another program never matches`() {
        cards.put(83, 4, card)
        assertNull(cards.fresh(83, 5))
        cards.forget(83)
        assertNull(cards.fresh(83, 4))
        assertNull(cards.fresh(99, 4))
    }
}
