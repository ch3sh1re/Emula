package dev.dunno.emula

/** Validation kept outside the Activity so it can be covered by fast JVM tests. */
internal object UidCodec {
    const val DEFAULT_UID_LENGTH = 4
    const val MAX_UID_LENGTH = 10

    @JvmStatic
    fun isSupportedLength(length: Int): Boolean = length == 4 || length == 7 || length == 10

    @JvmStatic
    fun parse(parts: Array<String>): ByteArray? {
        if (!isSupportedLength(parts.size)) return null
        return ByteArray(parts.size) { index ->
            val part = parts[index]
            if (part.length != 2 || part.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) {
                return null
            }
            part.toInt(16).toByte()
        }
    }
}
