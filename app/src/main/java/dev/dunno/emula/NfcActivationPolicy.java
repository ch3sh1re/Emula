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
