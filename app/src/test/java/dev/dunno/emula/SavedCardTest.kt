package dev.dunno.emula

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedCardTest {
    @Test
    fun `copies uid supplied by caller`() {
        val source = byteArrayOf(1, 2, 3, 4)
        val card = SavedCard("id", "Office", source)

        source[0] = 9

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), card.uid)
    }

    @Test
    fun `compares uid by content`() {
        val card = SavedCard("id", "Office", byteArrayOf(1, 2, 3, 4))

        assertTrue(card.hasSameUid(byteArrayOf(1, 2, 3, 4)))
        assertFalse(card.hasSameUid(byteArrayOf(4, 3, 2, 1)))
        assertFalse(card.hasSameUid(null))
    }

    @Test
    fun `requires exactly four bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            SavedCard("id", "Office", byteArrayOf(1, 2, 3))
        }
    }

}
