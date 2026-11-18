package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test

class NfcFirmwareProfilesTest {
    @Test fun onePlusFirmwareMatchesDistinguishRawRestoreFromOemActivation() {
        val restore = NfcFirmwareProfiles.describe("0c92a11302dfe4e25c4472b156c823c2ddc67f724279cded22b07dd102ad6af9")
        assertTrue(restore.contains("CPH2447"))
        assertTrue(restore.contains("startup restore"))
        for ((hash, source) in mapOf(
            "f23dfaf598ba23e20c67f09518b019fd8ea593e0659ca01b24beb20f625e5985" to "CPH2573",
            "98f4b26ef2dbf8b33edb17cca5400dd2061cbe907f3fd2ae2443584ff14a423b" to "astonc 20261113"
        )) {
            val result = NfcFirmwareProfiles.describe(hash)
            assertTrue(result.contains(source))
            assertTrue(result.contains("Status 0 does not report individual controller writes"))
            assertEquals("Public NFC APK match: none established\n", NfcFirmwareProfiles.describe(hash.drop(1)))
        }
    }

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
