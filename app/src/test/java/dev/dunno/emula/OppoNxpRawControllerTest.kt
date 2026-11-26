package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test

class OppoNxpRawControllerTest {
    private fun baseline() = linkedMapOf(
        0x30 to byteArrayOf(4), 0x31 to byteArrayOf(0), 0x32 to byteArrayOf(0x20),
        0x33 to byteArrayOf(8, 0, 0, 0), 0x58 to byteArrayOf(0x74),
        0x59 to byteArrayOf(), 0x85 to byteArrayOf(1))

    private inner class Device : OppoNxpRawController.Platform {
        var identity = "reviewed firmware/apk/jni/chip"
        var identityFails = false
        var saved: OppoNxpRawController.Snapshot? = null
        var current = baseline()
        var saveFails = false
        var clearFails = false
        var noOp = false
        var corruptOther = false
        var failNextWrite = false
        var readFailures = emptySet<Int>()
        var reads = 0
        val events = mutableListOf<String>()
        val commands = mutableListOf<ByteArray>()
        override fun identity(report: RawOperationReport): String {
            if (identityFails) throw IllegalStateException("unreviewed firmware")
            return identity
        }
        override fun load() = saved
        override fun save(snapshot: OppoNxpRawController.Snapshot) {
            events.add("save")
            if (saveFails) throw IllegalStateException("storage unavailable")
            saved = snapshot
        }
        override fun clear() {
            events.add("clear")
            if (clearFails) throw IllegalStateException("marker retained")
            saved = null
        }
        override fun read(report: RawOperationReport): Map<Int, ByteArray> {
            events.add("read")
            reads++
            if (reads in readFailures) throw IllegalStateException("read timeout")
            return current.mapValues { it.value.clone() }
        }
        override fun write(command: ByteArray, lastCommand: Boolean): Int {
            assertFalse("Never flush RF settings to flash", lastCommand)
            assertNotNull("Persist original state before any SET", saved)
            assertArrayEquals(byteArrayOf(0x20, 2), command.copyOfRange(0, 2))
            assertEquals(1, command[3].toInt())
            assertEquals(0x33, command[4].toInt())
            events.add("write")
            commands.add(command.clone())
            if (!noOp) current[0x33] = command.copyOfRange(6, command.size)
            if (corruptOther) current[0x58] = byteArrayOf(0)
            if (failNextWrite) { failNextWrite = false; return -1 }
            return 0
        }
        override fun settle() { events.add("settle") }
    }
    private fun newReport(activate: Boolean = true) = RawOperationReport(
        if (activate) RawOperationReport.Operation.ACTIVATE else RawOperationReport.Operation.DEACTIVATE)
    private fun failure(work: () -> Unit): Exception {
        try { work() } catch (expected: Exception) { return expected }
        throw AssertionError("Expected failure")
    }

    @Test fun ple110KeepsExactAndroid16Scope() {
        val firmware = OppoNxpRawController.PLE_FIRMWARE
        assertTrue(OppoNxpRawController.targets(36, "OPPO", "PLE110", firmware))
        for (sdk in listOf(35, 37)) assertFalse(OppoNxpRawController.targets(sdk, "OPPO", "PLE110", firmware))
        assertFalse(OppoNxpRawController.targets(36, "OnePlus", "PLE110", firmware))
        assertFalse(OppoNxpRawController.targets(36, "OPPO", "PLE110", firmware + "-updated"))
        assertFalse(OppoNxpRawController.targets(36, "OPPO", "CPH2747", firmware))
    }

    @Test fun cph2865AcceptsRegionsAndLaterBuildsWithoutMatchingFingerprint() {
        val firmwares = listOf(OppoNxpRawController.CPH_RU_FIRMWARE,
            OppoNxpRawController.CPH_EEA_FIRMWARE, "another region or OTA build", "unknown")
        for (sdk in listOf(36, 37, 38)) for (firmware in firmwares) {
            assertTrue(OppoNxpRawController.targets(sdk, "OPPO", "CPH2865", firmware))
            assertFalse(OppoNxpRawController.targets(sdk, "OnePlus", "CPH2865", firmware))
            for (model in listOf("CPH2867", "CPH2747", "PLE110", ""))
                assertFalse(OppoNxpRawController.targets(sdk, "OPPO", model, firmware))
        }
        assertFalse(OppoNxpRawController.targets(35, "OPPO", "CPH2865", OppoNxpRawController.CPH_RU_FIRMWARE))
    }

    @Test fun ple110KeepsReviewedBytesAndOtherChipsRemainExcluded() {
        for ((model, apk, jni) in listOf(Triple("PLE110", OppoNxpRawController.PLE_APK, OppoNxpRawController.PLE_JNI),
                Triple("CPH2865", OppoNxpRawController.CPH_APK, OppoNxpRawController.CPH_JNI))) {
            assertTrue(OppoNxpRawController.acceptsStack(model, apk, jni, "SN220U"))
            assertTrue(OppoNxpRawController.acceptsStack(model, apk, jni, "PN560"))
            for (chip in listOf("NQ330", "ST54J", "THN31", "unknowed", ""))
                assertFalse(OppoNxpRawController.acceptsStack(model, apk, jni, chip))
            assertFalse(OppoNxpRawController.acceptsStack(model, null, jni, "SN220U"))
            assertFalse(OppoNxpRawController.acceptsStack(model, apk, null, "SN220U"))
        }
        assertFalse(OppoNxpRawController.acceptsStack("PLE110", OppoNxpRawController.CPH_APK,
            OppoNxpRawController.CPH_JNI, "SN220U"))
        assertFalse(OppoNxpRawController.acceptsStack("CPH2867", OppoNxpRawController.CPH_APK,
            OppoNxpRawController.CPH_JNI, "SN220U"))
    }

    @Test fun cph2865UsesCollectedIdentityInsteadOfKnownHashAllowlist() {
        val apk = "a".repeat(64)
        val jni = "b".repeat(64)
        assertFalse(OppoNxpRawController.reviewed("CPH2865", apk, jni, "SN220U"))
        assertTrue(OppoNxpRawController.acceptsStack("CPH2865", apk, jni, "SN220U"))
        assertTrue(OppoNxpRawController.acceptsStack("CPH2865", OppoNxpRawController.PLE_APK,
            OppoNxpRawController.PLE_JNI, "SN220U"))
        assertFalse(OppoNxpRawController.acceptsStack("PLE110", apk, jni, "SN220U"))
        for (incomplete in listOf("", "a".repeat(63), "z".repeat(64), "unreadable")) {
            assertFalse(OppoNxpRawController.acceptsStack("CPH2865", incomplete, jni, "SN220U"))
            assertFalse(OppoNxpRawController.acceptsStack("CPH2865", apk, incomplete, "SN220U"))
        }
    }

    @Test fun regionalCandidateStillSavesAndRestoresItsOwnSnapshot() {
        val device = Device().apply { identity = "regional build\n" + "a".repeat(64) + "\n" + "b".repeat(64) + "\nSN220U" }
        OppoNxpRawController.change(device, byteArrayOf(1,2,3,4), newReport())
        assertEquals(device.identity, device.saved!!.identity)
        assertEquals(listOf("read", "save", "write", "settle", "read"), device.events)
        OppoNxpRawController.change(device, null, newReport(false))
        assertNull(device.saved)
        assertArrayEquals(baseline()[0x33], device.current[0x33])
    }

    @Test fun previousReleaseSnapshotRestoresWithoutMigration() {
        val device = Device().apply {
            identity = OppoNxpRawController.CPH_RU_FIRMWARE + "\n" + OppoNxpRawController.CPH_APK +
                "\n" + OppoNxpRawController.CPH_JNI + "\nSN220U"
            saved = OppoNxpRawController.Snapshot(identity, baseline())
            current[0x33] = byteArrayOf(1,2,3,4)
        }
        val report = newReport(false)
        OppoNxpRawController.change(device, null, report)
        assertEquals("ORIGINAL_CONFIG_RESTORED", report.result)
        assertNull(device.saved)
        assertArrayEquals(baseline()[0x33], device.current[0x33])
    }

    @Test fun allUidLengthsEncodeOnlyNfcid1AndRestoreAnEmptyOriginal() {
        for (length in listOf(0, 4, 7, 10)) {
            val uid = ByteArray(length) { (it + 1).toByte() }
            val command = OppoNxpRawController.uidCommand(uid)
            assertEquals(length + 6, command.size)
            assertEquals(length + 3, command[2].toInt())
            assertEquals(length, command[5].toInt())
            assertArrayEquals(uid, command.copyOfRange(6, command.size))
        }
        for (length in listOf(1, 3, 5, 8, 11)) failure { OppoNxpRawController.uidCommand(ByteArray(length)) }
        val device = Device().apply { current[0x33] = byteArrayOf() }
        OppoNxpRawController.change(device, byteArrayOf(1,2,3,4), newReport())
        OppoNxpRawController.change(device, null, newReport(false))
        assertArrayEquals(byteArrayOf(0x20,2,3,1,0x33,0), device.commands.last())
        assertNull(device.saved)
    }

    @Test fun activationSwitchingAndRestartKeepFirstBaseline() {
        val device = Device()
        val report = newReport()
        OppoNxpRawController.change(device, byteArrayOf(1,2,3,4), report)
        assertEquals(listOf("read", "save", "write", "settle", "read"), device.events)
        assertEquals("UID_ONLY_VERIFIED", report.result)
        assertEquals("RAW_READBACK_VERIFIED_READER_REQUIRED", NfcDiagnosticAssessment.assessRaw(report).code)
        val original = device.saved
        OppoNxpRawController.change(device, ByteArray(7) { 6 }, newReport())
        assertSame(original, device.saved)
        val restarted = Device().apply { saved = original; current = device.current }
        val restore = newReport(false)
        OppoNxpRawController.change(restarted, null, restore)
        assertArrayEquals(baseline()[0x33], restarted.current[0x33])
        assertEquals("ORIGINAL_CONFIG_RESTORED", restore.result)
        assertNull(restarted.saved)
    }

    @Test fun incompleteOrMalformedSnapshotNeverPersistsOrWrites() {
        for (key in baseline().keys) {
            val device = Device().apply { current.remove(key) }
            val report = newReport()
            failure { OppoNxpRawController.change(device, byteArrayOf(1,2,3,4), report) }
            assertTrue(device.commands.isEmpty()); assertNull(device.saved)
            assertEquals("PREPARATION_FAILED", report.result)
        }
        val device = Device().apply { current[0x32] = byteArrayOf() }
        failure { OppoNxpRawController.change(device, byteArrayOf(1,2,3,4), newReport()) }
        assertTrue(device.commands.isEmpty())
    }

    @Test fun failedSaveOrInitialReadNeverWrites() {
        for (storage in listOf(true, false)) {
            val device = Device().apply { saveFails = storage; if (!storage) readFailures = setOf(1) }
            failure { OppoNxpRawController.change(device, byteArrayOf(1,2,3,4), newReport()) }
            assertTrue(device.commands.isEmpty())
        }
    }

    @Test fun falselySuccessfulSetterIsDetectedAndUnchangedOriginalNeedsNoRollbackSet() {
        val device = Device().apply { noOp = true }
        val report = newReport()
        failure { OppoNxpRawController.change(device, byteArrayOf(1,2,3,4), report) }
        assertEquals("UID_READBACK_MISMATCH", report.result)
        assertEquals("ORIGINAL_CONFIG_ALREADY_PRESENT", report.rollback!!.result)
        assertEquals(1, device.commands.size); assertNull(device.saved)
    }

    @Test fun failedSetterMayHaveAppliedAndRequiresVerifiedRollback() {
        val device = Device().apply { failNextWrite = true }
        val report = newReport()
        failure { OppoNxpRawController.change(device, byteArrayOf(1,2,3,4), report) }
        assertEquals(2, device.commands.size)
        assertArrayEquals(baseline()[0x33], device.current[0x33])
        assertNull(device.saved)
        assertEquals("RAW_VENDOR_RF_REJECTED", NfcDiagnosticAssessment.assessRaw(report).code)
        assertEquals(-1, report.requests.single().vendorStatus)
        assertNull(report.requests.single().controllerStatus)
        assertNull(report.requests.single().frameworkStatus)
        assertTrue(report.formatDetailed().contains("not exposed by vendor API"))
    }

    @Test fun lostReadbackAfterWriteCanStillRestoreFromFreshIndependentRead() {
        val device = Device().apply { readFailures = setOf(2) }
        val report = newReport()
        failure { OppoNxpRawController.change(device, byteArrayOf(1,2,3,4), report) }
        assertNull(device.saved)
        assertEquals("ORIGINAL_CONFIG_RESTORED", report.rollback!!.result)
        assertEquals(2, device.commands.size)
    }

    @Test fun failedRollbackReadRetainsSnapshotAndDoesNotWriteBlindly() {
        val device = Device().apply { readFailures = setOf(2, 3) }
        val report = newReport()
        failure { OppoNxpRawController.change(device, byteArrayOf(1,2,3,4), report) }
        assertNotNull(device.saved); assertNotNull(report.rollbackFailure)
        assertEquals(1, device.commands.size)
        device.readFailures = emptySet()
        OppoNxpRawController.change(device, null, newReport(false))
        assertNull(device.saved)
    }

    @Test fun neverOverwritesUnrelatedParameterChangesDuringRollback() {
        val device = Device().apply { corruptOther = true }
        val report = newReport()
        failure { OppoNxpRawController.change(device, byteArrayOf(1,2,3,4), report) }
        assertNotNull(device.saved); assertNotNull(report.rollbackFailure)
        assertEquals(1, device.commands.size)
        assertArrayEquals(byteArrayOf(0), device.current[0x58])
    }

    @Test fun firmwareOrStackChangesRetainRecoveryWithoutGetOrSet() {
        val device = Device()
        OppoNxpRawController.change(device, byteArrayOf(1,2,3,4), newReport())
        device.identity = "updated firmware"
        val before = device.events.toList()
        failure { OppoNxpRawController.change(device, null, newReport(false)) }
        assertEquals(before, device.events); assertNotNull(device.saved)
    }

    @Test fun noSnapshotDoesNotResetAnythingAtStartup() {
        val device = Device()
        OppoNxpRawController.change(device, null, newReport(false))
        assertTrue(device.events.isEmpty())
    }

    @Test fun unavailableFreshCandidateLeavesGenericRawFallbackAndExistingStateIntact() {
        val device = Device().apply { identityFails = true }
        assertFalse(OppoNxpRawController.available(device))
        assertTrue(device.events.isEmpty())
        assertNull(device.saved)
        device.identityFails = false
        assertTrue(OppoNxpRawController.available(device))
        assertTrue(device.events.isEmpty())
    }

    @Test fun failedClearKeepsRecoveryAndRetrySkipsAlreadyRestoredWrite() {
        val device = Device()
        OppoNxpRawController.change(device, byteArrayOf(1,2,3,4), newReport())
        device.clearFails = true
        failure { OppoNxpRawController.change(device, null, newReport(false)) }
        assertNotNull(device.saved)
        val writes = device.commands.size
        device.clearFails = false
        OppoNxpRawController.change(device, null, newReport(false))
        assertEquals(writes, device.commands.size); assertNull(device.saved)
    }
}
