package dev.dunno.emula

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyNfcControllerTest {
    @Test
    fun rawModeIsLimitedToExplicitDeviceFamilies() {
        assertTrue(RawNfcDeviceCatalog.isSupported("Google", "Pixel 9"))
        assertTrue(RawNfcDeviceCatalog.isSupported("SAMSUNG", "SM-S928B"))
        assertTrue(RawNfcDeviceCatalog.isSupported("OnePlus", "CPH2745"))
        assertTrue(RawNfcDeviceCatalog.isSupported("Sony", "XQ-EC72"))
        assertTrue(RawNfcDeviceCatalog.isSupported("SONY", "xq-ec72"))

        assertFalse(RawNfcDeviceCatalog.isSupported("Sony", "XQ-CT72"))
        assertFalse(RawNfcDeviceCatalog.isSupported("OPPO", "CPH2669"))
        assertFalse(RawNfcDeviceCatalog.isSupported(null, null))
    }

    @Test
    fun rawProfilesComeFromTheDeviceCatalog() {
        assertTrue(RawNfcDeviceCatalog.profileFor("Google", "Pixel 9") ==
            RawNfcDeviceCatalog.Profile.PIXEL)
        assertTrue(RawNfcDeviceCatalog.profileFor("Samsung", "SM-S928B") ==
            RawNfcDeviceCatalog.Profile.CTS_TYPE_4A)
        assertTrue(RawNfcDeviceCatalog.profileFor("OnePlus", "CPH2745") ==
            RawNfcDeviceCatalog.Profile.CTS_TYPE_4A)
        assertTrue(RawNfcDeviceCatalog.profileFor("Sony", "XQ-EC72") ==
            RawNfcDeviceCatalog.Profile.CTS_TYPE_4A)
    }

    @Test
    fun discoveryTechnologyFallbackStaysFamilyAndVersionSpecific() {
        assertTrue(RawNfcDeviceCatalog.shouldUseDiscoveryTechnologyFallback(
            35, "Samsung", "SM-S928B"))
        assertFalse(RawNfcDeviceCatalog.shouldUseDiscoveryTechnologyFallback(
            36, "Samsung", "SM-S928B"))

        assertTrue(RawNfcDeviceCatalog.shouldUseDiscoveryTechnologyFallback(
            35, "OnePlus", "CPH2745"))
        assertTrue(RawNfcDeviceCatalog.shouldUseDiscoveryTechnologyFallback(
            36, "OnePlus", "CPH2745"))
        assertTrue(RawNfcDeviceCatalog.shouldUseDiscoveryTechnologyFallback(
            37, "OnePlus", "CPH2745"))
        assertTrue(RawNfcDeviceCatalog.shouldUseDiscoveryTechnologyFallback(
            36, "Sony", "XQ-EC72"))

        assertFalse(RawNfcDeviceCatalog.shouldUseDiscoveryTechnologyFallback(
            34, "Sony", "XQ-EC72"))
        assertFalse(RawNfcDeviceCatalog.shouldUseDiscoveryTechnologyFallback(
            36, "Sony", "XQ-CT72"))
        assertFalse(RawNfcDeviceCatalog.shouldUseDiscoveryTechnologyFallback(
            34, "OnePlus", "CPH2745"))
        assertFalse(RawNfcDeviceCatalog.shouldUseDiscoveryTechnologyFallback(
            36, "Google", "Pixel 9"))
    }

    @Test
    fun uidOnlyIsLimitedToTheProblematicOnePlus15Model() {
        assertTrue(RawNfcDeviceCatalog.prefersUidOnly("OnePlus", "CPH2747"))
        assertTrue(RawNfcDeviceCatalog.prefersUidOnly("oneplus", "cph2747"))

        assertFalse(RawNfcDeviceCatalog.prefersUidOnly("OnePlus", "CPH2745"))
        assertFalse(RawNfcDeviceCatalog.prefersUidOnly("Samsung", "CPH2747"))
        assertFalse(RawNfcDeviceCatalog.prefersUidOnly(null, null))
    }

    @Test
    fun onePlus15PrefersExplicitPollAndListenDisable() {
        assertTrue(RawNfcDeviceCatalog.prefersDiscoveryTechnologyControl(
            36, "OnePlus", "CPH2747"))
        assertTrue(RawNfcDeviceCatalog.prefersDiscoveryTechnologyControl(
            35, "oneplus", "cph2747"))

        assertFalse(RawNfcDeviceCatalog.prefersDiscoveryTechnologyControl(
            34, "OnePlus", "CPH2747"))
        assertFalse(RawNfcDeviceCatalog.prefersDiscoveryTechnologyControl(
            36, "OnePlus", "CPH2745"))
        assertFalse(RawNfcDeviceCatalog.prefersDiscoveryTechnologyControl(
            36, "Samsung", "CPH2747"))
        assertFalse(RawNfcDeviceCatalog.prefersDiscoveryTechnologyControl(
            36, null, null))
    }

    @Test
    fun unverifiedOptInUsesCtsProfileAndPublicDiscoveryOnAndroid15Plus() {
        assertTrue(RawNfcDeviceCatalog.profileForExperimental("OPPO", "CPH2669") ==
            RawNfcDeviceCatalog.Profile.CTS_TYPE_4A)
        assertFalse(RawNfcDeviceCatalog.shouldUseDiscoveryTechnologyFallback(
            36, "OPPO", "CPH2669"))
        assertFalse(RawNfcDeviceCatalog.shouldUseDiscoveryTechnologyFallback(
            34, "OPPO", "CPH2669", true))
        assertTrue(RawNfcDeviceCatalog.shouldUseDiscoveryTechnologyFallback(
            35, "OPPO", "CPH2669", true))
        assertTrue(RawNfcDeviceCatalog.shouldUseDiscoveryTechnologyFallback(
            37, "OPPO", "CPH2669", true))
    }

    @Test
    fun experimentalWriteRequiresEveryParameterItMayChange() {
        val complete = linkedMapOf(
            0x85 to byteArrayOf(0x01),
            0x30 to byteArrayOf(0x04),
            0x31 to byteArrayOf(0x00),
            0x32 to byteArrayOf(0x20),
            0x33 to byteArrayOf(0x01, 0x02, 0x03, 0x04),
            0x58 to byteArrayOf(0x00),
        )

        assertTrue(LegacyNfcController.isCompleteExperimentalSnapshot(complete))
        assertFalse(LegacyNfcController.isCompleteExperimentalSnapshot(
            complete.filterKeys { it != 0x33 },
        ))
    }

    @Test
    fun clearingPendingRawRestoreRequiresExactReadbackOfEverySavedParameter() {
        val original = linkedMapOf(
            0x85 to byteArrayOf(1), 0x30 to byteArrayOf(4), 0x31 to byteArrayOf(0),
            0x32 to byteArrayOf(0x20), 0x33 to byteArrayOf(8, 0, 0, 0),
            0x58 to byteArrayOf(0x70),
        )
        val readback = original.mapValues { it.value.copyOf() }
        assertTrue(LegacyNfcController.configurationEquals(original, readback))
        assertFalse(LegacyNfcController.configurationEquals(original,
            readback + (0x33 to byteArrayOf(1, 2, 3, 4))))
        assertFalse(LegacyNfcController.configurationEquals(original,
            readback.filterKeys { it != 0x85 }))
        assertFalse(LegacyNfcController.configurationEquals(emptyMap(), emptyMap()))
        assertFalse(LegacyNfcController.configurationEquals(null, readback))
    }

    @Test
    fun nothingReadOnlyReportAlreadyMatchesThePendingRestoreSnapshot() {
        // The six parameters in the 1.0.11 STARTUP_PENDING_RESTORE request.
        val saved = RawNciCodec.decodeConfiguration(
            "85=01;30=04;31=00;32=20;33=01020304;58=40",
        )
        // The user's subsequent GET_CONFIG response includes the unchanged 0x59.
        val response = ("00 07 30 01 04 31 01 00 32 01 20 33 04 01 02 03 04 " +
            "58 01 40 59 00 85 01 01").split(" ").map { it.toInt(16).toByte() }.toByteArray()
        val readback = RawNciCodec.parseGetConfigResponse(response)
        assertTrue(LegacyNfcController.configurationEquals(saved, readback))

        // No changed or missing controlled parameter may clear recovery.
        for ((parameter, value) in saved) {
            val changed = value.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertFalse("Changed parameter 0x${parameter.toString(16)}",
                LegacyNfcController.configurationEquals(saved, readback + (parameter to changed)))
            assertFalse("Missing parameter 0x${parameter.toString(16)}",
                LegacyNfcController.configurationEquals(saved, readback.filterKeys { it != parameter }))
        }
    }
}
