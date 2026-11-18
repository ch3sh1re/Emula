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
        } else {
            return "Public NFC APK match: none established\n";
        }
        return "Public NFC APK match: " + source + "\n"
                + "Reviewed service behavior: " + finding + "\n"
                + "Match covers APK bytes only, not the whole firmware, loaded native libraries or hardware.\n";
    }

    private static String oplusGates() {
        return "OPLUS_HCE_GATES: vendor setHceTypeAConfig rejects CN-region devices and a disabled "
                + "ro.oplus.nfc.hce_accesscard property (default true). See the read-only region/property values. "
                + "Raw requests reach the native device host; SET rejection is not explained by API presence.";
    }
}
