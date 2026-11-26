package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test

class OnePlusLegacyRecoveryTest {
    private val firmware = OnePlusLegacyRecovery.ONEPLUS_11.fingerprint
    private val onePlus15Firmware = "OnePlus/CPH2745IN/OP611FL1:16/BP2A.250605.015/" +
        "B.R4T3.1abf132_aeee51_aea830:user/release-keys"
    private val values = mapOf(0x30 to byteArrayOf(4), 0x31 to byteArrayOf(0),
        0x32 to byteArrayOf(32), 0x33 to byteArrayOf(8, 0, 0, 0), 0x58 to byteArrayOf(0x74),
        0x59 to byteArrayOf(), 0x85 to byteArrayOf(1))
    private val unexpected = OnePlusLegacyRecovery.SnapshotReader { error("Unexpected snapshot read") }

    @Test fun onePlus12KeepsTheSessionBitFrameForActivationAndReadback() {
        val eea = "OnePlus/CPH2581EEA/OP595DL1:16/BP2A.250605.015/U.test:user/release-keys"
        val catalog = RawNfcDeviceCatalog.Profile.CTS_TYPE_4A
        for (phone in listOf(eea, OnePlusLegacyRecovery.ONEPLUS_12.fingerprint)) {
            for (sdd in listOf<Byte>(1, 2, 4, 8, 16)) {
                val original = values + (0x30 to byteArrayOf(sdd))
                val first = OnePlusLegacyRecovery.baseline("CPH2581", phone, null, false, true, false, unexpected) { original }
                val saved = OnePlusLegacyRecovery.baseline("CPH2581", phone, phone, true, true, false, { original }, unexpected)
                for (baseline in listOf(first, saved)) {
                    val profile = LegacyNfcController.resolveProfile(catalog, phone, baseline)
                    for (length in listOf(4, 7, 10)) {
                        val uid = ByteArray(length) { (it + 1).toByte() }
                        assertArrayEquals(RawNciCodec.legacyPayload(uid, sdd, 0x0c, 0x20, 0x40),
                            profile.enabledConfig(uid))
                    }
                    val readback = original + mapOf(0x31 to byteArrayOf(0x0c), 0x58 to byteArrayOf(0x40))
                    assertTrue(profile.matches(readback))
                    assertFalse(profile.matches(readback + (0x30 to byteArrayOf(6))))
                    assertArrayEquals(byteArrayOf(sdd), original[0x30])
                }
            }
        }
    }

    @Test fun onlyTheLegacyWriterNeedsTheBitFrameEncoding() {
        val phone = OnePlusLegacyRecovery.ONEPLUS_12.fingerprint
        for (sdd in listOf(0, 6, 32, 128)) {
            val original = values + (0x30 to byteArrayOf(sdd.toByte()))
            assertSame(original, OnePlusLegacyRecovery.baseline("CPH2573", phone, null, false, true, false, unexpected) { original })
            assertThrows(IllegalStateException::class.java) {
                OnePlusLegacyRecovery.snapshotBitFrameSdd(original)
            }
            assertSame(original, OnePlusLegacyRecovery.baseline("CPH2573", phone, phone, true, false, false, { original }, unexpected))
        }
        for (original in listOf(null, values - 0x30, values + (0x30 to byteArrayOf()))) {
            assertThrows(IllegalStateException::class.java) {
                LegacyNfcController.resolveProfile(RawNfcDeviceCatalog.Profile.CTS_TYPE_4A, phone, original)
            }
        }
    }

    @Test fun rawUidSessionsFollowTheDeviceAcrossRegionsAndAndroid15AndLater() {
        val indian = OnePlusLegacyRecovery.ONEPLUS_12.fingerprint
        for ((sdk, release) in listOf(34 to "14", 35 to "15", 36 to "16", 37 to "17", 38 to "18")) {
            for (region in listOf("CPH2573IN", "CPH2581EEA", "CPH2583")) {
                val phone = indian.replace("CPH2573IN", region).replace(":16/", ":$release/")
                val eligible = sdk >= 35
                assertEquals(eligible, OnePlusLegacyRecovery.useUidSnapshot(sdk, phone, false, false))
                assertEquals(eligible, OnePlusLegacyRecovery.useUidSnapshot(sdk, phone, true, true))
                assertFalse(OnePlusLegacyRecovery.useUidSnapshot(sdk, phone, true, false))
                assertEquals(release == "16", OnePlusLegacyRecovery.oldRecord("CPH2581", phone, true, false, false))
            }
        }
        for (other in listOf(firmware, OnePlusRawRecovery.FIRMWARE, indian.replace("OnePlus/", "Other/"),
                indian.replace("user/release-keys", "userdebug/test-keys"), null)) {
            assertFalse(OnePlusLegacyRecovery.useUidSnapshot(37, other, false, false))
        }
    }

    @Test fun uidSessionsKeepTheirOriginalsOnAndroid15AndLater() {
        val phone16 = OnePlusLegacyRecovery.ONEPLUS_12.fingerprint
        for (release in listOf("15", "16", "17", "18")) {
            val phone = phone16.replace(":16/", ":$release/")
            assertSame(values, OnePlusLegacyRecovery.baseline("CPH2573", phone, null, false, true, true, unexpected) { values })
            for (activating in listOf(false, true)) {
                assertSame(values, OnePlusLegacyRecovery.baseline("CPH2573", phone, phone, true, activating, true, { values }, unexpected))
                assertThrows(IllegalStateException::class.java) {
                    OnePlusLegacyRecovery.baseline("CPH2573", phone, null, true, activating, true, unexpected, unexpected)
                }
                assertThrows(IllegalStateException::class.java) {
                    OnePlusLegacyRecovery.baseline("CPH2573", phone, phone + "_old", true, activating, true, unexpected, unexpected)
                }
            }
        }
    }

    @Test fun otherFirmwareRetainsItsCatalogBitFrameWithoutASnapshot() {
        val uid = byteArrayOf(1, 2, 3, 4)
        val cts = RawNfcDeviceCatalog.Profile.CTS_TYPE_4A
        val nextOs = OnePlusLegacyRecovery.ONEPLUS_12.fingerprint.replace(":16/", ":17/")
        for (other in listOf(firmware, nextOs, "Samsung/other", null)) {
            assertArrayEquals(RawNciCodec.legacyPayload(uid, 6, 0x0c, 0x20, 0x40),
                LegacyNfcController.resolveProfile(cts, other, null).enabledConfig(uid))
        }
        assertArrayEquals(RawNciCodec.legacyPayload(uid, 4, 0, 0, 0),
            LegacyNfcController.resolveProfile(RawNfcDeviceCatalog.Profile.PIXEL, "google/Pixel", null).enabledConfig(uid))
    }

    @Test fun captureBeforeFirstWriteAndKeepBaselineAcrossSwitchOrRestart() {
        assertSame(values, OnePlusLegacyRecovery.baseline("CPH2447", firmware, null, false, true, false, unexpected) { values })
        for (activating in listOf(true, false)) {
            assertSame(values, OnePlusLegacyRecovery.baseline("CPH2447", firmware, firmware, true,
                activating, false, { values }, unexpected))
        }
        assertThrows(IllegalStateException::class.java) {
            OnePlusLegacyRecovery.baseline("CPH2447", firmware, null, false, true, false, unexpected) { values - 0x33 }
        }
        assertThrows(IllegalStateException::class.java) {
            OnePlusLegacyRecovery.baseline("CPH2447", firmware, firmware, true, false, false,
                { values + (0x58 to byteArrayOf()) }, unexpected)
        }
    }

    @Test fun neverReplaceMissingOriginalsWithCurrentValuesOrReuseThemAfterOta() {
        assertNull(OnePlusLegacyRecovery.baseline("CPH2447", firmware, null, true, false, false, unexpected, unexpected))
        assertNull(OnePlusLegacyRecovery.baseline("CPH2447", "another build", null, false, true, false, unexpected, unexpected))
        assertThrows(IllegalStateException::class.java) {
            OnePlusLegacyRecovery.baseline("CPH2447", firmware, null, true, true, false, unexpected, unexpected)
        }
        assertThrows(IllegalStateException::class.java) {
            OnePlusLegacyRecovery.baseline("CPH2447", "updated firmware", firmware, true, false, false, unexpected, unexpected)
        }
    }

    @Test fun onlyFlagOnlyCataloguedRecordsOnTheReportedBuildUseRestartRecovery() {
        assertTrue(OnePlusLegacyRecovery.oldRecord("CPH2447", firmware, true, false, false))
        assertFalse(OnePlusLegacyRecovery.oldRecord("CPH2447", "different", true, false, false))
        assertFalse(OnePlusLegacyRecovery.oldRecord("CPH2447", firmware, false, false, false))
        assertFalse(OnePlusLegacyRecovery.oldRecord("CPH2447", firmware, true, true, false))
        assertFalse(OnePlusLegacyRecovery.oldRecord("CPH2447", firmware, true, false, true))
    }

    @Test fun onePlus12Android16UsesItsOwnRebootRecordAndSnapshot() {
        val phone = OnePlusLegacyRecovery.ONEPLUS_12
        val reviewed = "OnePlus/CPH2573IN/OP595DL1:16/BP2A.250605.015/" +
            "U.R4T3.4f93090-29426eb-296fc63:user/release-keys"
        assertSame(phone, OnePlusLegacyRecovery.forDevice("CPH2573", reviewed))
        assertTrue(OnePlusLegacyRecovery.oldRecord("CPH2573", reviewed, true, false, false))
        assertFalse(OnePlusLegacyRecovery.oldRecord("CPH2573", reviewed, true, false, true))
        assertFalse(OnePlusLegacyRecovery.oldRecord("CPH2573", reviewed, true, true, false))
        assertNull(OnePlusLegacyRecovery.forDevice("CPH2573", reviewed.replace(":16/", ":17/")))
        assertEquals("oneplus11_pending_boot", OnePlusLegacyRecovery.ONEPLUS_11.checkpointKey)
        assertEquals("oneplus11_archived_legacy_record", OnePlusLegacyRecovery.ONEPLUS_11.archiveKey)
        assertNotEquals(OnePlusLegacyRecovery.ONEPLUS_11.checkpointKey, phone.checkpointKey)
        assertNotEquals(OnePlusLegacyRecovery.ONEPLUS_11.archiveKey, phone.archiveKey)
        assertSame(values, OnePlusLegacyRecovery.baseline("CPH2573", reviewed, null, false, true, false, unexpected) { values })
        assertSame(values, OnePlusLegacyRecovery.baseline("CPH2573", reviewed, reviewed, true, false, false, { values }, unexpected))
        assertThrows(IllegalStateException::class.java) {
            OnePlusLegacyRecovery.baseline("CPH2573", reviewed, firmware, true, false, false, unexpected, unexpected)
        }
    }

    @Test fun onePlus12AcceptsRegionalProductsAndKeepsEachFirmwareRecoverySeparate() {
        val eea = "OnePlus/CPH2581EEA/OP595DL1:16/BP2A.250605.015/" +
            "U.R4T3.4f93090-29426eb-296fc63:user/release-keys"
        val india = OnePlusLegacyRecovery.ONEPLUS_12
        val products = listOf("CPH2581EEA", "CPH2581", "CPH2583", "AnotherProduct")
        val otherBuild = eea.replace("BP2A.250605.015", "BP2A.OTHER").replace("296fc63", "other")
        val fingerprints = products.map { eea.replace("CPH2581EEA", it) } + otherBuild
        val profiles = fingerprints.map { fingerprint ->
            val profile = requireNotNull(OnePlusLegacyRecovery.forDevice("CPH2581", fingerprint))
            assertEquals(fingerprint, profile.fingerprint)
            assertTrue(OnePlusLegacyRecovery.oldRecord("CPH2581", fingerprint, true, false, false))
            assertFalse(OnePlusLegacyRecovery.oldRecord("CPH2581", fingerprint, true, false, true))
            profile
        } + india
        assertEquals(profiles.size, profiles.distinctBy { it.checkpointKey }.size)
        assertEquals(profiles.size, profiles.distinctBy { it.archiveKey }.size)
        assertEquals("oneplus12_pending_boot", india.checkpointKey)
        assertEquals("oneplus12_archived_legacy_record", india.archiveKey)
        assertNull(OnePlusLegacyRecovery.forDevice("CPH2581", eea.replace("OP595DL1", "OP594DL1")))
        assertNull(OnePlusLegacyRecovery.forDevice("CPH2581", eea.replace("OnePlus/", "Other/")))
        assertNull(OnePlusLegacyRecovery.forDevice("CPH2581", eea.replace(":16/", ":15/")))
        assertNull(OnePlusLegacyRecovery.forDevice("CPH2581", eea.replace("user/release-keys", "userdebug/test-keys")))
        assertNull(OnePlusLegacyRecovery.forDevice("CPH2581", eea.replace("CPH2581EEA", "EU/Other")))
        assertThrows(IllegalStateException::class.java) {
            OnePlusLegacyRecovery.baseline("CPH2581", eea, india.fingerprint, true, false, false, unexpected, unexpected)
        }
        assertThrows(IllegalStateException::class.java) {
            OnePlusLegacyRecovery.baseline("CPH2581", otherBuild, eea, true, false, false, unexpected, unexpected)
        }
    }

    @Test fun onePlus15LegacyRecordsUseTheStockAndroid16ProfileAcrossProducts() {
        val model = "CPH2745"
        val firmware = onePlus15Firmware
        val regional = firmware.replace("CPH2745IN", "CPH2745EEA")
        val updated = firmware.replace("aea830", "other")
        val profiles = listOf(firmware, regional, updated).map {
            val profile = requireNotNull(OnePlusLegacyRecovery.forDevice(model, it))
            assertEquals("OnePlus 15", profile.displayName)
            assertEquals(it, profile.fingerprint)
            assertTrue(OnePlusLegacyRecovery.oldRecord(model, it, true, false, false))
            assertFalse(OnePlusLegacyRecovery.oldRecord(model, it, false, false, false))
            assertFalse(OnePlusLegacyRecovery.oldRecord(model, it, true, true, false))
            assertFalse(OnePlusLegacyRecovery.oldRecord(model, it, true, false, true))
            assertFalse(OnePlusLegacyRecovery.supportsUidSnapshot(36, it))
            profile
        }
        assertEquals(3, profiles.distinctBy { it.checkpointKey }.size)
        assertEquals(3, profiles.distinctBy { it.archiveKey }.size)
        for (other in listOf(firmware.replace(":16/", ":17/"), firmware.replace(":16/", ":15/"),
                firmware.replace("OP611FL1", "OTHER"), firmware.replace("OnePlus/", "Other/"),
                firmware.replace("user/release-keys", "userdebug/test-keys"))) {
            assertNull(OnePlusLegacyRecovery.forDevice(model, other))
        }
    }

    @Test fun onePlus15UsesTheOriginalOfTheSessionThatCreatedIt() {
        val model = "CPH2745"
        val firmware = onePlus15Firmware
        val original = values + (0x58 to byteArrayOf(0x41))
        assertSame(original, OnePlusLegacyRecovery.baseline(model, firmware, null, false, true, false,
            unexpected) { original })
        assertSame(original, OnePlusLegacyRecovery.baseline(model, firmware, firmware, true, false, false,
            { original }, unexpected))
        assertThrows(IllegalStateException::class.java) {
            OnePlusLegacyRecovery.baseline(model, firmware.replace("aea830", "other"), firmware,
                true, false, false, unexpected, unexpected)
        }
    }

    @Test fun onePlus15UidFirstVariantKeepsItsExistingRecovery() {
        for (model in listOf("CPH2747", "cph2747")) {
            val firmware = onePlus15Firmware.replace("CPH2745IN", "CPH2747")
            assertTrue(RawNfcDeviceCatalog.prefersUidOnly("OnePlus", model))
            assertNull(OnePlusLegacyRecovery.forDevice(model, firmware))
            assertFalse(OnePlusLegacyRecovery.oldRecord(model, firmware, true, false, false))
            assertNull(OnePlusLegacyRecovery.baseline(model, firmware, null, false, true, false,
                unexpected, unexpected))
        }
    }

    @Test fun processRestartOrUnknownBootCannotRetireTheRecord() {
        for ((first, current) in listOf(12 to 12, 12 to 11, -1 to 12, 12 to -1)) {
            val report = RawOperationReport(RawOperationReport.Operation.STARTUP_PENDING_RESTORE)
            assertThrows(IllegalStateException::class.java) {
                OnePlusLegacyRecovery.checkAfterRestart(first, current, report, unexpected)
            }
            assertEquals(if (first == current) "RESTART_REQUIRED" else "RESTART_UNVERIFIED", report.result)
        }
    }

    @Test fun onlyAnExactCompleteReadAfterBootCanRetireTheRecord() {
        val report = RawOperationReport(RawOperationReport.Operation.STARTUP_PENDING_RESTORE)
        var reads = 0
        OnePlusLegacyRecovery.checkAfterRestart(12, 13, report) { reads++; values }
        assertEquals(1, reads)
        for ((id, bytes) in values) {
            for (incomplete in listOf(values - id, values + (id to bytes.plus(1.toByte())))) {
                assertThrows(IllegalStateException::class.java) {
                    OnePlusLegacyRecovery.checkAfterRestart(12, 13, report) { incomplete }
                }
                assertEquals("POST_BOOT_PROFILE_MISMATCH", report.result)
            }
        }
        val timeout = IllegalStateException("GET timeout")
        assertSame(timeout, assertThrows(IllegalStateException::class.java) {
            OnePlusLegacyRecovery.checkAfterRestart(12, 13, report) { throw timeout }
        })
    }
}
