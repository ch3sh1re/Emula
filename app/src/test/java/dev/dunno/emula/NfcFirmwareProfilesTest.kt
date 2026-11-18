package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test

class NfcFirmwareProfilesTest {
    @Test fun onlyTheCompleteReviewedApkHashEstablishesTheXiaomiStub() {
        val hash = "76fe9301e5d6bb35e4a4cef31804c27a4fa473fe0ff89951e21ebb196a300ced"
        val result = NfcFirmwareProfiles.describe(hash)
        assertTrue(result.contains("ANDROID_OEM_NO_OP"))
        assertTrue(result.contains("Raw command/callback methods are implemented"))
        for (other in listOf(null, "", hash.dropLast(1), hash.dropLast(1) + "0", "37", "17")) {
            assertEquals("Public NFC APK match: none established\n", NfcFirmwareProfiles.describe(other))
        }
    }

    @Test fun oppoMatchesExplainRuntimeGatesWithoutDeclaringWorkingEmulation() {
        for (hash in listOf("016b97a36a6366af7881c287ae6c11712525f7cae24d13483c0b37fae06d882a",
            "e9c72611e850a8f323a7f47252b6360c91370573ed509b9476c80f95013a8b20")) {
            val result = NfcFirmwareProfiles.describe(hash)
            assertTrue(result.contains("OPLUS_HCE_GATES"))
            assertTrue(result.contains("Match covers APK bytes only"))
            assertFalse(result.contains("ANDROID_OEM_NO_OP"))
        }
    }
}
