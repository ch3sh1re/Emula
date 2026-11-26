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
            OnePlusAndroidRecovery.eligible(forced, reviewed, blocked, api, snapshot, raw, other)
        assertTrue(selected())
        assertFalse(selected(forced = true)); assertFalse(selected(reviewed = false))
        assertFalse(selected(blocked = false)); assertFalse(selected(api = false))
        assertFalse(selected(snapshot = null)); assertFalse(selected(raw = true)); assertFalse(selected(other = true))
        for ((mode, firmware) in listOf("1" to saved.fingerprint, "0" to "other", null to saved.fingerprint)) {
            assertFalse(selected(snapshot = OnePlusVendorNfcController.Snapshot(mode, null, null, null, firmware)))
        }
        assertTrue(OnePlusAndroidRecovery.matchesStack(170000014, "17.0.0.260114"))
        assertFalse(OnePlusAndroidRecovery.matchesStack(170000015, "17.0.0.260114"))
        assertFalse(OnePlusAndroidRecovery.matchesStack(170000014, "17.0.0.updated"))
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
