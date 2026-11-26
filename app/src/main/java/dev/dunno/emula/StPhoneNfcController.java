package dev.dunno.emula;

import android.content.Context;
import android.os.Build;

import java.lang.reflect.Method;

/** ST HCE profile exposed by the stock sapphiren and Fairphone 5 NFC services. */
final class StPhoneNfcController {
    private static final String PREFS = "st_phone_hce_restore";

    private StPhoneNfcController() {}

    static boolean targets(int sdk, String manufacturer, String model, String device) {
        if (sdk < 35) return false;
        boolean redmi = "Xiaomi".equalsIgnoreCase(manufacturer) || "Redmi".equalsIgnoreCase(manufacturer);
        return (redmi && "sapphiren".equalsIgnoreCase(device))
                || ("Fairphone".equalsIgnoreCase(manufacturer) && "FP5".equalsIgnoreCase(model)
                    && "FP5".equalsIgnoreCase(device));
    }

    static boolean targets() {
        return targets(Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL, Build.DEVICE);
    }

    static boolean hasPendingRestore(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("pending", false);
    }

    static MotorolaStNfcController.Probe probe(Context context) {
        if (!targets()) return new MotorolaStNfcController.Probe(false, false, null);
        try {
            new ProfileSdk(context);
            return new MotorolaStNfcController.Probe(true, true, null);
        } catch (Exception | LinkageError error) {
            Throwable cause = OemNfcController.unwrap(error);
            return new MotorolaStNfcController.Probe(true, false,
                    cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    static void setCardEnabled(Context context, byte[] uid, boolean enabled, OemOperationReport report)
            throws Exception {
        report.path = OemOperationReport.Path.ST_VENDOR_HCE;
        report.profile = "ST Type A / " + Build.DEVICE;
        report.uid = uid.clone();
        report.sak = new byte[] {0x20};
        report.atqa = new byte[] {0x06, 0x0C};
        if (!enabled && !hasPendingRestore(context)) {
            report.result = "NO_PENDING_ST_RESTORE";
            report.stage = "COMPLETE";
            return;
        }
        if (!targets()) throw new IllegalStateException("Unsupported ST HCE device");
        report.stage = "RESOLVE_ST_SERVICE";
        ProfileSdk sdk = new ProfileSdk(context);
        MotorolaStNfcController.change(sdk,
                new MotorolaStNfcController.PreferenceStore(context, PREFS, true),
                Build.FINGERPRINT, uid, enabled, report);
    }

    private static final class ProfileSdk extends StVendorSdk implements StHceProfile.Access {
        // The same SDK record read and updated by the firmware's programHceParameters.
        private static final int HCE_SUBSET = 4;
        private static final int HCE_RECORD = 0;
        private final Method read;
        private final Method restore;

        ProfileSdk(Context context) throws Exception {
            super(context);
            read = method("sendPropGetConfig", byte[].class, int.class, int.class);
            restore = method("sendPropSetConfig", void.class, int.class, int.class, byte[].class);
        }

        @Override public byte[] readHceProfile() throws Exception {
            return (byte[]) invoke(read, receiver, HCE_SUBSET, HCE_RECORD);
        }

        @Override public void restoreHceProfile(byte[] profile) throws Exception {
            invoke(restore, receiver, HCE_SUBSET, HCE_RECORD, profile);
        }
    }
}
