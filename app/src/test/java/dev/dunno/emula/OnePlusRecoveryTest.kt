package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test

class OnePlusRecoveryTest {
    private val originalUid = byteArrayOf(0x11, 0x22, 0x33, 0x44)
    private val fingerprint = "oneplus-recovery-test-firmware"

    private inner class Device : OnePlusVendorNfcController.Platform {
        val values = mutableMapOf<String, String?>(
            "hce_access_card_mode" to "1", "hce_access_card_atqa" to "0400",
            "hce_access_card_sak" to "28", "hce_access_card_uid" to "11223344",
        )
        var saved: OnePlusVendorNfcController.Snapshot? = null
        var currentFirmware = fingerprint
        var config: MutableMap<Int, ByteArray> = mutableMapOf(
            0x30 to byteArrayOf(4), 0x31 to byteArrayOf(0),
            0x32 to byteArrayOf(0x28), 0x33 to originalUid.copyOf(),
        )
        var deny = false
        var readFails = false
        var clearFails = false
        var failGuardWrite = false
        var vendorCalls = 0
        var settingsWrites = 0
        var reads = 0
        val seenUids = mutableListOf<ByteArray>()
        override fun fingerprint() = currentFirmware
        override fun read(name: String) = values[name]
        override fun write(name: String, value: String?): Boolean {
            settingsWrites++
            if (failGuardWrite && name == "hce_access_card_mode" && value == "0") return false
            values[name] = value
            return true
        }
        override fun load() = saved
        override fun save(snapshot: OnePlusVendorNfcController.Snapshot) { saved = snapshot }
        override fun clear(): Boolean {
            if (clearFails) return false
            saved = null
            return true
        }
        override fun configuration(report: OemOperationReport): Map<Int, ByteArray> {
            reads++
            if (readFails) throw IllegalStateException("No GET response")
            return config
        }
        override fun invoke(enable: Boolean, atqa: ByteArray?, sak: ByteArray?, uid: ByteArray?): Boolean {
            vendorCalls++
            assertNotNull("Persist recovery before a vendor call", saved)
            if (deny) throw SecurityException("OplusNfcAdapterService requires com.oplus.permission.safe.NFC")
            if (enable) {
                seenUids.add(uid!!.copyOf())
                config[0x30] = byteArrayOf(atqa!![0])
                config[0x31] = byteArrayOf(atqa[1])
                config[0x32] = sak!!.copyOf()
                config[0x33] = uid.copyOf()
                values["hce_access_card_mode"] = "1"
            }
            return true
        }
        fun retainOldMarker() {
            saved = OnePlusVendorNfcController.Snapshot("1", "0400", "28", "11223344", fingerprint)
        }
    }

    private fun activate(device: Device, uid: ByteArray = byteArrayOf(1, 2, 3, 4)): OemOperationReport {
        val report = OemOperationReport(OemOperationReport.Operation.ACTIVATE)
        OnePlusVendorNfcController.setCardEnabled(device, uid, true, report)
        return report
    }

    private fun restore(device: Device): OemOperationReport {
        val report = OemOperationReport(OemOperationReport.Operation.STARTUP_RESTORE)
        OnePlusVendorNfcController.setCardEnabled(device, ByteArray(4), false, report)
        return report
    }

    @Test fun freshPermissionDenialClearsOnlyTheMarkerCreatedBeforeTheRejectedCall() {
        val device = Device().apply { deny = true }
        val report = OemOperationReport(OemOperationReport.Operation.ACTIVATE)
        assertThrows(SecurityException::class.java) {
            OnePlusVendorNfcController.setCardEnabled(device, byteArrayOf(1, 2, 3, 4), true, report)
        }
        assertNull(device.saved)
        assertEquals(1, device.vendorCalls)
        assertEquals(0, device.settingsWrites)
        assertNull(report.rollbackAttempted)
        assertEquals("CLEARED_AFTER_PERMISSION_DENIAL", report.snapshotState)
    }

    @Test fun olderMarkerClearsOnlyAfterMatchingSettingsAndControllerReadback() {
        val device = Device().apply { deny = true; retainOldMarker() }
        assertEquals("ORIGINAL_STATE_ALREADY_PRESENT", restore(device).result)
        assertNull(device.saved)
        assertEquals(0, device.vendorCalls)
        assertEquals(0, device.settingsWrites)
        assertEquals(1, device.reads)
    }

    @Test fun newActivationCannotDiscardAnOlderSnapshotOnPermissionDenial() {
        val device = Device().apply { retainOldMarker(); deny = true }
        val original = device.saved
        assertThrows(SecurityException::class.java) { activate(device) }
        assertSame(original, device.saved)
        assertEquals(0, device.settingsWrites)
    }

    @Test fun failureAfterVendorSuccessStillRollsBackTheOriginalState() {
        val device = Device().apply { failGuardWrite = true }
        val before = device.values.toMap()
        val report = OemOperationReport(OemOperationReport.Operation.ACTIVATE)
        assertThrows(IllegalStateException::class.java) {
            OnePlusVendorNfcController.setCardEnabled(device, byteArrayOf(1, 2, 3, 4), true, report)
        }
        assertEquals(true, report.vendorCallSucceeded)
        assertEquals(true, report.rollbackAttempted)
        assertEquals(true, report.rollbackSucceeded)
        assertEquals(2, device.vendorCalls)
        assertEquals(before, device.values)
        assertArrayEquals(originalUid, device.config[0x33])
        assertNull(device.saved)
    }

    @Test fun missingOrDifferentControllerParametersKeepOldSnapshotOnPermissionDenial() {
        for (id in listOf(0x30, 0x31, 0x32, 0x33)) {
            for (missing in listOf(true, false)) {
                val device = Device().apply {
                    deny = true; retainOldMarker()
                    if (missing) config.remove(id) else config[id] = byteArrayOf(0x7F)
                }
                assertThrows(SecurityException::class.java) { restore(device) }
                assertNotNull(device.saved)
                assertEquals(0, device.settingsWrites)
                assertEquals(1, device.vendorCalls)
            }
        }
    }

    @Test fun matchingReadbackAloneCannotClearChangedMetadata() {
        val device = Device().apply {
            deny = true; retainOldMarker(); values["hce_access_card_mode"] = "0"
        }
        assertThrows(SecurityException::class.java) { restore(device) }
        assertNotNull(device.saved)
        assertEquals(0, device.reads)
    }

    @Test fun firmwareChangeBlocksRestorationAndActivationWithoutDroppingOriginal() {
        val device = Device().apply { retainOldMarker(); currentFirmware = "updated-firmware" }
        assertThrows(IllegalStateException::class.java) { restore(device) }
        assertThrows(IllegalStateException::class.java) { activate(device) }
        assertNotNull(device.saved)
        assertEquals(0, device.vendorCalls)
        assertEquals(0, device.settingsWrites)
    }

    @Test fun unavailableReadOnlyApiStillPermitsNormalVendorRestoration() {
        val device = Device().apply { retainOldMarker(); readFails = true }
        assertEquals("SUCCESS", restore(device).result)
        assertNull(device.saved)
        assertEquals(1, device.vendorCalls)
    }

    @Test fun failedMarkerClearDoesNotAllowFallbackAsIfRecoveryCompleted() {
        val device = Device().apply { deny = true; clearFails = true }
        assertThrows(IllegalStateException::class.java) { activate(device) }
        assertNotNull(device.saved)
        assertEquals(1, device.vendorCalls)
    }

    @Test fun successfulCardSwitchesRetainTheFirstBaselineAndRestoreIt() {
        val device = Device()
        val before = device.values.toMap()
        for (length in listOf(4, 7, 10)) {
            val uid = ByteArray(length) { (it + 1).toByte() }
            assertEquals("SUCCESS", activate(device, uid).result)
            assertArrayEquals(uid, device.config[0x33])
            assertEquals("11223344", device.saved!!.uid)
            assertEquals("0", device.values["hce_access_card_mode"])
        }
        assertEquals("SUCCESS", restore(device).result)
        assertEquals(before, device.values)
        assertArrayEquals(originalUid, device.config[0x33])
        assertNull(device.saved)
    }
}
