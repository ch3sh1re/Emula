package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test

class HonorStNfcControllerTest {
    @Test
    fun reportedNxpHardwareExcludesStEvenWhenBothLibrariesAreInstalled() {
        assertTrue(HonorStNfcController.isNxpController("sn220u", "/dev/nq-nci"))
        assertTrue(HonorStNfcController.isNxpController(" SN220U ", null))
        assertTrue(HonorStNfcController.isNxpController(null, "/dev/nq-nci"))
        assertTrue(HonorStNfcController.isNxpController("sn110u", ""))
        assertTrue(HonorStNfcController.isNxpController("pn560", "/dev/pn544"))
        assertFalse(HonorStNfcController.isNxpController("st21nfc", "/dev/st21nfc"))
        assertFalse(HonorStNfcController.isNxpController("st54lnfc", "/dev/st21nfc"))
        assertFalse(HonorStNfcController.isNxpController(null, null))
        assertFalse(HonorStNfcController.isNxpController("unknown", ""))
    }

    @Test
    fun candidateIsRestrictedToReportedPhoneOnAndroid15AndNewer() {
        fun target(sdk: Int = 35, manufacturer: String? = "HONOR", model: String? = "REA-NX9",
                   device: String? = "HNREA") = HonorStNfcController.isTargetDevice(sdk, manufacturer, model, device)
        for (sdk in listOf(35, 36, 37, 38, 100)) assertTrue("SDK $sdk", target(sdk = sdk))
        assertTrue(target(manufacturer = "honor", model = "rea-nx9", device = "hnrea"))
        for (sdk in listOf(0, 34)) assertFalse(target(sdk = sdk))
        assertFalse(target(manufacturer = "HUAWEI"))
        assertFalse(target(model = "REA-AN00"))
        assertFalse(target(device = "HNBRC-M1"))
        assertFalse(target(manufacturer = null, model = null, device = null))
    }

    @Test
    fun unsupportedActivationStopsBeforeTouchingContextOrPreferences() {
        val report = OemOperationReport(OemOperationReport.Operation.ACTIVATE)
        try {
            HonorStNfcController.setCardEnabled(null, byteArrayOf(1, 2, 3, 4), true, report)
            fail("Expected device gate")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("not targeted"))
        }
        assertNull(report.vendorCallSucceeded)
        assertNull(report.rollbackAttempted)
    }

    @Test
    fun androidOemStaysPrimaryAndUnavailableHonorDoesNotAdvertiseSupport() {
        assertEquals("ANDROID_OEM_EXTENSION", capabilities(android = true).preferredPath())
        assertEquals("HONOR_ST_VENDOR_HCE", capabilities().preferredPath())
        assertTrue(capabilities().isSupported())
        assertFalse(capabilities(honor = false).isSupported())
        assertTrue(capabilities(honor = false).format(true).contains("HONOR ST probe failure: service missing"))
    }

    @Test
    fun confirmedRawFallbackSkipsHonorButPreservesOtherOemSupport() {
        assertFalse(capabilities().isSupported(false))
        assertTrue(capabilities(android = true).isSupported(false))
        assertEquals(NfcActivationPolicy.Path.CONFIRM_EXPERIMENTAL_RAW,
            NfcActivationPolicy.choose(true, capabilities().isSupported(false), true, false, false, false))
    }

    @Test
    fun unavailableHonorServiceKeepsTheExistingRawConsentAndFirmwareOptIn() {
        val state = capabilities(honor = false)
        assertEquals(NfcActivationPolicy.Path.CONFIRM_EXPERIMENTAL_RAW,
            NfcActivationPolicy.choose(true, state.isSupported(), true, false, false, false))
        assertEquals(NfcActivationPolicy.Path.EXPERIMENTAL_RAW,
            NfcActivationPolicy.choose(true, state.isSupported(), true, false, false, true))
    }

    @Test
    fun pendingRestoreIsExplicitEvenWhenServiceDisappears() {
        val state = capabilities(honor = false, pending = true)
        assertEquals("HONOR_ST_RESTORE_REQUIRED", state.preferredPath())
        assertTrue(state.format(true).contains("Pending HONOR ST restore: yes"))
        // Backend separately prioritizes pending restoration regardless of availability.
        assertFalse(state.isSupported())
    }

    private fun capabilities(android: Boolean = false, honor: Boolean = true, pending: Boolean = false) =
        OemNfcController.Capabilities(android, null, OnePlusVendorNfcController.Probe.notTargeted(),
            false, MotorolaStNfcController.Probe(false, false, null), false,
            MotorolaStNfcController.Probe(true, honor, if (honor) null else "service missing"), pending)
}
