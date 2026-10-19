package dev.dunno.emula

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnePlusVendorNfcControllerTest {
    @Test
    fun vendorPathPreservesCph2747Compatibility() {
        assertTrue(OnePlusVendorNfcController.isTargetDevice(36, "OnePlus", "CPH2747"))
        assertTrue(OnePlusVendorNfcController.isTargetDevice(37, "oneplus", "cph2747"))

        assertFalse(OnePlusVendorNfcController.isTargetDevice(35, "OnePlus", "CPH2747"))
        assertFalse(OnePlusVendorNfcController.isTargetDevice(36, "OnePlus", "CPH2745"))
        assertFalse(OnePlusVendorNfcController.isTargetDevice(36, "OPPO", "CPH2747"))
        assertFalse(OnePlusVendorNfcController.isTargetDevice(36, null, null))
    }

    @Test
    fun cph2707UsesVendorPathOnlyOnOnePlusAndroid16OrNewer() {
        assertTrue(OnePlusVendorNfcController.isTargetDevice(36, "OnePlus", "CPH2707"))
        assertTrue(OnePlusVendorNfcController.isTargetDevice(37, "oneplus", "cph2707"))

        assertFalse(OnePlusVendorNfcController.isTargetDevice(35, "OnePlus", "CPH2707"))
        assertFalse(OnePlusVendorNfcController.isTargetDevice(36, "OPPO", "CPH2707"))
        assertFalse(OnePlusVendorNfcController.isTargetDevice(36, "Google", "CPH2707"))
        assertFalse(OnePlusVendorNfcController.isTargetDevice(36, null, "CPH2707"))
        assertFalse(OnePlusVendorNfcController.isTargetDevice(36, "OnePlus", null))
        assertFalse(OnePlusVendorNfcController.isTargetDevice(36, "OnePlus", "CPH2709"))
    }

    @Test
    fun firmwareSettingsAcceptExpectedUidLengthsAndSeparators() {
        assertArrayEquals(
            byteArrayOf(0x06, 0x0C),
            OnePlusVendorNfcController.parseHexSetting("06 0C", 2),
        )
        assertArrayEquals(
            byteArrayOf(0x01, 0x02, 0x03, 0x04),
            OnePlusVendorNfcController.parseHexSetting("0x01:0x02:0x03:0x04", 4, 7, 10),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun firmwareSettingsRejectInvalidText() {
        OnePlusVendorNfcController.parseHexSetting("not-a-uid", 4)
    }

    @Test(expected = IllegalArgumentException::class)
    fun firmwareSettingsRejectUnexpectedLength() {
        OnePlusVendorNfcController.parseHexSetting("01 02 03", 4, 7, 10)
    }
}
