package dev.dunno.emula

internal class SavedCard @JvmOverloads constructor(
    @JvmField val id: String,
    @JvmField val name: String,
    uid: ByteArray,
    @JvmField val colorHue: Float? = null,
) {
    @JvmField
    val uid: ByteArray = uid.copyOf()

    init {
        require(UidCodec.isSupportedLength(uid.size)) {
            "UID must contain 4, 7, or 10 bytes"
        }
        require(colorHue == null || CardPalette.valid(colorHue)) { "Color hue must be in [0, 360)" }
    }

    fun hasSameUid(other: ByteArray?): Boolean = other != null && uid.contentEquals(other)

    fun hueOrDefault(index: Int): Float = colorHue ?: CardPalette.defaultHue(index)
}
