package dev.dunno.emula

import org.junit.Assert.assertEquals
import org.junit.Test

class NfcActivationPolicyTest {
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
