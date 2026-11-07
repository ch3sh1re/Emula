package dev.dunno.emula;

/** Watch-only fallback eligibility. Manufacturer/model names are deliberately not inputs. */
final class WatchActivationPolicy {
    private WatchActivationPolicy() {}

    static WatchNfcSession.Path select(int sdk, boolean watch, boolean oem,
            boolean rawApi, boolean pollingControl) {
        if (sdk < 35 || !watch) return null;
        if (oem) return WatchNfcSession.Path.OEM;
        return rawApi && pollingControl ? WatchNfcSession.Path.RAW : null;
    }

    static boolean hasConsent(String firmware, String acceptedFirmware) {
        return firmware != null && !firmware.isBlank() && !"unknown".equals(firmware)
                && firmware.equals(acceptedFirmware);
    }
}
