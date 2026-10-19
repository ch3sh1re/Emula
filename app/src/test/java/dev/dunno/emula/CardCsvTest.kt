package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException

class CardCsvTest {
    @Test
    fun roundTripPreservesUnicodeQuotesCommasMultilineNamesAndEveryUidLength() {
        val cards = listOf(
            SavedCard("private-id", "Вход, \"офис\"", byteArrayOf(0, 1, 2, -1)),
            SavedCard("other-id", "Line one\r\nLine two", ByteArray(7) { it.toByte() }),
            SavedCard("third-id", " Café ", ByteArray(10) { (255 - it).toByte() }),
        )
        val csv = CardCsv.encode(cards)
        assertTrue(csv.startsWith("name,uid\r\n"))
        assertTrue(csv.contains("\"Вход, \"\"офис\"\"\",000102FF"))
        assertFalse(csv.contains("private-id"))
        val decoded = CardCsv.read(ByteArrayInputStream(csv.toByteArray(StandardCharsets.UTF_8)))
        assertEquals(cards.map { it.name }, decoded.map { it.name })
        cards.indices.forEach { assertArrayEquals(cards[it].uid, decoded[it].uid) }
    }

    @Test
    fun acceptsBomBlankLinesCrLfQuotedFieldsAndLowercaseHex() {
        val entries = CardCsv.decode("\uFEFFname,uid\r\n\r\n\"Home\",\"00ff1234\"\r\n")
        assertEquals("Home", entries.single().name)
        assertArrayEquals(byteArrayOf(0, -1, 0x12, 0x34), entries.single().uid)
    }

    @Test
    fun rejectsMalformedFileEvenAfterValidCards() {
        val invalidRows = listOf(
            "Bad,1234567", "Bad,0102030405", "Bad,GG020304", "Bad,01020304,extra",
            ",01020304", "   ,01020304", "\"Unclosed,01020304",
            "Un\"quoted,01020304", "\"Closed\"extra,01020304",
        )
        invalidRows.forEach { row ->
            assertThrows(CardCsv.FormatException::class.java) {
                CardCsv.decode("name,uid\nValid,01020304\n" + row)
            }
        }
    }

    @Test
    fun rejectsWrongHeaderHeaderOnlyAndEmptyFiles() {
        listOf("", "\n\n", "name,uid\n", "uid,name\n01020304,Home",
            "name;uid\nHome;01020304", "name,uid,id\nHome,01020304,x").forEach { csv ->
            assertThrows(CardCsv.FormatException::class.java) { CardCsv.decode(csv) }
        }
    }

    @Test
    fun rejectsInvalidUtf8InsteadOfSilentlyChangingName() {
        val bytes = "name,uid\n".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) +
            ",01020304\n".toByteArray()
        assertThrows(CardCsv.FormatException::class.java) {
            CardCsv.read(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun limitsInputSizeAndNumberOfRecords() {
        assertThrows(CardCsv.FormatException::class.java) {
            CardCsv.read(ByteArrayInputStream(ByteArray(CardCsv.MAX_BYTES + 1)))
        }
        assertThrows(CardCsv.FormatException::class.java) {
            CardCsv.decode("name,uid\n" + "Home,01020304\n".repeat(CardCsv.MAX_CARDS + 1))
        }
    }

    @Test
    fun reportsPhysicalLineAfterQuotedMultilineName() {
        val failure = assertThrows(CardCsv.FormatException::class.java) {
            CardCsv.decode("name,uid\n\"First\nSecond\",01020304\nBroken,XX")
        }
        assertTrue(failure.message!!.startsWith("Line 4:"))
    }

    @Test
    fun repeatedUidsRemainDataUntilAnImportModeIsChosen() {
        val entries = CardCsv.decode("name,uid\nHome,01020304\nOther name,01020304\n")
        assertEquals(listOf("Home", "Other name"), entries.map { it.name })
    }

    @Test
    fun interruptedImportStopsBeforeReadingOrParsing() {
        val input = object : InputStream() {
            override fun read(): Int = throw AssertionError("Must not read after cancellation")
        }
        Thread.currentThread().interrupt()
        try {
            assertThrows(CancellationException::class.java) { CardCsv.read(input) }
            assertThrows(CancellationException::class.java) { CardCsv.decode("name,uid\nHome,01020304") }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun interruptBetweenChunksStopsFurtherReads() {
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int = throw AssertionError("Use bounded block reads")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                reads++
                bytes[offset] = 'x'.code.toByte()
                Thread.currentThread().interrupt()
                return 1
            }
        }
        try {
            assertThrows(CancellationException::class.java) { CardCsv.read(input) }
            assertEquals(1, reads)
        } finally {
            Thread.interrupted()
        }
    }
}
