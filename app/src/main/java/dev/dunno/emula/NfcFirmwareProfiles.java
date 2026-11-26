package dev.dunno.emula;

/** Exact APK-byte matches for reporting only; never activation or permission policy. */
final class NfcFirmwareProfiles {
    private NfcFirmwareProfiles() {}

    static String describe(String sha256) {
        String source;
        String finding;
        if ("76fe9301e5d6bb35e4a4cef31804c27a4fa473fe0ff89951e21ebb196a300ced".equals(sha256)) {
            source = "Xiaomi klimt OS3.0.336.0.XOSEUXM / NQNfcNci";
            finding = "ANDROID_OEM_NO_OP: emulateNfcATag logs, checks admin permission and returns 0 without configuring NFC. "
                    + "The legacy NXP vendor interface returns null. "
                    + "Raw command/callback methods are implemented; writes and reader behavior still require separate evidence.";
        } else if ("016b97a36a6366af7881c287ae6c11712525f7cae24d13483c0b37fae06d882a".equals(sha256)) {
            source = "OPPO PLE110 16.0.10.600(CN01) / NfcNci";
            finding = oplusGates();
        } else if ("e9c72611e850a8f323a7f47252b6360c91370573ed509b9476c80f95013a8b20".equals(sha256)) {
            source = "OPPO CPH2865 EEA 16.0.10.501(EX01) / NfcNci";
            finding = oplusGates();
        } else if ("2c2d091f1c1fe26f5a52764caf110f28990cafe72c02adf4bd5a547701562830".equals(sha256)) {
            source = "HONOR NLA-L31 9.0.0.290(C430E6R2P2) / NfcNci_45_oversea";
            finding = "The service can time out after 3000 ms while callback delivery waits for its lock. "
                    + "A response delivered after framework FAILED remains diagnostic evidence only.";
        } else if ("0c92a11302dfe4e25c4472b156c823c2ddc67f724279cded22b07dd102ad6af9".equals(sha256)) {
            source = "OnePlus 11 CPH2447 India 16.0.5.1301(EX01) / NfcNci";
            finding = "The service forwards raw configuration requests to the device host. "
                    + "For a failed startup restore, check the controller response in LAST NFC OPERATION.";
        } else if ("f23dfaf598ba23e20c67f09518b019fd8ea593e0659ca01b24beb20f625e5985".equals(sha256)) {
            source = "OnePlus 12 CPH2573 India 17.0.0.100(EX01) / NfcNci";
            finding = androidOemReadback();
        } else if ("98f4b26ef2dbf8b33edb17cca5400dd2061cbe907f3fd2ae2443584ff14a423b".equals(sha256)) {
            source = "LineageOS 24.0 astonc 20261113 / NfcNciApex";
            finding = androidOemReadback();
        } else {
            return "Public NFC APK match: none established\n";
        }
        return "Public NFC APK match: " + source + "\n"
                + "Reviewed service behavior: " + finding + "\n"
                + "Match covers APK bytes only, not the whole firmware, loaded native libraries or hardware.\n";
    }

    private static String androidOemReadback() {
        return "Android OEM activation calls the device host and reapplies routing. "
                + "Status 0 does not report individual controller writes. "
                + "Compare the activation readback with an external reader result.";
    }

    private static String oplusGates() {
        return "OPLUS_HCE_GATES: vendor setHceTypeAConfig rejects CN-region devices and a disabled "
                + "ro.oplus.nfc.hce_accesscard property (default true). See the read-only region/property values. "
                + "Raw requests reach the native device host; SET rejection is not explained by API presence.";
    }
}
