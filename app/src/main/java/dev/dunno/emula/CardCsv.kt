package dev.dunno.emula

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException

/** Portable card data only: local IDs and activation state never leave the device. */
internal object CardCsv {
    const val MIME_TYPE = "text/csv"
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_CARDS = 10_000

    class Entry(@JvmField val name: String, uid: ByteArray) {
        @JvmField val uid: ByteArray = uid.copyOf()
    }

    class FormatException(message: String) : IllegalArgumentException(message)

    @JvmStatic
    fun encode(cards: List<SavedCard>): String {
        if (cards.size > MAX_CARDS) throw FormatException("Too many cards (maximum 10000).")
        val text = buildString {
            append("name,uid\r\n")
            cards.forEach { card ->
                append(quote(card.name))
                append(',')
                append(Hex.bytes(card.uid).replace(" ", ""))
                append("\r\n")
            }
        }
        if (text.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES) {
            throw FormatException("CSV is too large (maximum 2 MiB).")
        }
        return text
    }

    @JvmStatic
    fun read(input: InputStream): List<Entry> {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkCancelled()
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            checkCancelled()
            if (count == -1) break
            if (output.size() + count > MAX_BYTES) {
                throw FormatException("CSV is too large (maximum 2 MiB).")
            }
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw FormatException("Save the CSV as UTF-8.")
        }
        return decode(text)
    }

    @JvmStatic
    fun decode(source: String): List<Entry> {
        checkCancelled()
        val text = source.removePrefix("\uFEFF")
        if (text.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES) {
            throw FormatException("CSV is too large (maximum 2 MiB).")
        }
        val entries = mutableListOf<Entry>()
        var headerSeen = false
        parse(text) { row, line ->
            if (row.size == 1 && row[0].isBlank()) return@parse
            if (!headerSeen) {
                if (row.map { it.trim().lowercase(java.util.Locale.ROOT) } != listOf("name", "uid")) {
                    throw FormatException("Expected the header name,uid.")
                }
                headerSeen = true
            } else {
                if (row.size != 2) throw FormatException("Line " + line + ": expected name and UID.")
                if (row[0].isBlank()) throw FormatException("Line " + line + ": card name is empty.")
                val hex = row[1].trim()
                val uid = if (hex.length == 8 || hex.length == 14 || hex.length == 20) {
                    UidCodec.parse(hex.chunked(2).toTypedArray())
                } else null
                if (uid == null) {
                    throw FormatException("Line " + line + ": UID must be 8, 14 or 20 HEX characters.")
                }
                if (entries.size == MAX_CARDS) throw FormatException("Too many cards (maximum 10000).")
                entries += Entry(row[0], uid)
            }
        }
        if (!headerSeen) throw FormatException("Expected the header name,uid.")
        if (entries.isEmpty()) throw FormatException("The CSV contains no cards.")
        return entries
    }

    private fun quote(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("CSV import cancelled")
    }

    private fun parse(text: String, consume: (List<String>, Int) -> Unit) {
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closedQuote = false
        var line = 1
        var rowLine = 1
        var index = 0
        var nextCancellationCheck = 0
        fun endField() {
            row += field.toString()
            field.setLength(0)
            closedQuote = false
        }
        while (index < text.length) {
            if (index >= nextCancellationCheck) {
                checkCancelled()
                nextCancellationCheck = index + 1024
            }
            val char = text[index++]
            if (quoted) {
                if (char == '"') {
                    if (index < text.length && text[index] == '"') {
                        field.append('"')
                        index++
                    } else {
                        quoted = false
                        closedQuote = true
                    }
                } else {
                    field.append(char)
                    if (char == '\n' || (char == '\r' &&
                                (index == text.length || text[index] != '\n'))) line++
                }
            } else when (char) {
                '"' -> {
                    if (field.isNotEmpty() || closedQuote) {
                        throw FormatException("Line " + line + ": unexpected quote.")
                    }
                    quoted = true
                }
                ',' -> endField()
                '\r', '\n' -> {
                    endField()
                    consume(row.toList(), rowLine)
                    row.clear()
                    if (char == '\r' && index < text.length && text[index] == '\n') index++
                    line++
                    rowLine = line
                }
                else -> {
                    if (closedQuote) throw FormatException("Line " + line + ": text after closing quote.")
                    field.append(char)
                }
            }
        }
        if (quoted) throw FormatException("Line " + rowLine + ": unclosed quote.")
        if (row.isNotEmpty() || field.isNotEmpty() || closedQuote) {
            endField()
            consume(row, rowLine)
        }
    }
}
