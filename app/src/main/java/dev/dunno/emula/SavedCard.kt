package dev.dunno.emula

internal class SavedCard(
    @JvmField val id: String,
    @JvmField val name: String,
    uid: ByteArray,
) {
    @JvmField
    val uid: ByteArray = uid.copyOf()

    init {
        require(uid.size == CardStore.UID_LENGTH) {
            "UID must contain exactly four bytes"
        }
    }

    fun hasSameUid(other: ByteArray?): Boolean = other != null && uid.contentEquals(other)
}
