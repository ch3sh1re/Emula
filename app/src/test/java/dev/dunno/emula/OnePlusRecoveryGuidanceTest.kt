package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class OnePlusRecoveryGuidanceTest {
    private val saved = OnePlusVendorNfcController.Snapshot("0", null, null, null, OnePlusRawRecovery.FIRMWARE)
    private fun state(forced: Boolean = false, target: Boolean = true, blocked: Boolean = true,
                      snapshot: OnePlusVendorNfcController.Snapshot? = saved, archive: Boolean = false,
                      pending: Boolean = false, other: Boolean = false, reviewed: Boolean = false) =
        OnePlusRecoveryGuidance.status(forced, target, blocked, snapshot, archive, pending, other, reviewed, "CPH2747")

    @Test fun reviewedAndroidServiceOffersNormalModeAndPreservesRestorePrecedence() {
        assertEquals(OnePlusRecoveryGuidance.Status.ANDROID_OEM_PENDING_CHECKS, state(reviewed = true))
        assertEquals(OnePlusRecoveryGuidance.Status.ANDROID_OEM_READY, state(reviewed = true, snapshot = null))
        assertEquals(OnePlusRecoveryGuidance.Status.SELECTED_PENDING_CHECKS, state(reviewed = true, forced = true))
        assertEquals(OnePlusRecoveryGuidance.Status.RAW_RESTORE_PENDING, state(reviewed = true, pending = true))
        assertEquals(OnePlusRecoveryGuidance.Status.OTHER_RESTORE_PENDING, state(reviewed = true, other = true))
        assertEquals(OnePlusRecoveryGuidance.Status.NOT_ELIGIBLE, state(reviewed = true, blocked = false))
        assertEquals(OnePlusRecoveryGuidance.Status.NOT_TARGETED, state(reviewed = true, target = false))
    }

    @Test fun report43RequiresExplicitRawSelectionAndStillUsesUnchangedWriterPolicy() {
        assertEquals(OnePlusRecoveryGuidance.Status.RAW_MODE_REQUIRED, state())
        assertFalse(OnePlusRawRecovery.selectsTrial(false, true, true, saved, false))
        assertEquals(OnePlusRecoveryGuidance.Status.SELECTED_PENDING_CHECKS, state(forced = true))
        assertTrue(OnePlusRawRecovery.selectsTrial(true, true, true, saved, false))
    }

    @Test fun unavailableTrialsDoNotSuggestRawMode() {
        assertEquals(OnePlusRecoveryGuidance.Status.NOT_TARGETED, state(target = false))
        assertEquals(OnePlusRecoveryGuidance.Status.NOT_ELIGIBLE, state(blocked = false))
        assertEquals(OnePlusRecoveryGuidance.Status.NOT_ELIGIBLE, state(snapshot = null))
        for (snapshot in listOf(
            OnePlusVendorNfcController.Snapshot("1", null, null, null, OnePlusRawRecovery.FIRMWARE),
            OnePlusVendorNfcController.Snapshot("0", null, null, null, "another firmware")
        )) assertEquals(OnePlusRecoveryGuidance.Status.NOT_ELIGIBLE, state(snapshot = snapshot, archive = true))
        assertEquals(OnePlusRecoveryGuidance.Status.RAW_MODE_REQUIRED, state(snapshot = null, archive = true))
    }

    @Test fun pendingRestorationTakesPrecedenceOverModeAdvice() {
        for (forced in listOf(false, true)) {
            assertEquals(OnePlusRecoveryGuidance.Status.RAW_RESTORE_PENDING, state(forced = forced, pending = true))
            assertEquals(OnePlusRecoveryGuidance.Status.OTHER_RESTORE_PENDING, state(forced = forced, other = true))
        }
    }

    @Test fun guidanceOnlyHandlesTheVendorSecurityDenialIncludingWrappedFailures() {
        val denied = SecurityException("OplusNfcAdapterService: lacks com.oplus.permission.safe.NFC")
        assertTrue(OnePlusRecoveryGuidance.vendorPermissionDenied(denied))
        assertTrue(OnePlusRecoveryGuidance.vendorPermissionDenied(PhoneNfcSession.Failure(
            PhoneNfcSession.Reason.FAILED, false, InvocationTargetException(denied))))
        assertFalse(OnePlusRecoveryGuidance.vendorPermissionDenied(SecurityException("WRITE_SECURE_SETTINGS")))
        assertFalse(OnePlusRecoveryGuidance.vendorPermissionDenied(IllegalStateException(denied.message)))
        assertFalse(OnePlusRecoveryGuidance.vendorPermissionDenied(null))
    }
}
