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
