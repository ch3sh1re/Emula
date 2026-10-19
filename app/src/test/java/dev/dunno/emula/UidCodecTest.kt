package dev.dunno.emula

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UidCodecTest {
    @Test
    fun `parses four hexadecimal bytes`() {
        assertArrayEquals(
            byteArrayOf(0x01, 0xAB.toByte(), 0x00, 0xFF.toByte()),
            UidCodec.parse(arrayOf("01", "ab", "00", "FF")),
        )
    }

    @Test
    fun `parses complete seven and ten byte uids`() {
        val parts = arrayOf("04", "ab", "00", "01", "ff", "80", "07", "08", "09", "0A")
        val expected = byteArrayOf(4, 0xAB.toByte(), 0, 1, 0xFF.toByte(), 0x80.toByte(), 7, 8, 9, 10)
        assertArrayEquals(expected.copyOf(7), UidCodec.parse(parts.copyOfRange(0, 7)))
        assertArrayEquals(expected, UidCodec.parse(parts))
    }

    @Test
    fun `rejects all unsupported lengths and invalid bytes in long uids`() {
        for (length in listOf(0, 1, 2, 3, 5, 6, 8, 9, 11)) {
            assertNull(UidCodec.parse(Array(length) { "00" }))
        }
        for (length in listOf(4, 7, 10)) {
            for (invalid in listOf("", "0", "000", "GG", "١٢")) {
                val parts = Array(length) { "00" }
                parts[length - 1] = invalid
                assertNull(UidCodec.parse(parts))
            }
        }
    }

    @Test
    fun `rejects incomplete or invalid uid`() {
        assertNull(UidCodec.parse(arrayOf("01", "02", "03")))
        assertNull(UidCodec.parse(arrayOf("01", "02", "3", "04")))
        assertNull(UidCodec.parse(arrayOf("01", "02", "GG", "04")))
    }

    @Test
    fun `formats bytes as uppercase uid`() {
        assertEquals("01 AB 00 FF", Hex.bytes(byteArrayOf(0x01, 0xAB.toByte(), 0x00, 0xFF.toByte())))
    }
}
