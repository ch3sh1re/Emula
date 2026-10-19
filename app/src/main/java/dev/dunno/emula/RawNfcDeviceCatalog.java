package dev.dunno.emula;

/** Device-specific compatibility data for the experimental raw NCI path. */
final class RawNfcDeviceCatalog {
    enum Profile {
        PIXEL,
        CTS_TYPE_4A
    }

    private enum DiscoveryFallback {
        NEVER,
        ANDROID_15_ONLY,
        ANDROID_15_PLUS
    }

    // A null model matches the entire manufacturer family. Keep model-specific entries for
    // OEMs such as Sony until each controller generation and firmware path has been verified.
    private static final DeviceSpec[] SUPPORTED_DEVICES = new DeviceSpec[] {
            family("google", Profile.PIXEL, DiscoveryFallback.NEVER),
            family("samsung", Profile.CTS_TYPE_4A, DiscoveryFallback.ANDROID_15_ONLY),
            family("oneplus", Profile.CTS_TYPE_4A, DiscoveryFallback.ANDROID_15_PLUS),
            device("sony", "XQ-EC72", Profile.CTS_TYPE_4A,
                    DiscoveryFallback.ANDROID_15_PLUS)
    };

    private RawNfcDeviceCatalog() {}

    /** Watch firmware is unverified even when its manufacturer matches a phone profile. */
    static boolean prefersWatchDiscoveryControl(int sdkInt, boolean watch,
            boolean allowUnverified) {
        return watch && allowUnverified && sdkInt >= 35;
    }

    static boolean isSupported(String manufacturer, String model) {
        return find(manufacturer, model) != null;
    }

    static Profile profileFor(String manufacturer, String model) {
        DeviceSpec device = find(manufacturer, model);
        if (device == null) {
            throw new IllegalArgumentException("Raw NCI device is not supported");
        }
        return device.profile;
    }

    static Profile profileForExperimental(String manufacturer, String model) {
        DeviceSpec device = find(manufacturer, model);
        return device == null ? Profile.CTS_TYPE_4A : device.profile;
    }

    /** Keep verified devices on legacy behavior; only the SN220T OnePlus 15 probes UID-only first. */
    static boolean prefersUidOnly(String manufacturer, String model) {
        return manufacturer != null && model != null
                && manufacturer.equalsIgnoreCase("oneplus")
                && model.equalsIgnoreCase("CPH2747");
    }

    static String profileDescription(String manufacturer, String model) {
        DeviceSpec device = find(manufacturer, model);
        return device == null ? "unknown" : "known (" + device.profile + ")";
    }

    static boolean shouldUseDiscoveryTechnologyFallback(
            int sdkInt, String manufacturer, String model) {
        return shouldUseDiscoveryTechnologyFallback(
                sdkInt, manufacturer, model, false);
    }

    static boolean shouldUseDiscoveryTechnologyFallback(
            int sdkInt, String manufacturer, String model, boolean allowUnverified) {
        DeviceSpec device = find(manufacturer, model);
        if (device == null) return allowUnverified && sdkInt >= 35;
        if (device.discoveryFallback == DiscoveryFallback.ANDROID_15_ONLY) {
            return sdkInt == 35;
        }
        return device.discoveryFallback == DiscoveryFallback.ANDROID_15_PLUS
                && sdkInt >= 35;
    }

    /**
     * CPH2747 reports a successful OEM polling pause while its controller still rejects every
     * Listen-A parameter write. Prefer the public API that explicitly disables both polling and
     * listening on this model; keep the OEM extension first everywhere else.
     */
    static boolean prefersDiscoveryTechnologyControl(
            int sdkInt, String manufacturer, String model) {
        return sdkInt >= 35
                && manufacturer != null
                && model != null
                && manufacturer.equalsIgnoreCase("oneplus")
                && model.equalsIgnoreCase("CPH2747");
    }

    private static DeviceSpec find(String manufacturer, String model) {
        for (DeviceSpec device : SUPPORTED_DEVICES) {
            if (device.matches(manufacturer, model)) return device;
        }
        return null;
    }

    private static DeviceSpec family(
            String manufacturer, Profile profile, DiscoveryFallback discoveryFallback) {
        return new DeviceSpec(manufacturer, null, profile, discoveryFallback);
    }

    private static DeviceSpec device(String manufacturer, String model,
            Profile profile, DiscoveryFallback discoveryFallback) {
        return new DeviceSpec(manufacturer, model, profile, discoveryFallback);
    }

    private static final class DeviceSpec {
        final String manufacturer;
        final String model;
        final Profile profile;
        final DiscoveryFallback discoveryFallback;

        DeviceSpec(String manufacturer, String model,
                Profile profile, DiscoveryFallback discoveryFallback) {
            this.manufacturer = manufacturer;
            this.model = model;
            this.profile = profile;
            this.discoveryFallback = discoveryFallback;
        }

        boolean matches(String candidateManufacturer, String candidateModel) {
            return manufacturer.equalsIgnoreCase(candidateManufacturer)
                    && (model == null || model.equalsIgnoreCase(candidateModel));
        }
    }
}
