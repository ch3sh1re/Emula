package dev.dunno.emula

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.LinkedHashMap

class RawNciCodecTest {
    @Test
    fun uidOnlyPayloadContainsOnlyLaNfcid1() {
        assertArrayEquals(
            byteArrayOf(0x01, 0x33, 0x04, 0xA6.toByte(), 0xBA.toByte(),
                0x9F.toByte(), 0xA6.toByte()),
            RawNciCodec.uidOnlyPayload(
                byteArrayOf(0xA6.toByte(), 0xBA.toByte(), 0x9F.toByte(), 0xA6.toByte()))
        )
    }

    @Test
    fun frameworkStatusesHaveStableNames() {
        assertEquals("SUCCESS", RawNciCodec.frameworkStatusName(0))
        assertEquals("REJECTED", RawNciCodec.frameworkStatusName(1))
        assertEquals("MESSAGE_CORRUPTED", RawNciCodec.frameworkStatusName(2))
        assertEquals("FAILED", RawNciCodec.frameworkStatusName(3))
        assertEquals("UNKNOWN_FRAMEWORK_STATUS_7", RawNciCodec.frameworkStatusName(7))
    }

    @Test
    fun nciStatusesHaveStableNames() {
        assertEquals("OK", RawNciCodec.nciStatusName(0x00))
        assertEquals("REJECTED", RawNciCodec.nciStatusName(0x01))
        assertEquals("FAILED", RawNciCodec.nciStatusName(0x03))
        assertEquals("NOT_INITIALIZED", RawNciCodec.nciStatusName(0x04))
        assertEquals("SYNTAX_ERROR", RawNciCodec.nciStatusName(0x05))
        assertEquals("SEMANTIC_ERROR", RawNciCodec.nciStatusName(0x06))
        assertEquals("INVALID_PARAM", RawNciCodec.nciStatusName(0x09))
        assertEquals("MESSAGE_SIZE_EXCEEDED", RawNciCodec.nciStatusName(0x0A))
        assertEquals("UNKNOWN_NCI_STATUS_0x42", RawNciCodec.nciStatusName(0x42))
    }

    @Test
    fun rejectedSetParametersArePreserved() {
        assertEquals(
            listOf(0x85, 0x33, 0x58, 0x59),
            RawNciCodec.parseRejectedParameterIds(
                byteArrayOf(0x09, 0x04, 0x85.toByte(), 0x33, 0x58, 0x59))
        )
    }

    @Test
    fun getConfigParserSupportsValuesZeroLengthAndUnknownParameters() {
        val values = RawNciCodec.parseGetConfigResponse(
            byteArrayOf(
                0x00, 0x03,
                0x33, 0x04, 0xA6.toByte(), 0xBA.toByte(), 0x9F.toByte(), 0xA6.toByte(),
                0x59, 0x00,
                0x7F, 0x02, 0x12, 0x34
            )
        )

        assertArrayEquals(
            byteArrayOf(0xA6.toByte(), 0xBA.toByte(), 0x9F.toByte(), 0xA6.toByte()),
            values[0x33]
        )
        assertArrayEquals(byteArrayOf(), values[0x59])
        assertArrayEquals(byteArrayOf(0x12, 0x34), values[0x7F])
    }

    @Test
    fun getConfigParserRejectsTruncatedValues() {
        assertThrows(IllegalArgumentException::class.java) {
            RawNciCodec.parseGetConfigResponse(
                byteArrayOf(0x00, 0x01, 0x33, 0x04, 0x01, 0x02)
            )
        }
    }

    @Test
    fun configPayloadRestoresExactValuesIncludingEmptyParameters() {
        val values = linkedMapOf(
            0x85 to byteArrayOf(0x01),
            0x33 to byteArrayOf(),
            0x58 to byteArrayOf(0x40),
        )

        assertArrayEquals(
            byteArrayOf(0x03, 0x85.toByte(), 0x01, 0x01, 0x33, 0x00,
                0x58, 0x01, 0x40),
            RawNciCodec.configPayload(values, 0x85, 0x33, 0x58),
        )
    }

    @Test
    fun configurationSnapshotRoundTripsWithoutLosingOrderOrEmptyValues() {
        val original = LinkedHashMap<Int, ByteArray>().apply {
            put(0x30, byteArrayOf(0x06))
            put(0x33, byteArrayOf())
            put(0x85, byteArrayOf(0x01))
        }

        val decoded = RawNciCodec.decodeConfiguration(
            RawNciCodec.encodeConfiguration(original),
        )

        assertEquals(listOf(0x30, 0x33, 0x85), decoded.keys.toList())
        original.forEach { (id, value) -> assertArrayEquals(value, decoded[id]) }
    }

    @Test
    fun malformedConfigurationSnapshotsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RawNciCodec.decodeConfiguration("33=ABC")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawNciCodec.decodeConfiguration("33=00;33=01")
        }
    }
}
