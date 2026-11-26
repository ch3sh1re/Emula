package dev.dunno.emula

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class NfcActivationPolicyTest {
    @Test
    fun startupSkipsBrokenOrAbsentOemButNeverSkipsPendingVendorRecovery() {
        assertFalse(NfcActivationPolicy.shouldRestoreOemAtStartup(false, false, false))
        assertFalse(NfcActivationPolicy.shouldRestoreOemAtStartup(false, true, true))
        assertTrue(NfcActivationPolicy.shouldRestoreOemAtStartup(false, true, false))
        for (modern in listOf(false, true)) {
            for (rawTrial in listOf(false, true)) {
                assertTrue(NfcActivationPolicy.shouldRestoreOemAtStartup(true, modern, rawTrial))
            }
        }
    }

    @Test
    fun xiaomiRandomUidWorkaroundRequiresExactReportedFirmwareAndRawConsent() {
        val fingerprint = "Xiaomi/klimt_eea/klimt:17/CP2A.260605.016/OS3.0.336.0.XOSEUXM:user/release-keys"
        assertTrue(NfcActivationPolicy.requiresRawTrial("Xiaomi", "2506BPN68G", fingerprint))
        assertFalse(NfcActivationPolicy.requiresRawTrial("Xiaomi", "2506BPN68G", fingerprint.replace(".336", ".337")))
        assertFalse(NfcActivationPolicy.requiresRawTrial("Xiaomi", "other", fingerprint))
        assertFalse(NfcActivationPolicy.requiresRawTrial("other", "2506BPN68G", fingerprint))
        assertFalse(NfcActivationPolicy.requiresRawTrial(null, null, null))
        assertTrue(NfcActivationPolicy.choose(true, false, true, false, false, false) ==
                NfcActivationPolicy.Path.CONFIRM_EXPERIMENTAL_RAW)
        assertTrue(NfcActivationPolicy.choose(true, false, true, false, false, true) ==
                NfcActivationPolicy.Path.EXPERIMENTAL_RAW)
        assertTrue(NfcActivationPolicy.choose(true, false, false, false, false, true) ==
                NfcActivationPolicy.Path.UNSUPPORTED)
    }

    @Test
    fun oemRemainsPrimaryForCataloguedDevicesUnlessRawIsForced() {
        assertEquals(
            NfcActivationPolicy.Path.OEM,
            NfcActivationPolicy.choose(true, true, true, true, false, false),
        )
        assertEquals(
            NfcActivationPolicy.Path.CATALOG_RAW,
            NfcActivationPolicy.choose(true, true, true, true, true, false),
        )
    }

    @Test
    fun cataloguedRawRemainsAutomaticWhenOemApiIsUnavailable() {
        assertEquals(
            NfcActivationPolicy.Path.CATALOG_RAW,
            NfcActivationPolicy.choose(true, false, true, true, false, false),
        )
    }

    @Test
    fun unknownRawDeviceRequiresConfirmationThenUsesFingerprintOptIn() {
        assertEquals(
            NfcActivationPolicy.Path.CONFIRM_EXPERIMENTAL_RAW,
            NfcActivationPolicy.choose(true, false, true, false, false, false),
        )
        assertEquals(
            NfcActivationPolicy.Path.EXPERIMENTAL_RAW,
            NfcActivationPolicy.choose(true, false, true, false, false, true),
        )
    }

    @Test
    fun missingInterfacesAndUnconfirmedDeactivationAreUnsupported() {
        assertEquals(
            NfcActivationPolicy.Path.UNSUPPORTED,
            NfcActivationPolicy.choose(true, false, false, false, false, false),
        )
        assertEquals(
            NfcActivationPolicy.Path.UNSUPPORTED,
            NfcActivationPolicy.choose(false, false, true, false, false, false),
        )
    }

    @Test
    fun forcedRawOnUncataloguedVendorDeviceStillRequiresFirmwareConsent() {
        assertEquals(
            NfcActivationPolicy.Path.CONFIRM_EXPERIMENTAL_RAW,
            NfcActivationPolicy.choose(true, true, true, false, true, false),
        )
        assertEquals(
            NfcActivationPolicy.Path.EXPERIMENTAL_RAW,
            NfcActivationPolicy.choose(true, true, true, false, true, true),
        )
        assertEquals(
            NfcActivationPolicy.Path.OEM,
            NfcActivationPolicy.choose(true, true, true, false, false, true),
        )
        assertEquals(
            NfcActivationPolicy.Path.UNSUPPORTED,
            NfcActivationPolicy.choose(false, true, true, false, true, false),
        )
    }
}
