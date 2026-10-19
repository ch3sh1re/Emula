package dev.dunno.emula

import android.content.Context
import java.util.UUID

/** Phone CSV imports; shared card storage/catalog stay independent of file formats. */
internal object CardImport {
    class Result(
        @JvmField val cards: List<SavedCard>,
        @JvmField val added: Int,
        @JvmField val skipped: Int,
    )

    @JvmStatic
    fun apply(context: Context, incoming: List<CardCsv.Entry>, replace: Boolean): Result {
        val result = plan(CardStore.getCards(context), incoming, replace)
        CardStore.replaceAll(context, result.cards)
        return result
    }

    /** A merge never changes existing IDs, names, UIDs, or their order. */
    @JvmStatic
    fun plan(
        existing: List<SavedCard>,
        incoming: List<CardCsv.Entry>,
        replace: Boolean,
    ): Result {
        require(incoming.isNotEmpty()) { "The CSV contains no cards." }
        val cards = if (replace) mutableListOf() else existing.toMutableList()
        val uids = cards.mapTo(mutableSetOf()) { Hex.bytes(it.uid) }
        var added = 0
        incoming.forEach { entry ->
            require(entry.name.isNotBlank() && UidCodec.isSupportedLength(entry.uid.size)) {
                "Invalid imported card"
            }
            if (replace || uids.add(Hex.bytes(entry.uid))) {
                cards += SavedCard(UUID.randomUUID().toString(), entry.name, entry.uid)
                added++
            }
        }
        return Result(cards.toList(), added, incoming.size - added)
    }
}
