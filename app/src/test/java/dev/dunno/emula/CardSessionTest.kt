package dev.dunno.emula

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSessionTest {
    @After
    fun clearSession() {
        CardSession.clearActiveCard()
    }

    @Test
    fun `keeps at most one active card`() {
        CardSession.setActiveCardId("home")
        assertTrue(CardSession.isActive("home"))

        CardSession.setActiveCardId("office")

        assertFalse(CardSession.isActive("home"))
        assertTrue(CardSession.isActive("office"))
        assertEquals("office", CardSession.getActiveCardId())
    }

    @Test
    fun `clears active card`() {
        CardSession.setActiveCardId("home")

        CardSession.clearActiveCard()

        assertNull(CardSession.getActiveCardId())
        assertFalse(CardSession.isActive("home"))
    }
}
