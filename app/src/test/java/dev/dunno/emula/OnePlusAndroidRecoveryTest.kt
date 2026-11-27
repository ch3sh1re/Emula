package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test

class OnePlusAndroidRecoveryTest {
    private val saved = OnePlusVendorNfcController.Snapshot("0", null, null, null, OnePlusRawRecovery.FIRMWARE)
    private fun report(length: Int = 4) = OemOperationReport(OemOperationReport.Operation.ACTIVATE).apply {
        path = OemOperationReport.Path.ANDROID_OEM_EXTENSION
        stage = "COMPLETE"; result = "SUCCESS"; androidStatus = 0
        atqa = byteArrayOf(6, 12); sak = byteArrayOf(0x20); rats = byteArrayOf(0x40)
        uid = ByteArray(length) { (it + 1).toByte() }
    }
    private fun values(r: OemOperationReport) = linkedMapOf(
        0x30 to byteArrayOf(6), 0x31 to byteArrayOf(12), 0x32 to r.sak.copyOf(),
        0x33 to r.uid.copyOf(), 0x58 to byteArrayOf(0x40), 0x85 to byteArrayOf(0),
    )

    @Test fun migrationRequiresEveryReviewedScopeConditionAndDisabledSnapshot() {
        fun selected(forced: Boolean = false, reviewed: Boolean = true, blocked: Boolean = true,
                     api: Boolean = true, snapshot: OnePlusVendorNfcController.Snapshot? = saved,
                     raw: Boolean = false, other: Boolean = false) =
            OnePlusAndroidRecovery.eligible(forced, snapshot, raw, other,
                OnePlusRawRecovery.FIRMWARE, "CPH2747", "OP611FL1") { reviewed && blocked && api }
        assertTrue(selected())
        assertFalse(selected(forced = true)); assertFalse(selected(reviewed = false))
        assertFalse(selected(blocked = false)); assertFalse(selected(api = false))
        assertFalse(selected(snapshot = null)); assertFalse(selected(raw = true)); assertFalse(selected(other = true))
        for ((mode, firmware) in listOf("1" to saved.fingerprint, "0" to "other", null to saved.fingerprint)) {
            assertFalse(selected(snapshot = OnePlusVendorNfcController.Snapshot(mode, null, null, null, firmware)))
        }
        assertTrue(OnePlusVendorNfcController.matchesAndroidService(170000014, "17.0.0.260114"))
        assertTrue(OnePlusVendorNfcController.matchesAndroidService(170000015, "17.0.0.260904"))
        assertFalse(OnePlusVendorNfcController.matchesAndroidService(170000015, "17.0.0.260114"))
        assertFalse(OnePlusVendorNfcController.matchesAndroidService(170000014, "17.0.0.updated"))
    }

    @Test fun android16To17RecoveryDoesNotConsultAModelListOrAnActivationApi() {
        // The Nord 5 destination is a test fixture, not a hardware result.
        for ((product, device) in listOf("CPH2747" to "OP611FL1", "CPH2747EEA" to "OP611FL1",
                "CPH2707IN" to "OP6131L1", "futureProduct" to "futureDevice")) {
            val source = "OnePlus/$product/$device:16/other.id/vendor.16.patch:user/release-keys"
            val current = "OnePlus/$product/$device:17/new.id/vendor.17.patch:user/release-keys"
            fun selected(mode: String? = "0", forced: Boolean = false, raw: Boolean = false, other: Boolean = false) =
                OnePlusAndroidRecovery.eligible(forced, OnePlusVendorNfcController.Snapshot(mode, null, null, null, source),
                    raw, other, current, product, device) { error("An OTA check must not resolve an activation API") }
            assertTrue(selected())
            assertFalse(selected(mode = null))
            assertFalse(selected(mode = "1"))
            assertFalse(selected(forced = true))
            assertFalse(selected(raw = true))
            assertFalse(selected(other = true))
            assertFalse(OnePlusRawRecovery.isTarget(37, "OnePlus", product, current))
        }
        assertTrue(OnePlusAndroidRecovery.onePlusAndroid17(37, "OnePlus"))
        assertFalse(OnePlusAndroidRecovery.onePlusAndroid17(37, "OPPO"))
        assertFalse(OnePlusAndroidRecovery.onePlusAndroid17(36, "OnePlus"))
        assertFalse(OnePlusVendorNfcController.reviewedAndroidPhone(37, "OnePlus", "CPH2707", "OP6131L1"))
    }

    @Test fun anOtaRecordRequiresTheSameStockProductDeviceAndUpgradeDirection() {
        val old = "OnePlus/CPH2707IN/OP6131L1:16/old.id/old.patch:user/release-keys"
        val current = "OnePlus/CPH2707IN/OP6131L1:17/new.id/new.patch:user/release-keys"
        fun matches(source: String? = old, destination: String? = current, product: String = "CPH2707IN",
                    device: String = "OP6131L1") =
            OnePlusAndroidRecovery.updatedFrom16(source, destination, product, device)
        assertTrue(matches())
        for ((part, replacement) in listOf("OnePlus" to "other", "CPH2707IN" to "CPH2707EU",
                "OP6131L1" to "OP611FL1", "user/" to "userdebug/", "release-keys" to "test-keys",
                "user/release-keys" to "user/release-keys/extra")) {
            assertFalse(matches(source = old.replace(part, replacement)))
            assertFalse(matches(destination = current.replace(part, replacement)))
        }
        for (source in listOf(null, "", current, old.replace(":16/", ":15/"),
                old.replace("old.id", ""), old.replace("old.patch", "has space"))) {
            assertFalse(matches(source = source))
        }
        assertFalse(matches(destination = old))
        assertFalse(matches(destination = current.replace(":17/", ":18/")))
        assertFalse(matches(product = "CPH2707"))
        assertFalse(matches(device = "other"))
    }

    @Test fun matchingWrittenParametersStillRequireReaderEvidenceForEveryUidLength() {
        for (length in listOf(4, 7, 10)) {
            val r = report(length)
            OnePlusAndroidRecovery.observe(r) {
                // API receives null historical bytes; 0x59 is not a written/required value here.
                LegacyNfcDiagnostics.ProbeResult("summary", "transport detail", "", values(r))
            }
            assertEquals("MATCH", r.androidReadbackStatus)
            assertEquals("OEM_READBACK_MATCH_READER_REQUIRED", NfcDiagnosticAssessment.assessOem(r, true).code)
            assertTrue(r.formatDetailed().contains("transport detail"))
            assertTrue(r.formatForDisplay().contains("summary"))
            assertFalse(r.formatForDisplay().contains("transport detail"))
        }
    }

    @Test fun eachMissingOrDifferentWrittenParameterPreventsAMatch() {
        val r = report()
        for (id in values(r).keys) {
            assertEquals("INCOMPLETE", r.classifyAndroidReadback(values(r).apply { remove(id) }))
            assertEquals("MISMATCH", r.classifyAndroidReadback(values(r).apply { put(id, byteArrayOf(0x7F)) }))
        }
        assertEquals("MISMATCH", r.classifyAndroidReadback(
            values(r).apply { remove(0x30); put(0x33, byteArrayOf(8, 0, 0, 0)) }))
    }

    @Test fun serviceSuccessWithMismatchedReadbackIsNotAssessedAsEmulationSuccess() {
        val r = report()
        OnePlusAndroidRecovery.observe(r) {
            LegacyNfcDiagnostics.ProbeResult("uid differs", "uid differs", "",
                values(r).apply { put(0x33, byteArrayOf(8, 0, 0, 0)) })
        }
        assertEquals("SUCCESS", r.result) // Original service result is preserved.
        assertEquals("OEM_READBACK_MISMATCH", NfcDiagnosticAssessment.assessOem(r, true).code)
        assertTrue(r.formatDetailed().contains("Android service status: 0"))
    }

    @Test fun failedOrPartialProbeDoesNotReplaceServiceEvidenceOrEstablishSupport() {
        val r = report()
        OnePlusAndroidRecovery.observe(r) { throw IllegalStateException("transport failed") }
        assertEquals("UNAVAILABLE", r.androidReadbackStatus)
        assertEquals("COMPLETE", r.stage); assertEquals("SUCCESS", r.result)
        assertNull(r.failure)
        assertEquals("OEM_READBACK_INCONCLUSIVE", NfcDiagnosticAssessment.assessOem(r, true).code)
        assertTrue(r.formatDetailed().contains("transport failed"))
        OnePlusAndroidRecovery.observe(r) { LegacyNfcDiagnostics.ProbeResult("partial", "partial", "", emptyMap()) }
        assertEquals("INCOMPLETE", r.androidReadbackStatus)
        assertEquals("OEM_READBACK_INCONCLUSIVE", NfcDiagnosticAssessment.assessOem(r, true).code)
    }

    @Test fun otherPathsAndFailedServiceCallsNeverStartAProbe() {
        for (path in OemOperationReport.Path.values()) {
            if (path == OemOperationReport.Path.ANDROID_OEM_EXTENSION) continue
            val r = report().apply { this.path = path }
            OnePlusAndroidRecovery.observe(r) { error("must not probe $path") }
            assertNull(r.androidReadbackStatus)
        }
        for (status in listOf(null, 1, 2, 9)) {
            val r = report().apply { androidStatus = status }
            OnePlusAndroidRecovery.observe(r) { error("must not probe status $status") }
        }
        OnePlusAndroidRecovery.observe(report().apply { stage = "FAILED" }) { error("failed call") }
    }

    @Test fun disableReadbackDoesNotClaimHistoricalRestorationOrActivation() {
        val r = OemOperationReport(OemOperationReport.Operation.DEACTIVATE).apply {
            path = OemOperationReport.Path.ANDROID_OEM_EXTENSION; stage = "COMPLETE"; androidStatus = 0
        }
        OnePlusAndroidRecovery.observe(r) { LegacyNfcDiagnostics.ProbeResult("idle", "idle", "", emptyMap()) }
        assertEquals("OBSERVED_AFTER_DISABLE", r.androidReadbackStatus)
        assertEquals("OEM_ACTIVATION_NOT_ESTABLISHED", NfcDiagnosticAssessment.assessOem(r, true).code)
    }

    @Test fun interruptedProbeRetainsInterruptAndDoesNotRewriteServiceResult() {
        val r = report()
        try {
            OnePlusAndroidRecovery.observe(r) { throw InterruptedException("cancelled") }
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals("UNAVAILABLE", r.androidReadbackStatus)
            assertEquals("SUCCESS", r.result)
        } finally { Thread.interrupted() }
    }
}
