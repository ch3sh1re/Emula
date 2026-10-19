package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test

class QuickCardPolicyTest {
    private val first = SavedCard("first", "Office", byteArrayOf(1, 2, 3, 4))
    private val duplicate = SavedCard("second", "Office", byteArrayOf(1, 2, 3, 4))

    @Test fun shortcutsResolveRecordIdentityEvenWithDuplicateUidAndName() {
        assertSame(duplicate, QuickCardPolicy.find(listOf(first, duplicate), "second"))
        assertFalse(QuickCardPolicy.deactivate(duplicate, first.id))
        assertTrue(QuickCardPolicy.deactivate(first, first.id))
    }

    @Test fun tileUsesLastSelectedCardAndFallsBackToCurrentCard() {
        assertSame(duplicate, QuickCardPolicy.tileCard(listOf(first, duplicate), first.id, duplicate.id))
        assertSame(duplicate, QuickCardPolicy.tileCard(listOf(first, duplicate), null, duplicate.id))
        assertSame(first, QuickCardPolicy.tileCard(listOf(first, duplicate), first.id, null))
    }

    @Test fun renamedAndEditedCardsResolveCurrentSavedContent() {
        val edited = SavedCard(first.id, "Renamed", ByteArray(7) { it.toByte() })
        assertSame(edited, QuickCardPolicy.find(listOf(edited), first.id))
    }

    @Test fun deletedAndReplacedIdsNeverSelectAnUnrelatedCard() {
        assertNull(QuickCardPolicy.find(listOf(duplicate), first.id))
        assertNull(QuickCardPolicy.tileCard(listOf(duplicate), first.id, first.id))
        assertNull(QuickCardPolicy.tileCard(emptyList(), first.id, duplicate.id))
    }
}
