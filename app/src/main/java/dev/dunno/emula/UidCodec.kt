package dev.dunno.emula

/** Validation kept outside the Activity so it can be covered by fast JVM tests. */
internal object UidCodec {
    @JvmStatic
    fun parse(parts: Array<String>): ByteArray? {
        if (parts.size != CardStore.UID_LENGTH) return null
        return ByteArray(parts.size) { index ->
            val part = parts[index]
            if (part.length != 2 || part.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) {
                return null
            }
            part.toInt(16).toByte()
        }
    }
}
