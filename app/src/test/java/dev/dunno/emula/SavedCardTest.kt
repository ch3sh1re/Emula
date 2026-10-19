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
    fun `requires four seven or ten bytes`() {
        for (length in listOf(0, 3, 5, 6, 8, 9, 11)) {
            assertThrows(IllegalArgumentException::class.java) {
                SavedCard("id", "Office", ByteArray(length))
            }
        }
    }

    @Test
    fun `long uids preserve every byte and differ from truncated versions`() {
        for (length in listOf(7, 10)) {
            val source = ByteArray(length) { (it + 1).toByte() }
            val expected = source.copyOf()
            val card = SavedCard("id", "Office", source)
            source[length - 1] = 0
            assertArrayEquals(expected, card.uid)
            assertTrue(card.hasSameUid(expected))
            assertFalse(card.hasSameUid(expected.copyOf(4)))
        }
    }
}
