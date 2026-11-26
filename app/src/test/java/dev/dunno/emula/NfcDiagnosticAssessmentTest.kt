package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test

class NfcDiagnosticAssessmentTest {
    private fun raw() = RawOperationReport(RawOperationReport.Operation.ACTIVATE)
    private fun request(report: RawOperationReport, framework: Int, controller: Int?, command: String = "SET_CONFIG") =
        report.addRequest(report.operation, RawOperationReport.Candidate.LEGACY_PROFILE, command, command,
            byteArrayOf(1, 0x33)).apply {
            frameworkStatus = framework
            controllerStatus = controller
            rx = controller?.let { byteArrayOf(it.toByte()) }
        }

    @Test
    fun report7ControllerRejectionIsNotAnApiOrTransportFailure() {
        val report = raw().apply { stage = "FAILED"; primaryFailure = "SET_CONFIG rejected"; pauseSucceeded = true; resumeSucceeded = true }
        request(report, 0, 9).apply {
            failure = "NCI INVALID_PARAM"
            reportedParameterIds = listOf(0x85, 0x30, 0x31, 0x32, 0x33, 0x58)
        }
        report.rollback = RawOperationReport(RawOperationReport.Operation.ROLLBACK_AFTER_ACTIVATION_FAILURE).apply {
            stage = "COMPLETE"; result = "ORIGINAL_CONFIG_ALREADY_PRESENT"
        }
        assertEquals("RAW_CONFIG_REJECTED", NfcDiagnosticAssessment.assessRaw(report).code)
        val probe = RawOperationReport(RawOperationReport.Operation.DIAGNOSTIC_GET_CONFIG).apply { result = "OK" }
        assertEquals("RAW_READABLE", NfcDiagnosticAssessment.assessProbe(probe, null).code)
        assertEquals("RAW_CONFIG_REJECTED", NfcDiagnosticAssessment.assessRaw(report).code)
    }

    @Test
    fun frameworkFailureWithOkControllerStillRequiresTransportInvestigation() {
        val probe = RawOperationReport(RawOperationReport.Operation.DIAGNOSTIC_GET_CONFIG).apply { result = "FAILED" }
        request(probe, 3, 0, "GET_CONFIG")
        assertEquals("RAW_FRAMEWORK_FAILED_WITH_RESPONSE", NfcDiagnosticAssessment.assessProbe(probe, null).code)
        val text = NfcDiagnosticAssessment.format(true, true, false, true, false, null, null, probe, null)
        assertTrue(text.contains("RAW_FRAMEWORK_FAILED_WITH_RESPONSE"))
        assertTrue(text.contains("framework FAILED; controller OK"))
        assertTrue(text.contains("Last raw operation: RAW_NOT_TESTED"))
        assertTrue(text.contains("External reader: not verified"))
        assertFalse(text.contains("Next:"))
        assertFalse(text.contains("Checks:"))
    }

    @Test
    fun absentResponseAndExplicitFrameworkRejectionHaveDifferentNextDirections() {
        for ((framework, expected) in listOf(0 to "RAW_RESPONSE_MISSING", 1 to "RAW_FRAMEWORK_REJECTED",
                2 to "RAW_FRAMEWORK_REJECTED", 3 to "RAW_FRAMEWORK_FAILED")) {
            val probe = RawOperationReport(RawOperationReport.Operation.DIAGNOSTIC_GET_CONFIG).apply { result = "FAILED" }
            request(probe, framework, null, "GET_CONFIG")
            assertEquals(expected, NfcDiagnosticAssessment.assessProbe(probe, null).code)
        }
    }

    @Test
    fun completeReadAfterSingleFallbackDoesNotRetainBatchRejectionAsBlocker() {
        val probe = RawOperationReport(RawOperationReport.Operation.DIAGNOSTIC_GET_CONFIG).apply { result = "OK" }
        request(probe, 0, 9, "GET_CONFIG")
        request(probe, 0, 0, "GET_CONFIG")
        assertEquals("RAW_READABLE", NfcDiagnosticAssessment.assessProbe(probe, null).code)
    }

    @Test
    fun successfulActivationFallbackDoesNotRetainRejectedCandidateAsBlocker() {
        val report = raw().apply { stage = "COMPLETE"; result = "LEGACY_UID_VERIFIED" }
        request(report, 0, 9).failure = "first candidate failed"
        request(report, 0, 0)
        assertEquals("RAW_READBACK_VERIFIED_READER_REQUIRED", NfcDiagnosticAssessment.assessRaw(report).code)
    }

    @Test
    fun restorationIsNotMisreportedAsSuccessfulActivation() {
        val report = RawOperationReport(RawOperationReport.Operation.DEACTIVATE).apply {
            stage = "COMPLETE"; result = "ORIGINAL_CONFIG_RESTORED"
        }
        assertEquals("RAW_RESTORE_RECORDED", NfcDiagnosticAssessment.assessRaw(report).code)
        val oem = OemOperationReport(OemOperationReport.Operation.STARTUP_RESTORE).apply { stage = "COMPLETE"; result = "SUCCESS" }
        assertEquals("OEM_ACTIVATION_NOT_ESTABLISHED", NfcDiagnosticAssessment.assessOem(oem, true).code)
    }

    @Test
    fun acceptedButUnverifiedWriteDoesNotProveReadback() {
        val report = raw().apply { stage = "COMPLETE"; result = "ACCEPTED" }
        request(report, 0, 0)
        assertEquals("RAW_READBACK_REQUIRED", NfcDiagnosticAssessment.assessRaw(report).code)
    }

    @Test
    fun uidMismatchDirectsInvestigationToReadbackAndPersistence() {
        for (result in listOf("UID_READBACK_MISMATCH", "UID_OVERWRITTEN_AFTER_RESUME")) {
            val report = raw().apply { this.result = result; stage = "FAILED"; primaryFailure = "mismatch" }
            request(report, 0, 0)
            assertEquals("UID_PERSISTENCE_FAILED", NfcDiagnosticAssessment.assessRaw(report).code)
        }
    }

    @Test
    fun recoveryFailureTakesPriorityOverPrimaryControllerRejection() {
        val report = raw().apply { primaryFailure = "rejected"; rollbackFailure = "restore failed" }
        request(report, 0, 9)
        assertEquals("RAW_RECOVERY_FAILED", NfcDiagnosticAssessment.assessRaw(report).code)
        report.rollbackFailure = null
        report.resumeSucceeded = false
        assertEquals("RAW_RECOVERY_FAILED", NfcDiagnosticAssessment.assessRaw(report).code)
    }

    @Test
    fun failedPauseIsDistinguishedFromControllerFailure() {
        val report = raw().apply { pauseSucceeded = false; pauseFailure = "OEM pause rejected" }
        assertEquals("DISCOVERY_PAUSE_FAILED", NfcDiagnosticAssessment.assessRaw(report).code)
    }

    @Test
    fun oemPermissionFailureIsPreservedEvenWhenApiResolves() {
        val report = OemOperationReport(OemOperationReport.Operation.STARTUP_RESTORE).apply {
            failure = "SecurityException: com.oplus.permission.safe.NFC"
        }
        val finding = NfcDiagnosticAssessment.assessOem(report, true)
        assertEquals("OEM_PERMISSION_DENIED", finding.code)
        assertTrue(finding.evidence.contains("com.oplus.permission.safe.NFC"))
    }

    @Test
    fun oemSuccessStillRequiresExternalReaderEvidence() {
        val report = OemOperationReport(OemOperationReport.Operation.ACTIVATE).apply { stage = "COMPLETE"; result = "SUCCESS" }
        assertEquals("OEM_ACCEPTED_READER_REQUIRED", NfcDiagnosticAssessment.assessOem(report, true).code)
    }

    @Test
    fun currentPrerequisitesAreIndependentOfOldOperationFailures() {
        fun check(enabled: Boolean, permission: Boolean, oem: Boolean, raw: Boolean, recovery: Boolean) =
            NfcDiagnosticAssessment.prerequisites(enabled, permission, oem, raw, recovery).code
        assertEquals("NFC_DISABLED", check(false, true, true, true, false))
        assertEquals("APP_PERMISSION_MISSING", check(true, false, true, true, false))
        assertEquals("RECOVERY_PENDING", check(true, true, true, true, true))
        assertEquals("FIRMWARE_API_MISSING", check(true, true, false, false, false))
        assertEquals("API_CHECKS_PASSED", check(true, true, true, false, false))
        assertEquals("API_CHECKS_PASSED", check(true, true, false, true, false))
    }

    @Test
    fun missingAndPartialEvidenceDoNotDeclareUnsupportedHardware() {
        assertEquals("RAW_NOT_TESTED", NfcDiagnosticAssessment.assessRaw(null).code)
        assertEquals("PROBE_NOT_RUN", NfcDiagnosticAssessment.assessProbe(null, null).code)
        assertEquals("PROBE_UNAVAILABLE", NfcDiagnosticAssessment.assessProbe(null, "SecurityException").code)
        val partial = RawOperationReport(RawOperationReport.Operation.DIAGNOSTIC_GET_CONFIG).apply { result = "PARTIAL_RESULT" }
        request(partial, 0, 0, "GET_CONFIG")
        assertEquals("RAW_PROBE_INCOMPLETE", NfcDiagnosticAssessment.assessProbe(partial, null).code)
    }

    @Test
    fun missingOptionalHistoryDoesNotInvalidateAnOtherwiseCompleteLegacySnapshot() {
        val values = listOf(0x85, 0x30, 0x31, 0x32, 0x33, 0x58).associateWith { byteArrayOf(0) }
        assertTrue(LegacyNfcController.isCompleteExperimentalSnapshot(values))
        val partial = RawOperationReport(RawOperationReport.Operation.DIAGNOSTIC_GET_CONFIG).apply { result = "PARTIAL_RESULT" }
        request(partial, 0, 0, "GET_CONFIG")
        assertEquals("RAW_PROBE_INCOMPLETE", NfcDiagnosticAssessment.assessProbe(partial, null).code)
    }
}
