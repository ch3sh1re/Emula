package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class XiaomiNxpConfigTest {
    private val baseline = XiaomiNxpConfig.CORE_CONF_206
    private val uid = byteArrayOf(4, 0x12, 0x34, 0x56)

    @Test fun changedFirmwareRfValuesSurviveUidActivation() {
        val newer = baseline.replace("30,01,04", "30,01,08")
            .replace("31,01,00", "31,01,03").replace("32,01,60", "32,01,20")
        val before = XiaomiNxpConfig.parse(newer)
        val after = XiaomiNxpConfig.parse(XiaomiNxpConfig.configuration(newer, uid))
        before.forEach { (id, bytes) ->
            when (id) {
                0x85 -> assertArrayEquals(byteArrayOf(0), after[id])
                0x33 -> assertArrayEquals(uid, after[id])
                else -> assertArrayEquals("Firmware parameter $id", bytes, after[id])
            }
        }
        assertArrayEquals(byteArrayOf(8), after[0x30])
        assertArrayEquals(byteArrayOf(3), after[0x31])
        assertArrayEquals(byteArrayOf(0x20), after[0x32])
    }

    @Test fun firmwareCommentsWhitespaceAndUnrelatedKeysAreAllowed() {
        val text = "# NXP_CORE_CONF={wrong}\nNXP_CORE_CONF_EXTN={20,02,00}\n" +
            baseline.replace("={", " = {\n  ").replace(",", ", \n") + " # end\nOTHER=1\n"
        val original = XiaomiNxpConfig.parse(baseline)
        val parsed = XiaomiNxpConfig.parse(text)
        original.forEach { (id, bytes) -> assertArrayEquals(bytes, parsed[id]) }
    }

    @Test fun duplicateCoreConfigurationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { XiaomiNxpConfig.parse("$baseline\n$baseline") }
    }

    @Test fun malformedLengthCountAndParameterValuesAreRejected() {
        val bad = listOf(
            baseline.replace("20,02,33,11", "20,02,32,11"),
            baseline.replace("20,02,33,11", "20,02,33,10"),
            baseline.replace("20,02,33,11", "20,02,33,12"),
            baseline.replace("28,01,00", "28,FF,00"),
            baseline.replace("28,01,00", "28,01,GG"),
            baseline.replace("85,01,01", "85,01"),
            baseline.replace("20,02", "2F,02"),
            baseline.replace("33,00", "33,01")
        )
        bad.forEach { text ->
            assertThrows(text, IllegalArgumentException::class.java) { XiaomiNxpConfig.configuration(text, uid) }
        }
    }

    @Test fun proprietaryAndDuplicateParametersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            XiaomiNxpConfig.parse(baseline.replace("28,01,00", "A0,01,00"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            XiaomiNxpConfig.parse(baseline.replace("28,01,00", "21,01,00"))
        }
    }

    @Test fun missingUidOrConfigControlIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            XiaomiNxpConfig.parse(baseline.replace("33,00", "34,00"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            XiaomiNxpConfig.parse(baseline.replace("85,01,01", "86,01,01"))
        }
    }

    @Test fun oversizedConfigIsRefusedWithoutReadingToTheEnd() {
        val bytes = ByteArray(XiaomiNxpConfig.MAX_CONFIG_BYTES * 2) { 32 }
        val input = ByteArrayInputStream(bytes)
        assertThrows(IOException::class.java) { XiaomiNxpConfig.read(input) }
        assertEquals(XiaomiNxpConfig.MAX_CONFIG_BYTES - 1, input.available())
        assertThrows(IllegalArgumentException::class.java) { XiaomiNxpConfig.parse(" ".repeat(bytes.size)) }
    }

    @Test fun interruptedReadDoesNotConsumeInput() {
        val input = ByteArrayInputStream(baseline.toByteArray())
        try {
            Thread.currentThread().interrupt()
            assertThrows(IOException::class.java) { XiaomiNxpConfig.read(input) }
            assertEquals(baseline.length, input.available())
        } finally {
            Thread.interrupted()
        }
    }
}
