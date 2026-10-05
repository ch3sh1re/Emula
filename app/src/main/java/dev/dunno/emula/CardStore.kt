package dev.dunno.emula

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object CardStore {
    const val UID_LENGTH = 4

    private const val FILE_NAME = "office_cards"
    private const val KEY_CARDS = "cards"

    @JvmStatic
    fun getCards(context: Context): List<SavedCard> {
        val serialized = preferences(context).getString(KEY_CARDS, "[]") ?: "[]"
        return try {
            val array = JSONArray(serialized)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val uid = Base64.decode(item.getString("uid"), Base64.NO_WRAP)
                    if (uid.size != UID_LENGTH) continue
                    add(
                        SavedCard(
                            id = item.getString("id"),
                            name = item.optString(
                                "name",
                                context.getString(R.string.default_card_name),
                            ),
                            uid = uid,
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
    fun save(context: Context, existing: SavedCard?, name: String?, uid: ByteArray): SavedCard {
        val normalizedName = name
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.default_card_name)
        val saved = SavedCard(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = normalizedName,
            uid = uid,
        )
        writeCards(context, CardCatalog.upsert(getCards(context), saved))
        return saved
    }

    @JvmStatic
    fun delete(context: Context, id: String) {
        writeCards(context, CardCatalog.delete(getCards(context), id))
    }

    private fun writeCards(context: Context, cards: List<SavedCard>) {
        try {
            val array = JSONArray()
            cards.forEach { card ->
                array.put(
                    JSONObject()
                        .put("id", card.id)
                        .put("name", card.name)
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
