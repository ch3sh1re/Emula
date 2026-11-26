package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test

class RawRecoveryTest {
    private val original = linkedMapOf(
        0x85 to byteArrayOf(1), 0x30 to byteArrayOf(4), 0x31 to byteArrayOf(0),
        0x32 to byteArrayOf(0x20), 0x33 to byteArrayOf(8, 0, 0, 0), 0x58 to byteArrayOf(0x74),
    )
    private val changed = original + (0x33 to byteArrayOf(1, 2, 3, 4))
    private val events = mutableListOf<String>()
    private val report = RawOperationReport(RawOperationReport.Operation.DEACTIVATE)

    private inner class Polling : LegacyNfcController.PollingControl("TEST") {
        var pauseFailure: Exception? = null
        var settleFailure: Exception? = null
        var resumeFailure: Exception? = null
        override fun pause() {
            events.add("pause")
            pauseFailure?.let { throw it }
        }
        override fun awaitPauseApplied() {
            events.add("settle")
            settleFailure?.let { throw it }
        }
        override fun resume() {
            events.add("resume")
            resumeFailure?.let { throw it }
        }
    }

    private inner class Device : LegacyNfcController.ConfigurationIo {
        var before: Map<Int, ByteArray> = changed
        var after: Map<Int, ByteArray> = original
        var readFailure: Exception? = null
        var setFailure: Exception? = null
        var written: ByteArray? = null
        override fun readConfiguration(ids: IntArray, report: RawOperationReport,
                operation: RawOperationReport.Operation, candidate: RawOperationReport.Candidate,
                stage: String): Map<Int, ByteArray> {
            assertArrayEquals(original.keys.toIntArray(), ids)
            events.add(stage)
            if (stage == "CHECK_ORIGINAL_CONFIG") {
                readFailure?.let { throw it }
                return before
            }
            assertEquals("VERIFY_ORIGINAL_CONFIG", stage)
            return after
        }
        override fun setConfig(payload: ByteArray, report: RawOperationReport,
                operation: RawOperationReport.Operation, candidate: RawOperationReport.Candidate,
                stage: String) {
            assertEquals(RawOperationReport.Operation.DEACTIVATE, operation)
            assertEquals(RawOperationReport.Candidate.LEGACY_PROFILE, candidate)
            assertEquals("RESTORE_ORIGINAL_CONFIG", stage)
            events.add(stage)
            written = payload.copyOf()
            setFailure?.let { throw it }
        }
    }

    private fun restore(device: Device, polling: Polling = Polling()) {
        LegacyNfcController.restoreExperimentalConfiguration(device, polling, report,
            report.operation, original) { events.add("settle_after_resume") }
    }

    private fun failure(action: () -> Unit): Exception {
        try { action() } catch (e: Exception) { return e }
        throw AssertionError("Expected failure")
    }

    @Test fun matchingSnapshotSkipsBothPollingAndRestoreSet() {
        val device = Device().apply { before = original.mapValues { it.value.copyOf() } }
        restore(device)
        assertEquals(listOf("CHECK_ORIGINAL_CONFIG"), events)
        assertNull(device.written)
        assertEquals("ORIGINAL_CONFIG_ALREADY_PRESENT", report.result)
    }

    @Test fun differingSnapshotRestoresEverySavedByteAndVerifiesAfterResume() {
        val device = Device()
        restore(device)
        assertEquals(listOf("CHECK_ORIGINAL_CONFIG", "pause", "settle", "RESTORE_ORIGINAL_CONFIG",
            "resume", "settle_after_resume", "VERIFY_ORIGINAL_CONFIG"), events)
        assertArrayEquals(byteArrayOf(6, 0x85.toByte(), 1, 1, 0x30, 1, 4, 0x31, 1, 0,
            0x32, 1, 0x20, 0x33, 4, 8, 0, 0, 0, 0x58, 1, 0x74), device.written)
        assertEquals("ORIGINAL_CONFIG_RESTORED", report.result)
    }

    @Test fun partialPreflightCannotSkipRestoration() {
        val device = Device().apply { before = original - 0x58 }
        restore(device)
        assertNotNull(device.written)
        assertEquals("ORIGINAL_CONFIG_RESTORED", report.result)
    }

    @Test fun failedPreflightStillRestoresButCannotMistakeALateGetForVerification() {
        val unavailable = IllegalStateException("GET timed out")
        val device = Device().apply { readFailure = unavailable }
        val error = failure { restore(device) }
        assertSame(unavailable, error.cause)
        assertNotNull(device.written)
        assertEquals(listOf("CHECK_ORIGINAL_CONFIG", "pause", "settle", "RESTORE_ORIGINAL_CONFIG",
            "resume", "settle_after_resume"), events)
        assertEquals("ORIGINAL_CONFIG_RESTORE_UNVERIFIED", report.result)
        assertTrue(error.message!!.contains("retry recovery"))
    }

    @Test fun failedRestoreRetainsBothSetAndOptionalReadErrors() {
        val getError = IllegalStateException("GET timed out")
        val setError = IllegalStateException("SET rejected")
        val device = Device().apply { readFailure = getError; setFailure = setError }
        assertSame(setError, failure { restore(device) })
        assertSame(getError, setError.suppressed.single())
        assertEquals("resume", events.last())
        assertTrue(report.resumeSucceeded)
    }

    @Test fun interruptedPreflightDoesNotWriteOrClearCancellation() {
        val interrupted = InterruptedException("cancelled")
        val device = Device().apply { readFailure = interrupted }
        try {
            assertSame(interrupted, failure { restore(device) })
            assertTrue(Thread.currentThread().isInterrupted)
            assertNull(device.written)
            assertEquals(listOf("CHECK_ORIGINAL_CONFIG"), events)
        } finally { Thread.interrupted() }
    }

    @Test fun finalReadbackMustMatchEverySavedParameter() {
        for (id in original.keys) {
            val device = Device().apply { after = original - id }
            failure { restore(device) }
            assertEquals("ORIGINAL_CONFIG_RESTORE_MISMATCH", report.result)
        }
    }

    @Test fun interruptedDiscoverySettlingAlwaysResumesBeforePropagating() {
        val interrupted = InterruptedException("cancelled after discovery was disabled")
        val polling = Polling().apply { settleFailure = interrupted }
        assertSame(interrupted, failure {
            LegacyNfcController.executeWithPollingPaused(polling, report) { events.add("write") }
        })
        assertEquals(listOf("pause", "settle", "resume"), events)
        assertTrue(report.pauseSucceeded)
        assertTrue(report.resumeSucceeded)
    }

    @Test fun failedPausePreventsWritingWithoutReportingASuccessfulPause() {
        val pauseError = IllegalStateException("foreground unavailable")
        val polling = Polling().apply { pauseFailure = pauseError }
        assertSame(pauseError, failure {
            LegacyNfcController.executeWithPollingPaused(polling, report) { events.add("write") }
        })
        assertEquals(listOf("pause"), events)
        assertFalse(report.pauseSucceeded)
        assertNull(report.resumeSucceeded)
    }

    @Test fun resumeFailureDoesNotReplaceThePrimaryWriteFailure() {
        val primary = IllegalStateException("SET rejected")
        val resume = IllegalStateException("resume failed")
        val polling = Polling().apply { resumeFailure = resume }
        assertSame(primary, failure {
            LegacyNfcController.executeWithPollingPaused(polling, report) { throw primary }
        })
        assertSame(resume, primary.suppressed.single())
        assertEquals(listOf("pause", "settle", "resume"), events)
        assertFalse(report.resumeSucceeded)
        assertEquals("resume failed", report.resumeFailure)
    }

    @Test fun scopedUidRestoreWritesOnlySavedNfcid1AndChecksAllSevenParameters() {
        for (rats in listOf(0, 0x41, 0x74)) for (length in listOf(0, 4, 7, 10)) {
            val uid = ByteArray(length) { (it + 1).toByte() }
            val baseline = original + (0x33 to uid) + (0x58 to byteArrayOf(rats.toByte())) + (0x59 to byteArrayOf())
            var sets = 0
            val io = object : LegacyNfcController.ConfigurationIo {
                override fun setConfig(payload: ByteArray, report: RawOperationReport,
                        operation: RawOperationReport.Operation, candidate: RawOperationReport.Candidate, stage: String) {
                    sets++
                    assertEquals(RawOperationReport.Candidate.UID_ONLY, candidate)
                    assertArrayEquals(byteArrayOf(1, 0x33, length.toByte()) + uid, payload)
                }
                override fun readConfiguration(ids: IntArray, report: RawOperationReport,
                        operation: RawOperationReport.Operation, candidate: RawOperationReport.Candidate,
                        stage: String): Map<Int, ByteArray> {
                    assertEquals(1, sets)
                    assertEquals("resume", events.last())
                    assertArrayEquals(intArrayOf(0x30, 0x31, 0x32, 0x33, 0x58, 0x59, 0x85), ids)
                    return baseline
                }
            }
            LegacyNfcController.restoreUidSnapshot(io, Polling(), report, baseline, {}, false)
            assertEquals(1, sets)
            assertEquals("UID_SNAPSHOT_RESTORED", report.result)
        }
    }

    @Test fun onePlus12WritesOnlyUidAndChecksItBeforeAndAfterResume() {
        val uid = byteArrayOf(1, 2, 3, 4)
        for (outcome in listOf("retained", "overwritten", "rejected")) {
            events.clear()
            val activation = RawOperationReport(RawOperationReport.Operation.ACTIVATE)
            val io = object : LegacyNfcController.ConfigurationIo {
                override fun setConfig(payload: ByteArray, report: RawOperationReport,
                        operation: RawOperationReport.Operation, candidate: RawOperationReport.Candidate, stage: String) {
                    assertEquals("settle", events.last())
                    assertEquals("SET_WHILE_PAUSED", stage)
                    assertEquals(RawOperationReport.Candidate.UID_ONLY, candidate)
                    assertArrayEquals(byteArrayOf(1, 0x33, 4) + uid, payload)
                    events.add("SET")
                    if (outcome == "rejected") error("INVALID_PARAM")
                }
                override fun readConfiguration(ids: IntArray, report: RawOperationReport,
                        operation: RawOperationReport.Operation, candidate: RawOperationReport.Candidate,
                        stage: String): Map<Int, ByteArray> {
                    assertArrayEquals(intArrayOf(0x33), ids)
                    events.add(stage)
                    return if (stage == "UID_BEFORE" || (stage == "UID_AFTER_RESUME" && outcome == "overwritten"))
                        original else changed
                }
            }
            val activate = { LegacyNfcController.applyUidOnly(io, Polling(), uid, activation, false) }
            if (outcome == "retained") {
                activate()
                assertEquals("UID_ONLY_VERIFIED", activation.result)
            } else {
                failure(activate)
                if (outcome == "overwritten") assertEquals("UID_OVERWRITTEN_AFTER_RESUME", activation.result)
            }
            val expected = mutableListOf("UID_BEFORE", "pause", "settle", "SET")
            if (outcome != "rejected") expected.add("UID_AFTER_SET")
            expected.add("resume")
            if (outcome != "rejected") expected.add("UID_AFTER_RESUME")
            assertEquals(expected, events)
        }
    }

    @Test fun onePlus12RollbackSkipsMatchingUidAndVerifiesAllSevenOnRestore() {
        val baseline = original + (0x59 to byteArrayOf())
        for (field in listOf(0, 0x33, 0x59)) {
            events.clear()
            val io = object : LegacyNfcController.ConfigurationIo {
                override fun readConfiguration(ids: IntArray, report: RawOperationReport,
                        operation: RawOperationReport.Operation, candidate: RawOperationReport.Candidate,
                        stage: String): Map<Int, ByteArray> {
                    assertEquals(baseline.keys, ids.toSet())
                    assertEquals(RawOperationReport.Candidate.UID_ONLY, candidate)
                    events.add(stage)
                    return if (stage == "CHECK_ORIGINAL_CONFIG" && field != 0)
                        baseline + (field to byteArrayOf(1, 2, 3, 4)) else baseline
                }
                override fun setConfig(payload: ByteArray, report: RawOperationReport,
                        operation: RawOperationReport.Operation, candidate: RawOperationReport.Candidate, stage: String) {
                    assertEquals(0x33, field)
                    assertArrayEquals(byteArrayOf(1, 0x33, 4, 8, 0, 0, 0), payload)
                    events.add(stage)
                }
            }
            val restore = { LegacyNfcController.restoreUidSnapshot(io, Polling(), report, baseline, {}, true) }
            if (field == 0x59) failure(restore) else restore()
            when (field) {
                0 -> assertEquals("ORIGINAL_CONFIG_ALREADY_PRESENT", report.result)
                0x59 -> assertEquals("UID_SNAPSHOT_RESTORE_MISMATCH", report.result)
                0x33 -> assertEquals("UID_SNAPSHOT_RESTORED", report.result)
            }
            if (field != 0x33) assertEquals(listOf("CHECK_ORIGINAL_CONFIG"), events)
            else assertEquals(listOf("CHECK_ORIGINAL_CONFIG", "pause", "settle", "RESTORE_SAVED_NFCID1",
                "resume", "VERIFY_SAVED_NFC_CONFIG"), events)
        }
    }

    @Test fun scopedUidRestoreCannotReportSuccessIfAnyOtherParameterDiffers() {
        val baseline = original + (0x59 to byteArrayOf())
        for (missing in baseline.keys) {
            val io = object : LegacyNfcController.ConfigurationIo {
                override fun setConfig(payload: ByteArray, report: RawOperationReport,
                        operation: RawOperationReport.Operation, candidate: RawOperationReport.Candidate, stage: String) {}
                override fun readConfiguration(ids: IntArray, report: RawOperationReport,
                        operation: RawOperationReport.Operation, candidate: RawOperationReport.Candidate,
                        stage: String): Map<Int, ByteArray> = baseline - missing
            }
            failure { LegacyNfcController.restoreUidSnapshot(io, Polling(), report, baseline, {}, false) }
            assertEquals("UID_SNAPSHOT_RESTORE_MISMATCH", report.result)
            assertEquals("resume", events.last())
        }
    }
}
