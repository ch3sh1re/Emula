package dev.dunno.emula

internal class SavedCard(
    @JvmField val id: String,
    @JvmField val name: String,
    uid: ByteArray,
) {
    @JvmField
    val uid: ByteArray = uid.copyOf()

    init {
        require(UidCodec.isSupportedLength(uid.size)) {
            "UID must contain 4, 7, or 10 bytes"
        }
    }

    fun hasSameUid(other: ByteArray?): Boolean = other != null && uid.contentEquals(other)
}
