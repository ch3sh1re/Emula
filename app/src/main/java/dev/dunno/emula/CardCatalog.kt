package dev.dunno.emula

/** Pure list operations shared by persistence code and unit tests. */
internal object CardCatalog {
    @JvmStatic
    fun upsert(cards: List<SavedCard>, saved: SavedCard): List<SavedCard> {
        val result = cards.toMutableList()
        val existingIndex = result.indexOfFirst { it.id == saved.id }
        if (existingIndex >= 0) {
            result[existingIndex] = saved
        } else {
            result += saved
        }
        return result
    }

    @JvmStatic
    fun delete(cards: List<SavedCard>, id: String): List<SavedCard> =
        cards.filterNot { it.id == id }
}
