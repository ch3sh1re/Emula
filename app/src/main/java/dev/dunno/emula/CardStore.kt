package dev.dunno.emula

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object CardStore {
    private const val FILE_NAME = "office_cards"
    private const val KEY_CARDS = "cards"

    @JvmStatic
    @Synchronized
    fun getCards(context: Context): List<SavedCard> {
        val serialized = preferences(context).getString(KEY_CARDS, "[]") ?: "[]"
        return try {
            val array = JSONArray(serialized)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val uid = Base64.decode(item.getString("uid"), Base64.NO_WRAP)
                    if (!UidCodec.isSupportedLength(uid.size)) continue
                    add(
                        SavedCard(
                            id = item.getString("id"),
                            name = item.optString(
                                "name",
                                context.getString(R.string.default_card_name),
                            ),
                            uid = uid,
                            colorHue = item.optDouble("colorHue").toFloat()
                                .takeIf(CardPalette::valid) ?: CardPalette.defaultHue(size),
                        ),
                    )
                }
            }
        } catch (error: Exception) {
            AppLog.warning("Could not read saved cards", error)
            emptyList()
        }
    }

    @JvmStatic
    @Synchronized
    fun save(context: Context, existing: SavedCard?, name: String?, uid: ByteArray): SavedCard {
        val normalizedName = name
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.default_card_name)
        val cards = getCards(context)
        val hue = cards.firstOrNull { it.id == existing?.id }?.colorHue
            ?: unusedHue(cards.size, cards.mapNotNullTo(mutableSetOf()) { it.colorHue })
        val saved = SavedCard(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = normalizedName,
            uid = uid,
            colorHue = hue,
        )
        writeCards(context, CardCatalog.upsert(cards, saved))
        return saved
    }

    @JvmStatic
    @Synchronized
    fun delete(context: Context, id: String) {
        writeCards(context, CardCatalog.delete(getCards(context), id))
    }

    @JvmStatic
    @Synchronized
    fun replaceAll(context: Context, cards: List<SavedCard>) {
        writeCards(context, cards)
    }

    @JvmStatic
    @Synchronized
    fun appendReceived(context: Context, incoming: List<WatchCardPacket.Entry>): WatchCardPacket.Result {
        val merge = WatchCardPacket.append(
            getCards(context), incoming, context.getString(R.string.default_card_name),
        )
        if (merge.result.added > 0) writeCards(context, merge.cards)
        return merge.result
    }

    @JvmStatic
    @Synchronized
    fun applyReceivedColors(context: Context, incoming: List<WatchCardPacket.Entry>, hues: FloatArray) {
        val colors = mutableMapOf<String, Float>()
        incoming.forEachIndexed { index, entry -> colors.putIfAbsent(Hex.bytes(entry.uid), hues[index]) }
        val cards = getCards(context)
        val updated = cards.map { card ->
            val hue = colors[Hex.bytes(card.uid)]
            if (hue == null || hue == card.colorHue) card else SavedCard(card.id, card.name, card.uid, hue)
        }
        if (updated != cards) writeCards(context, updated)
    }

    private fun unusedHue(index: Int, used: Set<Float>): Float =
        generateSequence(index) { it + 1 }.map(CardPalette::defaultHue).first { it !in used }

    private fun writeCards(context: Context, cards: List<SavedCard>) {
        try {
            val array = JSONArray()
            val used = cards.mapNotNullTo(mutableSetOf()) { it.colorHue }
            cards.forEachIndexed { index, card ->
                val hue = card.colorHue ?: unusedHue(index, used).also { used.add(it) }
                array.put(
                    JSONObject()
                        .put("id", card.id)
                        .put("name", card.name)
                        .put("colorHue", hue.toDouble())
                        .put("uid", Base64.encodeToString(card.uid, Base64.NO_WRAP)),
                )
            }
            check(
                preferences(context)
                    .edit()
                    .putString(KEY_CARDS, array.toString())
                    .commit(),
            ) { "SharedPreferences commit failed" }
        } catch (error: Exception) {
            throw IllegalStateException("Could not save cards", error)
        }
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
