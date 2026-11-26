package dev.dunno.emula;

/** Pure routing policy kept separate from Android reflection and UI side effects. */
final class NfcActivationPolicy {
    enum Path {
        OEM,
        CATALOG_RAW,
        EXPERIMENTAL_RAW,
        CONFIRM_EXPERIMENTAL_RAW,
        UNSUPPORTED
    }

    private NfcActivationPolicy() {}

    /** The reported firmware acknowledges OEM activation but keeps the random UID. */
    static boolean requiresRawTrial(String manufacturer, String model, String fingerprint) {
        return "Xiaomi".equalsIgnoreCase(manufacturer)
                && "2506BPN68G".equalsIgnoreCase(model)
                && ("Xiaomi/klimt_eea/klimt:17/CP2A.260605.016/"
                + "OS3.0.336.0.XOSEUXM:user/release-keys").equals(fingerprint);
    }

    static boolean shouldRestoreOemAtStartup(boolean vendorPending, boolean modernApi,
            boolean rawTrialRequired) {
        return vendorPending || (modernApi && !rawTrialRequired);
    }

    static Path choose(boolean activating, boolean modernApi, boolean rawApi,
            boolean cataloguedRawAvailable, boolean rawForced,
            boolean experimentalAcceptedForBuild) {
        if (cataloguedRawAvailable && (!modernApi || rawForced)) {
            return Path.CATALOG_RAW;
        }
        if (modernApi && !(rawForced && rawApi)) return Path.OEM;
        if (!rawApi) return Path.UNSUPPORTED;
        if (experimentalAcceptedForBuild) return Path.EXPERIMENTAL_RAW;
        return activating ? Path.CONFIRM_EXPERIMENTAL_RAW : Path.UNSUPPORTED;
    }
}
