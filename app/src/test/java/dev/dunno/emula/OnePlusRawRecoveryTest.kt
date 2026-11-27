package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test

class OnePlusRawRecoveryTest {
    private val firmware = OnePlusRawRecovery.FIRMWARE
    private val saved = OnePlusVendorNfcController.Snapshot("0", null, null, null, firmware)
    private fun baseline() = linkedMapOf(
        0x30 to byteArrayOf(4), 0x31 to byteArrayOf(0), 0x32 to byteArrayOf(0x20),
        0x33 to byteArrayOf(8, 0, 0, 0), 0x58 to byteArrayOf(0x41),
        0x59 to byteArrayOf(), 0x85 to byteArrayOf(1),
    )
    private inner class Device : OnePlusRawRecovery.Platform {
        var permitted = true
        var vendor: OnePlusVendorNfcController.Snapshot? = saved
        var globals = saved
        var configuration: MutableMap<Int, ByteArray> = baseline()
        var original: Map<Int, ByteArray>? = null
        var archive: OnePlusVendorNfcController.Snapshot? = null
        var readFailure: Exception? = null
        var archiveFails = false
        var saveFails = false
        var activateFails = false
        var restoreFails = false
        var clearFails = false
        var changeSettingsDuringRead = false
        var reviewedAndroidStack = false
        val events = mutableListOf<String>()
        var rollbackError: Throwable? = null
        override fun allowed() = permitted
        override fun acceptsAndroidDisabledIdle() = reviewedAndroidStack && archive != null && vendor == null
        override fun vendorSnapshot() = vendor
        override fun settings() = globals
        override fun read(): Map<Int, ByteArray> {
            events.add("read")
            readFailure?.let { throw it }
            if (changeSettingsDuringRead) globals = OnePlusVendorNfcController.Snapshot("1", null, null, null, firmware)
            return configuration.mapValues { it.value.copyOf() }
        }
        override fun archiveAndRelease(original: OnePlusVendorNfcController.Snapshot, accepted: Map<Int, ByteArray>) {
            events.add("archive")
            if (archiveFails) throw IllegalStateException("archive failed")
            archive = original
            assertTrue(OnePlusRawRecovery.reportedIdleConfiguration(accepted))
            vendor = null
        }
        override fun pending() = original
        override fun save(original: Map<Int, ByteArray>) {
            events.add("save")
            if (saveFails) throw IllegalStateException("save failed")
            this.original = original.mapValues { it.value.copyOf() }
        }
        override fun clear() {
            events.add("clear")
            if (clearFails) throw IllegalStateException("clear failed")
            original = null
        }
        override fun write(uid: ByteArray, enabled: Boolean, operation: RawOperationReport.Operation,
                original: Map<Int, ByteArray>) {
            events.add(if (enabled) "activate" else "restore")
            assertNotNull("Snapshot must be retained before every SET", this.original)
            if (enabled && activateFails) throw IllegalStateException("activation failed")
            if (!enabled && restoreFails) throw IllegalStateException("restoration failed")
            configuration[0x33] = uid.copyOf()
        }
        override fun rollbackEvidence(primary: Throwable, rollback: Throwable?) { rollbackError = rollback }
    }

    @Test fun selectionRequiresExplicitRawAndExactReportSixScope() {
        assertTrue(OnePlusRawRecovery.isTarget(37, "OnePlus", "CPH2747", firmware))
        for (sdk in listOf(35, 36, 38)) assertFalse(OnePlusRawRecovery.isTarget(sdk, "OnePlus", "CPH2747", firmware))
        assertFalse(OnePlusRawRecovery.isTarget(37, "OPPO", "CPH2747", firmware))
        assertFalse(OnePlusRawRecovery.isTarget(37, "OnePlus", "CPH2707", firmware))
        assertFalse(OnePlusRawRecovery.isTarget(37, "OnePlus", "CPH2747", firmware + ".updated"))
        assertTrue(OnePlusRawRecovery.selectsTrial(true, true, true, saved, false))
        assertFalse(OnePlusRawRecovery.selectsTrial(false, true, true, saved, true))
        assertFalse(OnePlusRawRecovery.selectsTrial(true, false, true, saved, true))
        assertFalse(OnePlusRawRecovery.selectsTrial(true, true, false, saved, true))
        assertFalse(OnePlusRawRecovery.selectsTrial(true, true, true, null, false))
        assertTrue(OnePlusRawRecovery.selectsTrial(true, true, true, null, true))
        val enabled = OnePlusVendorNfcController.Snapshot("1", "0400", "20", "08000000", firmware)
        assertFalse(OnePlusRawRecovery.selectsTrial(true, true, true, enabled, true))
    }

    @Test fun firstActivationArchivesBeforeWritesAndPersistsTheCompleteBaseline() {
        val device = Device()
        OnePlusRawRecovery.change(device, byteArrayOf(1, 2, 3, 4))
        assertEquals(listOf("read", "archive", "read", "save", "activate"), device.events)
        assertSame(saved, device.archive)
        assertNull(device.vendor)
        assertEquals(7, device.original!!.size)
        assertArrayEquals(byteArrayOf(8, 0, 0, 0), device.original!![0x33])
        OnePlusRawRecovery.change(device, null)
        assertNull(device.original)
        assertSame(saved, device.archive)
        assertArrayEquals(byteArrayOf(8, 0, 0, 0), device.configuration[0x33])
    }

    @Test fun rawAfterAndroidDisableKeepsTheObservedZeroRatsBaseline() {
        val device = Device().apply {
            vendor = null
            archive = saved
            reviewedAndroidStack = true
            configuration[0x58] = byteArrayOf(0)
        }
        OnePlusRawRecovery.change(device, byteArrayOf(1, 2, 3, 4))
        assertEquals(listOf("read", "save", "activate"), device.events)
        assertArrayEquals(byteArrayOf(0), device.original!![0x58])
        OnePlusRawRecovery.change(device, null)
        assertArrayEquals(byteArrayOf(8, 0, 0, 0), device.configuration[0x33])
        assertArrayEquals(byteArrayOf(0), device.configuration[0x58])
        assertSame(saved, device.archive)
        assertNull(device.original)
    }

    @Test fun androidDisabledIdleRequiresBothReviewedStackAndRetainedArchive() {
        for (reviewed in listOf(false, true)) for (archived in listOf(false, true)) {
            if (reviewed && archived) continue
            val device = Device().apply {
                vendor = null
                archive = if (archived) saved else null
                reviewedAndroidStack = reviewed
                configuration[0x58] = byteArrayOf(0)
            }
            assertThrows(IllegalStateException::class.java) {
                OnePlusRawRecovery.change(device, byteArrayOf(1, 2, 3, 4))
            }
            assertEquals(listOf("read"), device.events)
            assertNull(device.original)
            val report = LegacyNfcController.getLastOperationReport()
            assertEquals("CHECK_IDLE_BASELINE", report.stage)
            assertEquals("PREPARATION_FAILED", report.result)
            assertEquals("RAW_PREPARATION_FAILED", NfcDiagnosticAssessment.assessRaw(report).code)
            assertTrue(report.formatDetailed().contains("58=00"))
            assertTrue(report.formatDetailed().contains("no raw SET was sent"))
            assertTrue(report.requests.isEmpty())
        }
    }

    @Test fun androidDisabledIdleStillRequiresEveryExactParameterAndRejectsOtherRatsValues() {
        for (id in baseline().keys) for (missing in listOf(false, true)) {
            val device = Device().apply {
                vendor = null
                archive = saved
                reviewedAndroidStack = true
                configuration[0x58] = byteArrayOf(0)
                if (missing) configuration.remove(id) else configuration[id] = byteArrayOf(0x7F)
            }
            assertThrows(IllegalStateException::class.java) {
                OnePlusRawRecovery.change(device, byteArrayOf(1, 2, 3, 4))
            }
            assertEquals(listOf("read"), device.events)
            assertNull(device.original)
        }
        for (rats in 1..255) {
            val config = baseline().apply { this[0x58] = byteArrayOf(rats.toByte()) }
            assertEquals(rats == 0x41, OnePlusRawRecovery.rawIdleConfiguration(config, true))
        }
        assertFalse(OnePlusRawRecovery.rawIdleConfiguration(baseline().apply {
            this[0x58] = byteArrayOf(0, 0)
        }, true))
    }

    @Test fun newIdleStateCannotBroadenTheOldVendorArchiveMigration() {
        for (android in listOf(false, true)) {
            val device = Device().apply {
                reviewedAndroidStack = true
                configuration[0x58] = byteArrayOf(0)
            }
            assertThrows(IllegalStateException::class.java) { OnePlusRawRecovery.prepare(device, android) }
            assertSame(saved, device.vendor)
            assertNull(device.archive)
            assertEquals(listOf("read"), device.events)
        }
    }

    @Test fun androidIdleBaselineSurvivesCardSwitchesAndFailedRollbackUntilRestart() {
        val device = Device().apply {
            vendor = null; archive = saved; reviewedAndroidStack = true
            configuration[0x58] = byteArrayOf(0)
        }
        for (length in listOf(4, 7, 10)) {
            OnePlusRawRecovery.change(device, ByteArray(length) { (it + 1).toByte() })
            assertArrayEquals(byteArrayOf(0), device.original!![0x58])
            assertArrayEquals(byteArrayOf(8, 0, 0, 0), device.original!![0x33])
        }
        assertEquals(1, device.events.count { it == "read" })
        device.activateFails = true
        device.restoreFails = true
        val error = assertThrows(IllegalStateException::class.java) {
            OnePlusRawRecovery.change(device, byteArrayOf(5, 6, 7, 8))
        }
        assertEquals("activation failed", error.message)
        assertSame(device.rollbackError, error.suppressed.single())
        val restarted = Device().apply {
            vendor = null; archive = saved
            original = device.original!!.mapValues { it.value.copyOf() }
            configuration = device.configuration.mapValues { it.value.copyOf() }.toMutableMap()
        }
        OnePlusRawRecovery.change(restarted, null)
        assertEquals(listOf("restore", "clear"), restarted.events)
        assertNull(restarted.original)
        assertArrayEquals(byteArrayOf(0), restarted.configuration[0x58])
        assertArrayEquals(byteArrayOf(8, 0, 0, 0), restarted.configuration[0x33])
    }

    @Test fun preparationFailuresRecordReadAndPersistenceStagesWithoutCallingTheWriter() {
        for (readFails in listOf(false, true)) {
            val device = Device().apply {
                vendor = null; archive = saved; reviewedAndroidStack = true
                configuration[0x58] = byteArrayOf(0)
                if (readFails) readFailure = IllegalStateException("GET unavailable") else saveFails = true
            }
            assertThrows(IllegalStateException::class.java) {
                OnePlusRawRecovery.change(device, byteArrayOf(1, 2, 3, 4))
            }
            val report = LegacyNfcController.getLastOperationReport()
            assertEquals(if (readFails) "READ_IDLE_BASELINE" else "SAVE_UID_SNAPSHOT", report.stage)
            assertEquals("PREPARATION_FAILED", report.result)
            assertTrue(report.requests.isEmpty())
            assertFalse(device.events.contains("activate"))
            assertFalse(device.events.contains("restore"))
        }
    }

    @Test fun enabledOrDifferentMetadataCannotBeArchived() {
        for (android in listOf(false, true)) for (field in listOf("mode", "atqa", "sak", "uid", "firmware")) {
            val device = Device().apply {
                globals = OnePlusVendorNfcController.Snapshot(
                    if (field == "mode") "1" else "0", if (field == "atqa") "0400" else null,
                    if (field == "sak") "20" else null, if (field == "uid") "08000000" else null,
                    if (field == "firmware") "new" else firmware)
            }
            assertThrows(IllegalStateException::class.java) { OnePlusRawRecovery.prepare(device, android) }
            assertSame(saved, device.vendor)
            assertTrue(device.events.isEmpty())
        }
    }

    @Test fun everyIdleParameterMustBePresentAndMatchBeforeArchiving() {
        for (android in listOf(false, true)) for (id in baseline().keys) for (missing in listOf(true, false)) {
            val device = Device().apply {
                if (missing) configuration.remove(id) else configuration[id] = byteArrayOf(0x7F)
            }
            assertThrows(IllegalStateException::class.java) { OnePlusRawRecovery.prepare(device, android) }
            assertSame(saved, device.vendor)
            assertEquals(listOf("read"), device.events)
        }
    }

    @Test fun failedReadOrMetadataRaceKeepsVendorRecovery() {
        for (android in listOf(false, true)) for (race in listOf(true, false)) {
            val device = Device().apply {
                changeSettingsDuringRead = race
                if (!race) readFailure = IllegalStateException("GET unavailable")
            }
            assertThrows(IllegalStateException::class.java) { OnePlusRawRecovery.prepare(device, android) }
            assertSame(saved, device.vendor)
            assertNull(device.archive)
        }
    }

    @Test fun androidMigrationArchivesWithoutAnySetterOrRawActivation() {
        val device = Device()
        OnePlusRawRecovery.prepare(device, true)
        assertEquals(listOf("read", "archive"), device.events)
        assertSame(saved, device.archive)
        assertNull(device.vendor)
        assertNull(device.original)
        val report = OemNfcController.getLastOperationReport()
        assertEquals("ARCHIVED_FOR_ANDROID_OEM", report.result)
        assertEquals("COMPLETE", report.stage)
        assertNull(report.vendorCallSucceeded)
        assertEquals("OEM_ACTIVATION_NOT_ESTABLISHED", NfcDiagnosticAssessment.assessOem(report, true).code)
    }

    @Test fun otaArchivesTheAndroid16RecordUsingOnlyLiveAndroid17Reads() {
        val old = "OnePlus/CPH2747/OP611FL1:16/BP2A.250605.015/B.R4T3.1abf132_aeee51_aea830:user/release-keys"
        val current = "OnePlus/CPH2747/OP611FL1:17/CP2A.260605.016/B.R4T3.50a3663-29f0eba-29fce1c:user/release-keys"
        val pending = OnePlusVendorNfcController.Snapshot("0", "0400", "20", null, old)
        fun device() = Device().apply {
            vendor = pending
            globals = OnePlusVendorNfcController.Snapshot("0", "0400", "20", null, current)
        }
        val good = device()
        OnePlusRawRecovery.prepareForAndroid(good, old, current)
        assertSame(pending, good.archive)
        assertNull(good.vendor)
        assertNull(good.original)
        assertEquals(listOf("read", "archive"), good.events)
        assertEquals("ARCHIVED_AFTER_SYSTEM_UPDATE", OemNfcController.getLastOperationReport().result)
        for (bad in listOf(device().apply { changeSettingsDuringRead = true },
            device().apply { configuration[0x33] = byteArrayOf(1, 2, 3, 4) },
            device().apply { configuration.remove(0x59) }, device().apply { archiveFails = true },
            device().apply { globals = pending },
            device().apply { globals = OnePlusVendorNfcController.Snapshot("0", "0400", "20", "01020304", current) })) {
            assertThrows(IllegalStateException::class.java) { OnePlusRawRecovery.prepareForAndroid(bad, old, current) }
            assertSame(pending, bad.vendor)
            assertNull(bad.archive)
            assertFalse("activate" in bad.events || "restore" in bad.events)
        }
    }

    @Test fun androidMigrationRetainsRecoveryOnLostEligibilityOrFailedArchive() {
        for (permitted in listOf(true, false)) {
            val device = Device().apply { this.permitted = permitted; archiveFails = true }
            assertThrows(IllegalStateException::class.java) { OnePlusRawRecovery.prepare(device, true) }
            assertSame(saved, device.vendor)
            assertNull(device.archive)
            assertFalse(device.events.contains("activate"))
        }
    }

    @Test fun failedArchiveCannotReleaseTheVendorMarkerOrWrite() {
        val device = Device().apply { archiveFails = true }
        assertThrows(IllegalStateException::class.java) { OnePlusRawRecovery.change(device, byteArrayOf(1, 2, 3, 4)) }
        assertSame(saved, device.vendor)
        assertNull(device.original)
        assertFalse(device.events.contains("activate"))
    }

    @Test fun failedSnapshotCommitCannotWrite() {
        val device = Device().apply { saveFails = true }
        assertThrows(IllegalStateException::class.java) { OnePlusRawRecovery.change(device, byteArrayOf(1, 2, 3, 4)) }
        assertSame(saved, device.archive)
        assertFalse(device.events.contains("activate"))
        assertFalse(device.events.contains("restore"))
    }

    @Test fun previouslyArchivedDeviceCannotOverwriteANewNonIdleSession() {
        val device = Device().apply {
            vendor = null
            configuration[0x33] = byteArrayOf(9, 8, 7, 6)
        }
        assertThrows(IllegalStateException::class.java) { OnePlusRawRecovery.change(device, byteArrayOf(1, 2, 3, 4)) }
        assertNull(device.original)
        assertEquals(listOf("read"), device.events)
    }

    @Test fun laterEnabledVendorModeCannotBeOverriddenByAnArchivedTrial() {
        val device = Device().apply {
            vendor = null
            globals = OnePlusVendorNfcController.Snapshot("1", "0400", "20", "08000000", firmware)
        }
        assertThrows(IllegalStateException::class.java) { OnePlusRawRecovery.change(device, byteArrayOf(1, 2, 3, 4)) }
        assertTrue(device.events.isEmpty())
    }

    @Test fun cardSwitchesRetainTheInitialBaselineForAllSupportedUidLengths() {
        val device = Device()
        for (length in listOf(4, 7, 10)) {
            val uid = ByteArray(length) { (it + 1).toByte() }
            OnePlusRawRecovery.change(device, uid)
            assertArrayEquals(uid, device.configuration[0x33])
            assertArrayEquals(byteArrayOf(8, 0, 0, 0), device.original!![0x33])
        }
        assertEquals(1, device.events.count { it == "archive" })
        OnePlusRawRecovery.change(device, null)
        assertArrayEquals(byteArrayOf(8, 0, 0, 0), device.configuration[0x33])
    }

    @Test fun activationFailureRestoresOriginalInsteadOfLeavingAnActiveCard() {
        val device = Device().apply { activateFails = true }
        val error = assertThrows(IllegalStateException::class.java) {
            OnePlusRawRecovery.change(device, byteArrayOf(1, 2, 3, 4))
        }
        assertEquals("activation failed", error.message)
        assertEquals(listOf("activate", "restore", "clear"), device.events.takeLast(3))
        assertNull(device.original)
    }

    @Test fun rollbackFailureKeepsSnapshotAndPrimaryError() {
        val device = Device().apply { activateFails = true; restoreFails = true }
        val error = assertThrows(IllegalStateException::class.java) {
            OnePlusRawRecovery.change(device, byteArrayOf(1, 2, 3, 4))
        }
        assertEquals("activation failed", error.message)
        assertSame(device.rollbackError, error.suppressed.single())
        assertNotNull(device.original)
        device.restoreFails = false
        OnePlusRawRecovery.change(device, null)
        assertNull(device.original)
    }

    @Test fun restartRestoresPersistedBaselineWithoutReadingANewOne() {
        val device = Device().apply { vendor = null; original = baseline() }
        OnePlusRawRecovery.change(device, null)
        assertEquals(listOf("restore", "clear"), device.events)
    }

    @Test fun firmwareChangeBlocksAllWritesAndRetainsRecovery() {
        val device = Device().apply { permitted = false; original = baseline() }
        assertThrows(IllegalStateException::class.java) { OnePlusRawRecovery.change(device, null) }
        assertThrows(IllegalStateException::class.java) { OnePlusRawRecovery.change(device, byteArrayOf(1, 2, 3, 4)) }
        assertNotNull(device.original)
        assertSame(saved, device.vendor)
        assertTrue(device.events.isEmpty())
    }
}
