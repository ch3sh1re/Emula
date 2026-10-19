package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class CardSearchTest {
    private val card = SavedCard("id", "Office · Вход", byteArrayOf(0, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()))

    @Test fun emptyQueryAndNamesUseTrimmedCaseInsensitiveSubstrings() {
        for (query in listOf("", "  ", "OFFICE", " вход ", "fice · Вх")) {
            assertTrue(query, CardSearch(query).matches(card))
        }
        assertFalse(CardSearch("missing").matches(card))
    }

    @Test fun uidFragmentsAcceptHexSeparatorsLeadingZeroAndPastedLabel() {
        for (query in listOf("00ab", "AB:cd", "ab-cd ef", " UID: 00 AB ", "bc", "00\u00a0ab")) {
            assertTrue(query, CardSearch(query).matches(card))
        }
        assertFalse(CardSearch("00 AC").matches(card))
    }

    @Test fun invalidUidCharactersAreNotSilentlyDiscarded() {
        for (query in listOf("abgcd", "ab/cd", "---", ":", "UID:", "00 ZZ ab")) {
            assertFalse(query, CardSearch(query).matches(card))
        }
    }

    @Test fun uidSearchCoversAllSupportedLengths() {
        for (size in listOf(4, 7, 10)) {
            val saved = SavedCard("$size", "Door", ByteArray(size) { (it + 1).toByte() })
            val tail = "%02x%02x".format(size - 1, size)
            assertTrue(CardSearch(tail).matches(saved))
        }
    }

    @Test fun filteringRetainsRecordIdentityCatalogOrderAndBytes() {
        val duplicate = SavedCard("other-id", card.name, card.uid)
        val source = listOf(card, SavedCard("unrelated", "Garage", byteArrayOf(1, 2, 3, 4)), duplicate)
        val query = CardSearch("office")
        val found = source.filter { query.matches(it) }
        assertEquals(listOf(card, duplicate), found)
        assertSame(card, found[0])
        assertSame(duplicate, found[1])
        assertArrayEquals(byteArrayOf(0, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()), card.uid)
    }

    @Test fun matchingDoesNotDependOnDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertTrue(CardSearch("OFFICE").matches(card))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
