package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test

class CardImportTest {
    private val original = SavedCard("original-id", "Keep this name", byteArrayOf(1, 2, 3, 4))
    private val incoming = CardCsv.decode(
        "name,uid\nChanged name,01020304\nNew,05060708\nRepeated new,05060708\n",
    )

    @Test
    fun addPreservesOriginalsAndSkipsExistingAndRepeatedUids() {
        val result = CardImport.plan(listOf(original), incoming, false)
        assertSame(original, result.cards[0])
        assertEquals(listOf("Keep this name", "New"), result.cards.map { it.name })
        assertEquals(1, result.added)
        assertEquals(2, result.skipped)
        assertNotEquals(original.id, result.cards[1].id)
    }

    @Test
    fun replaceUsesOnlyTheFileWithNewLocalIdentities() {
        val result = CardImport.plan(listOf(original), incoming, true)
        assertEquals(listOf("Changed name", "New", "Repeated new"), result.cards.map { it.name })
        assertEquals(3, result.added)
        assertEquals(0, result.skipped)
        assertTrue(result.cards.none { it.id == original.id })
        assertEquals(result.cards.size, result.cards.map { it.id }.distinct().size)
    }

    @Test
    fun reimportingBackupLeavesTheExistingCatalogUnchanged() {
        val first = CardImport.plan(emptyList(), incoming, false)
        val second = CardImport.plan(first.cards, incoming, false)
        assertEquals(first.cards, second.cards)
        assertEquals(0, second.added)
        assertEquals(3, second.skipped)
    }

    @Test
    fun failedValidationNeverMutatesTheOriginalCatalog() {
        val before = mutableListOf(original)
        val invalid = listOf(incoming[1], CardCsv.Entry("Invalid", byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) {
            CardImport.plan(before, invalid, true)
        }
        assertEquals(listOf(original), before)
        assertThrows(IllegalArgumentException::class.java) {
            CardImport.plan(before, emptyList(), true)
        }
    }
}
