package dev.dunno.emula

import org.junit.Assert.assertTrue
import org.junit.Test

class OemOperationReportTest {
    @Test
    fun detailedReportKeepsVendorGuardAndRollbackEvidence() {
        val report = OemOperationReport(OemOperationReport.Operation.ACTIVATE).apply {
            path = OemOperationReport.Path.ONEPLUS_VENDOR_HCE
            stage = "FAILED"
            profile = "cts-type-4a / OnePlus ST54L vendor HCE"
            atqa = byteArrayOf(0x06, 0x0C)
            sak = byteArrayOf(0x20)
            uid = byteArrayOf(0xA6.toByte(), 0xBA.toByte(), 0x9F.toByte(), 0xA6.toByte())
            snapshotState = "ROLLBACK_PENDING"
            originalGuardMode = "0"
            vendorCallSucceeded = true
            guardModeAfterVendorCall = "1"
            guardNeutralized = false
            rollbackAttempted = true
            rollbackSucceeded = true
            failure = "IllegalStateException: guard write failed"
            notes.add("firmware-scoped path")
        }

        val text = report.formatDetailed()

        assertTrue(text.contains("Path: ONEPLUS_VENDOR_HCE"))
        assertTrue(text.contains("ATQA: 06 0C"))
        assertTrue(text.contains("UID: A6 BA 9F A6"))
        assertTrue(text.contains("Guard after vendor call: 1"))
        assertTrue(text.contains("Guard neutralized: FAILED"))
        assertTrue(text.contains("ROLLBACK"))
        assertTrue(text.contains("Succeeded: OK"))
        assertTrue(text.contains("Note: firmware-scoped path"))
    }
}
