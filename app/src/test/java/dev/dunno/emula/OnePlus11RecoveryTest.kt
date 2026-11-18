package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test

class OnePlus11RecoveryTest {
    private val firmware = OnePlus11Recovery.FIRMWARE
    private val values = mapOf(0x30 to byteArrayOf(4), 0x31 to byteArrayOf(0),
        0x32 to byteArrayOf(32), 0x33 to byteArrayOf(8, 0, 0, 0), 0x58 to byteArrayOf(0x74),
        0x59 to byteArrayOf(), 0x85 to byteArrayOf(1))
    private val unexpected = OnePlus11Recovery.SnapshotReader { error("Unexpected snapshot read") }

    @Test fun captureBeforeFirstWriteAndKeepBaselineAcrossSwitchOrRestart() {
        assertSame(values, OnePlus11Recovery.baseline(firmware, null, false, true, unexpected) { values })
        for (activating in listOf(true, false)) {
            assertSame(values, OnePlus11Recovery.baseline(firmware, firmware, true,
                activating, { values }, unexpected))
        }
        assertThrows(IllegalStateException::class.java) {
            OnePlus11Recovery.baseline(firmware, null, false, true, unexpected) { values - 0x33 }
        }
        assertThrows(IllegalStateException::class.java) {
            OnePlus11Recovery.baseline(firmware, firmware, true, false,
                { values + (0x58 to byteArrayOf()) }, unexpected)
        }
    }

    @Test fun neverReplaceMissingOriginalsWithCurrentValuesOrReuseThemAfterOta() {
        assertNull(OnePlus11Recovery.baseline(firmware, null, true, false, unexpected, unexpected))
        assertNull(OnePlus11Recovery.baseline("another build", null, false, true, unexpected, unexpected))
        assertThrows(IllegalStateException::class.java) {
            OnePlus11Recovery.baseline(firmware, null, true, true, unexpected, unexpected)
        }
        assertThrows(IllegalStateException::class.java) {
            OnePlus11Recovery.baseline("updated firmware", firmware, true, false, unexpected, unexpected)
        }
    }

    @Test fun onlyFlagOnlyCataloguedRecordsOnTheReportedBuildUseRestartRecovery() {
        assertTrue(OnePlus11Recovery.oldRecord(firmware, true, false, false))
        assertFalse(OnePlus11Recovery.oldRecord("different", true, false, false))
        assertFalse(OnePlus11Recovery.oldRecord(firmware, false, false, false))
        assertFalse(OnePlus11Recovery.oldRecord(firmware, true, true, false))
        assertFalse(OnePlus11Recovery.oldRecord(firmware, true, false, true))
    }

    @Test fun processRestartOrUnknownBootCannotRetireTheRecord() {
        for ((first, current) in listOf(12 to 12, 12 to 11, -1 to 12, 12 to -1)) {
            val report = RawOperationReport(RawOperationReport.Operation.STARTUP_PENDING_RESTORE)
            assertThrows(IllegalStateException::class.java) {
                OnePlus11Recovery.checkAfterRestart(first, current, report, unexpected)
            }
            assertEquals(if (first == current) "RESTART_REQUIRED" else "RESTART_UNVERIFIED", report.result)
        }
    }

    @Test fun onlyAnExactCompleteReadAfterBootCanRetireTheRecord() {
        val report = RawOperationReport(RawOperationReport.Operation.STARTUP_PENDING_RESTORE)
        var reads = 0
        OnePlus11Recovery.checkAfterRestart(12, 13, report) { reads++; values }
        assertEquals(1, reads)
        for ((id, bytes) in values) {
            for (incomplete in listOf(values - id, values + (id to bytes.plus(1.toByte())))) {
                assertThrows(IllegalStateException::class.java) {
                    OnePlus11Recovery.checkAfterRestart(12, 13, report) { incomplete }
                }
                assertEquals("POST_BOOT_PROFILE_MISMATCH", report.result)
            }
        }
        val timeout = IllegalStateException("GET timeout")
        assertSame(timeout, assertThrows(IllegalStateException::class.java) {
            OnePlus11Recovery.checkAfterRestart(12, 13, report) { throw timeout }
        })
    }

    @Test fun onlyScopedRecoverySkipsTheWriteWhenTheSavedValuesAreAlreadyPresent() {
        for (checkFirst in listOf(true, false)) {
            val stages = mutableListOf<String>()
            val report = RawOperationReport(RawOperationReport.Operation.DEACTIVATE)
            val io = object : LegacyNfcController.ConfigurationIo {
                override fun readConfiguration(ids: IntArray, r: RawOperationReport,
                        op: RawOperationReport.Operation, candidate: RawOperationReport.Candidate?,
                        stage: String): Map<Int, ByteArray> {
                    stages.add(stage)
                    return values
                }

                override fun setConfig(payload: ByteArray, r: RawOperationReport,
                        op: RawOperationReport.Operation, candidate: RawOperationReport.Candidate?,
                        stage: String) {
                    stages.add(stage)
                }
            }
            val polling = object : LegacyNfcController.PollingControl("TEST") {
                override fun pause() { stages.add("pause") }
                override fun resume() { stages.add("resume") }
            }
            LegacyNfcController.restoreSavedConfiguration(io, polling, report, report.operation,
                values, checkFirst) { stages.add("settle") }
            if (checkFirst) {
                assertEquals(listOf("CHECK_ORIGINAL_CONFIG"), stages)
                assertEquals("ORIGINAL_CONFIG_ALREADY_PRESENT", report.result)
            } else {
                assertEquals(listOf("pause", "RESTORE_ORIGINAL_CONFIG", "resume", "settle",
                    "VERIFY_ORIGINAL_CONFIG"), stages)
                assertEquals("ORIGINAL_CONFIG_RESTORED", report.result)
            }
        }
    }
}
