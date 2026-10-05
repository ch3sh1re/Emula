package dev.dunno.emula

import java.util.Locale

internal object Hex {
    @JvmStatic
    fun bytes(data: ByteArray?): String = data
        ?.joinToString(separator = " ") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }
        ?: "<null>"
}
