package dev.dunno.emula

/** Resolve stable record IDs, never a UID/name copied into an old launcher item. */
internal object QuickCardPolicy {
    @JvmStatic
    fun find(cards: List<SavedCard>, id: String?): SavedCard? =
        cards.firstOrNull { it.id == id }

    @JvmStatic
    fun tileCard(cards: List<SavedCard>, activeId: String?, lastId: String?): SavedCard? =
        find(cards, lastId) ?: find(cards, activeId)

    @JvmStatic
    fun deactivate(card: SavedCard, activeId: String?): Boolean = card.id == activeId
}
