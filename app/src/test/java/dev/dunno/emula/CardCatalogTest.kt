package dev.dunno.emula

import org.junit.Assert.assertEquals
import org.junit.Test

class CardCatalogTest {
    private val first = SavedCard("one", "Home", byteArrayOf(1, 2, 3, 4))
    private val second = SavedCard("two", "Office", byteArrayOf(5, 6, 7, 8))

    @Test
    fun `appends a new card`() {
        val result = CardCatalog.upsert(listOf(first), second)

        assertEquals(listOf("one", "two"), result.map { it.id })
    }

    @Test
    fun `updates a card without changing its position`() {
        val updated = SavedCard("one", "Front door", byteArrayOf(9, 10, 11, 12))

        val result = CardCatalog.upsert(listOf(first, second), updated)

        assertEquals(listOf("one", "two"), result.map { it.id })
        assertEquals("Front door", result.first().name)
    }

    @Test
    fun `deletes only the requested card`() {
        val result = CardCatalog.delete(listOf(first, second), "one")

        assertEquals(listOf("two"), result.map { it.id })
    }
}
