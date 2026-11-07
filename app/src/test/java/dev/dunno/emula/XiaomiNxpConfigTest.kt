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

    private fun conditionalBaseline() = "NXP_SET_CONFIG_ALWAYS=0x00\n$baseline"
    private val rf = "NXP_RF_CONF_BLK_1={20,02,05,01,A0,00,01,00}\n" +
        "NXP_RF_CONF_BLK_12={20,02,05,01,A0,01,01,00}\n"

    @Test fun conditionalCoreUsesATrailingRfBlockWithExactFirmwareDefaultsForReset() {
        for (length in listOf(4, 7, 10)) {
            val selected = ByteArray(length) { it.toByte() }
            val plan = XiaomiNxpConfig.plan(conditionalBaseline(), rf, selected)
            assertTrue(plan.activation.startsWith("NXP_RF_CONF_BLK_13="))
            assertTrue(plan.reset.startsWith("NXP_RF_CONF_BLK_13="))
            XiaomiNxpConfig.validateReset(plan.reset)
            val restored = XiaomiNxpConfig.parse(plan.reset.replace("NXP_RF_CONF_BLK_13", "NXP_CORE_CONF"))
            XiaomiNxpConfig.parse(baseline).forEach { (id, bytes) -> assertArrayEquals(bytes, restored[id]) }
            val configured = XiaomiNxpConfig.parse(plan.activation.replace("NXP_RF_CONF_BLK_13", "NXP_CORE_CONF"))
            assertArrayEquals(selected, configured[0x33])
            assertArrayEquals(byteArrayOf(0), configured[0x85])
        }
    }

    @Test fun firmwareThatAlwaysAppliesCoreKeepsTheExistingCarrier() {
        val plan = XiaomiNxpConfig.plan("NXP_SET_CONFIG_ALWAYS=0x01\n$baseline", null, uid)
        assertEquals(XiaomiNxpConfig.configuration(baseline, uid), plan.activation)
        assertNull(plan.reset)
    }

    @Test fun slotSelectionAccountsForBothFirmwareFilesAndIgnoresComments() {
        val main = conditionalBaseline() + "\nNXP_RF_CONF_BLK_20={20,02,00}\n"
        val plan = XiaomiNxpConfig.plan(main, rf + "# NXP_RF_CONF_BLK_30={20,02,00}\n", uid)
        assertTrue(plan.activation.startsWith("NXP_RF_CONF_BLK_21="))
        // An RF-file override of a main-file block is retained, not mistaken for a free slot.
        assertTrue(XiaomiNxpConfig.plan(main, rf + "NXP_RF_CONF_BLK_20={20,02,00}\n", uid)
            .activation.startsWith("NXP_RF_CONF_BLK_21="))
    }

    @Test fun missingMalformedOrExhaustedRfLayoutIsRefused() {
        for (bad in listOf(null, "", "# empty", rf + rf, "NXP_RF_CONF_BLK_foo={20,02,00}",
                "NXP_RF_CONF_BLK_31={20,02,00}", "NXP_RF_CONF_BLK_30={20,02,00}")) {
            assertThrows(IllegalArgumentException::class.java) {
                XiaomiNxpConfig.plan(conditionalBaseline(), bad, uid)
            }
        }
    }

    @Test fun applicationPolicyMustBePresentAndUnambiguous() {
        for (value in listOf("0", "0x00", "0X0")) {
            assertFalse(XiaomiNxpConfig.setConfigAlways("NXP_SET_CONFIG_ALWAYS=$value # comment\n$baseline"))
        }
        for (value in listOf("1", "0x01", "0X1")) {
            assertTrue(XiaomiNxpConfig.setConfigAlways("NXP_SET_CONFIG_ALWAYS=$value\n$baseline"))
        }
        for (bad in listOf(baseline, "NXP_SET_CONFIG_ALWAYS=2\n$baseline", "NXP_SET_CONFIG_ALWAYS={}\n$baseline",
                conditionalBaseline() + "\nNXP_SET_CONFIG_ALWAYS=1\n")) {
            assertThrows(IllegalArgumentException::class.java) { XiaomiNxpConfig.setConfigAlways(bad) }
        }
    }

    @Test fun savedResetRejectsExtraKeysProprietaryCommandsAndMalformedFraming() {
        val reset = XiaomiNxpConfig.plan(conditionalBaseline(), rf, uid).reset
        for (bad in listOf(reset + "NXP_OTHER=1", reset + reset,
                reset.replace("NXP_RF_CONF_BLK_13", "NXP_RF_CONF_BLK_31"),
                reset.replace("20,02,33,11", "2F,02,33,11"),
                reset.replace("28,01,00", "A0,01,00"))) {
            assertThrows(IllegalArgumentException::class.java) { XiaomiNxpConfig.validateReset(bad) }
        }
    }

    @Test fun transitChangesApplyAndRestoreWithAControllerModelThatSkipsUnchangedCore() {
        // Model the HAL distinction found in diting: only RF blocks replay for a transit CRC change.
        var controller = XiaomiNxpConfig.parse(baseline)
        var lastTransit: String? = null
        fun restartWith(transit: String?) {
            if (transit != lastTransit && transit?.startsWith("NXP_RF_CONF_BLK_13=") == true) {
                controller = XiaomiNxpConfig.parse(transit.replace("NXP_RF_CONF_BLK_13", "NXP_CORE_CONF"))
            }
            lastTransit = transit
        }
        restartWith(XiaomiNxpConfig.configuration(baseline, uid))
        assertEquals(0, controller[0x33]!!.size) // Old carrier was accepted but did not change the UID.
        val plan = XiaomiNxpConfig.plan(conditionalBaseline(), rf, uid)
        restartWith(plan.activation)
        assertArrayEquals(uid, controller[0x33])
        val nextUid = byteArrayOf(0x2d, 0xd3.toByte(), 0x45, 0x4d)
        restartWith(XiaomiNxpConfig.plan(conditionalBaseline(), rf, nextUid).activation)
        assertArrayEquals(nextUid, controller[0x33])
        restartWith(plan.reset)
        restartWith(null)
        XiaomiNxpConfig.parse(baseline).forEach { (id, bytes) -> assertArrayEquals(bytes, controller[id]) }
    }
}
