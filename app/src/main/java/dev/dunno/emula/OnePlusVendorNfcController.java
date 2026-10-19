package dev.dunno.emula;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/** Model-scoped access to the OnePlus global HCE Type-A configuration API. */
@SuppressLint("PrivateApi")
final class OnePlusVendorNfcController {
    private static final String VENDOR_ADAPTER_CLASS = "com.vendor.nfc.VendorNfcAdapter";
    private static final String PREFS = "oneplus_vendor_hce_restore";
    private static final String PREF_PENDING = "pending";
    private static final String PREF_VERSION = "version";
    private static final String PREF_FINGERPRINT = "fingerprint";
    private static final String PREF_MODE = "mode";
    private static final String PREF_ATQA = "atqa";
    private static final String PREF_SAK = "sak";
    private static final String PREF_UID = "uid";
    private static final int SNAPSHOT_VERSION = 1;
    private static final String NULL_VALUE = "\u0000";

    private static final String SETTING_MODE = "hce_access_card_mode";
    private static final String SETTING_ATQA = "hce_access_card_atqa";
    private static final String SETTING_SAK = "hce_access_card_sak";
    private static final String SETTING_UID = "hce_access_card_uid";

    private static final byte[] CTS_ATQA = {(byte) 0x06, (byte) 0x0C};
    private static final byte[] CTS_SAK = {(byte) 0x20};

    private OnePlusVendorNfcController() {}

    static boolean isTargetDevice() {
        return isTargetDevice(Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL);
    }

    static boolean isTargetDevice(int sdk, String manufacturer, String model) {
        return sdk >= 36
                && "oneplus".equalsIgnoreCase(manufacturer)
                && ("cph2747".equalsIgnoreCase(model)
                        || "cph2707".equalsIgnoreCase(model));
    }

    static Probe probe(Context context) {
        if (!isTargetDevice()) return Probe.notTargeted();
        try {
            Api api = resolveApi(context, null);
            return new Probe(true, true, true, true, null, api);
        } catch (ResolutionException failure) {
            return failure.probe;
        } catch (Throwable failure) {
            return new Probe(true, false, false, false, describe(failure), null);
        }
    }

    static boolean hasPendingRestore(Context context) {
        return preferences(context).getBoolean(PREF_PENDING, false);
    }

    static Api resolveApi(Context context, OemOperationReport report)
            throws ResolutionException {
        if (!isTargetDevice()) {
            throw new ResolutionException(Probe.notTargeted(),
                    "OnePlus vendor HCE is not enabled for this device");
        }

        Class<?> adapterClass;
        try {
            adapterClass = Class.forName(
                    VENDOR_ADAPTER_CLASS, false, context.getClassLoader());
            if (report != null) report.vendorLibraryAvailable = true;
        } catch (Throwable failure) {
            if (report != null) {
                report.vendorLibraryAvailable = false;
                report.vendorResolutionFailure = describe(failure);
            }
            throw new ResolutionException(
                    new Probe(true, false, false, false, describe(failure), null),
                    "nfcvendorlib is unavailable", failure);
        }

        Method getter;
        Method setter;
        try {
            getter = adapterClass.getMethod("getVendorNfcAdapter");
            setter = adapterClass.getMethod("setHceTypeAConfig",
                    boolean.class, byte[].class, byte[].class, byte[].class);
            if (!Modifier.isStatic(getter.getModifiers())) {
                throw new NoSuchMethodException("getVendorNfcAdapter is not static");
            }
            if (report != null) report.vendorMethodResolved = true;
        } catch (Throwable failure) {
            if (report != null) {
                report.vendorMethodResolved = false;
                report.vendorResolutionFailure = describe(failure);
            }
            throw new ResolutionException(
                    new Probe(true, true, false, false, describe(failure), null),
                    "OnePlus vendor HCE method is unavailable", failure);
        }

        Object adapter;
        try {
            adapter = getter.invoke(null);
            if (adapter == null) throw new IllegalStateException("Vendor NFC adapter is null");
            if (report != null) report.vendorAdapterResolved = true;
        } catch (Throwable failure) {
            Throwable root = unwrap(failure);
            if (report != null) {
                report.vendorAdapterResolved = false;
                report.vendorResolutionFailure = describe(root);
            }
            throw new ResolutionException(
                    new Probe(true, true, true, false, describe(root), null),
                    "OnePlus vendor NFC service is unavailable", root);
        }
        return new Api(adapter, setter);
    }

    static void setCardEnabled(Context context, Api api, byte[] uid, boolean enable,
            OemOperationReport report) throws Exception {
        report.path = OemOperationReport.Path.ONEPLUS_VENDOR_HCE;
        report.profile = "cts-type-4a / OnePlus vendor HCE";
        report.atqa = OemOperationReport.copy(CTS_ATQA);
        report.sak = OemOperationReport.copy(CTS_SAK);
        report.uid = OemOperationReport.copy(uid);
        report.vendorLibraryAvailable = true;
        report.vendorAdapterResolved = true;
        report.vendorMethodResolved = true;

        if (enable) {
            activate(context, api, uid, report);
        } else {
            restore(context, api, report, false);
        }
    }

    private static void activate(Context context, Api api, byte[] uid,
            OemOperationReport report) throws Exception {
        report.stage = "SNAPSHOT_ORIGINAL_STATE";
        Snapshot existing = loadSnapshot(context);
        boolean capturedNow = existing == null;
        Snapshot snapshot = capturedNow ? captureSnapshot(context) : existing;
        report.snapshotState = capturedNow ? "CAPTURED_NOT_PERSISTED" : "REUSED_PENDING";
        report.originalGuardMode = printable(snapshot.mode);
        addSnapshotFirmwareEvidence(report, snapshot);
        validateSnapshot(snapshot);
        if (capturedNow) {
            persistSnapshot(context, snapshot);
            report.snapshotState = "CAPTURED";
        }

        boolean invocationStarted = false;
        try {
            report.stage = "VENDOR_SET_HCE_TYPE_A";
            invocationStarted = true;
            boolean accepted = invoke(api, true, CTS_ATQA, CTS_SAK, uid);
            report.vendorCallSucceeded = accepted;
            report.guardModeAfterVendorCall = printable(readSetting(context, SETTING_MODE));
            if (!accepted) {
                throw new IllegalStateException(
                        "OnePlus setHceTypeAConfig returned false");
            }

            report.stage = "NEUTRALIZE_RF_GUARD";
            boolean guardUpdated = writeSetting(context, SETTING_MODE, "0");
            report.guardModeAfterNeutralize = printable(readSetting(context, SETTING_MODE));
            report.guardNeutralized = guardUpdated
                    && "0".equals(readSetting(context, SETTING_MODE));
            if (!Boolean.TRUE.equals(report.guardNeutralized)) {
                throw new IllegalStateException(
                        "Could not neutralize OnePlus HCE access-card RF guard");
            }

            report.stage = "RESTORE_ORIGINAL_METADATA";
            report.originalMetadataRestored = restoreMetadata(context, snapshot);
            if (!Boolean.TRUE.equals(report.originalMetadataRestored)) {
                throw new IllegalStateException(
                        "Could not restore original OnePlus access-card metadata");
            }
            report.result = "SUCCESS";
            report.stage = "COMPLETE";
        } catch (Throwable failure) {
            if (!invocationStarted) {
                if (capturedNow) clearSnapshot(context);
            } else if (Boolean.FALSE.equals(report.vendorCallSucceeded)) {
                if (capturedNow) clearSnapshot(context);
            } else {
                try {
                    restore(context, api, report, true);
                } catch (Throwable rollbackFailure) {
                    report.rollbackFailure = describe(rollbackFailure);
                }
            }
            throw asException(failure);
        }
    }

    private static void restore(Context context, Api api, OemOperationReport report,
            boolean rollback) throws Exception {
        report.stage = "LOAD_ORIGINAL_STATE";
        Snapshot snapshot = loadSnapshot(context);
        if (snapshot == null) {
            if (rollback) {
                report.rollbackAttempted = false;
                report.rollbackSucceeded = false;
                throw new IllegalStateException("Original OnePlus HCE snapshot is missing");
            }
            report.snapshotState = "NONE_PENDING";
            report.result = "NO_PENDING_VENDOR_STATE";
            report.stage = "COMPLETE";
            report.notes.add("Vendor defaults were left untouched because Emula has no snapshot");
            return;
        }
        validateSnapshot(snapshot);
        report.snapshotState = rollback ? "ROLLBACK_PENDING" : "RESTORE_PENDING";
        report.originalGuardMode = printable(snapshot.mode);
        addSnapshotFirmwareEvidence(report, snapshot);
        if (rollback) report.rollbackAttempted = true;

        try {
            report.stage = rollback ? "ROLLBACK_VENDOR_STATE" : "RESTORE_VENDOR_STATE";
            boolean originalEnabled = "1".equals(snapshot.mode);
            boolean accepted;
            if (originalEnabled) {
                accepted = invoke(api, true,
                        parseHexSetting(snapshot.atqa, 2),
                        parseHexSetting(snapshot.sak, 1),
                        parseHexSetting(snapshot.uid, 4, 7, 10));
            } else {
                accepted = invoke(api, false, null, null, null);
            }
            if (!rollback) {
                report.vendorCallSucceeded = accepted;
                report.guardModeAfterVendorCall =
                        printable(readSetting(context, SETTING_MODE));
            }
            if (!accepted) {
                throw new IllegalStateException(
                        "OnePlus rejected restoration of the original HCE state");
            }
            if (!restoreAllSettings(context, snapshot)) {
                throw new IllegalStateException(
                        "Could not restore original OnePlus HCE settings");
            }
            if (!clearSnapshot(context)) {
                throw new IllegalStateException(
                        "Original HCE state was restored but the pending marker remains");
            }
            if (rollback) {
                report.rollbackSucceeded = true;
            } else {
                report.vendorCallSucceeded = true;
                report.result = "SUCCESS";
                report.stage = "COMPLETE";
            }
        } catch (Throwable failure) {
            if (rollback) report.rollbackSucceeded = false;
            throw asException(failure);
        }
    }

    private static boolean invoke(Api api, boolean enable, byte[] atqa, byte[] sak,
            byte[] uid) throws Exception {
        Object result;
        try {
            result = api.setHceTypeAConfig.invoke(api.adapter,
                    enable,
                    copy(atqa),
                    copy(sak),
                    copy(uid));
        } catch (InvocationTargetException failure) {
            throw asException(unwrap(failure));
        }
        if (!(result instanceof Boolean)) {
            throw new IllegalStateException("Unexpected OnePlus NFC response: " + result);
        }
        return (Boolean) result;
    }

    private static Snapshot captureSnapshot(Context context) {
        return new Snapshot(
                readSetting(context, SETTING_MODE),
                readSetting(context, SETTING_ATQA),
                readSetting(context, SETTING_SAK),
                readSetting(context, SETTING_UID),
                Build.FINGERPRINT);
    }

    private static void validateSnapshot(Snapshot snapshot) {
        if (snapshot == null) throw new IllegalStateException("HCE snapshot is missing");
        if (!"1".equals(snapshot.mode)) return;
        parseHexSetting(snapshot.atqa, 2);
        parseHexSetting(snapshot.sak, 1);
        parseHexSetting(snapshot.uid, 4, 7, 10);
    }

    private static boolean restoreMetadata(Context context, Snapshot snapshot) {
        boolean atqa = writeSetting(context, SETTING_ATQA, snapshot.atqa);
        boolean sak = writeSetting(context, SETTING_SAK, snapshot.sak);
        boolean uid = writeSetting(context, SETTING_UID, snapshot.uid);
        return atqa && sak && uid;
    }

    private static boolean restoreAllSettings(Context context, Snapshot snapshot) {
        boolean atqa = writeSetting(context, SETTING_ATQA, snapshot.atqa);
        boolean sak = writeSetting(context, SETTING_SAK, snapshot.sak);
        boolean uid = writeSetting(context, SETTING_UID, snapshot.uid);
        boolean mode = writeSetting(context, SETTING_MODE, snapshot.mode);
        return atqa && sak && uid && mode;
    }

    private static String readSetting(Context context, String name) {
        return Settings.Global.getString(context.getContentResolver(), name);
    }

    private static boolean writeSetting(Context context, String name, String value) {
        return Settings.Global.putString(context.getContentResolver(), name, value);
    }

    private static void persistSnapshot(Context context, Snapshot snapshot) {
        boolean committed = preferences(context).edit()
                .clear()
                .putInt(PREF_VERSION, SNAPSHOT_VERSION)
                .putBoolean(PREF_PENDING, true)
                .putString(PREF_FINGERPRINT, encode(snapshot.fingerprint))
                .putString(PREF_MODE, encode(snapshot.mode))
                .putString(PREF_ATQA, encode(snapshot.atqa))
                .putString(PREF_SAK, encode(snapshot.sak))
                .putString(PREF_UID, encode(snapshot.uid))
                .commit();
        if (!committed) {
            throw new IllegalStateException("Could not persist original OnePlus HCE state");
        }
    }

    private static Snapshot loadSnapshot(Context context) {
        SharedPreferences preferences = preferences(context);
        if (!preferences.getBoolean(PREF_PENDING, false)) return null;
        if (preferences.getInt(PREF_VERSION, 0) != SNAPSHOT_VERSION) {
            throw new IllegalStateException("Unsupported OnePlus HCE snapshot version");
        }
        return new Snapshot(
                decode(preferences.getString(PREF_MODE, NULL_VALUE)),
                decode(preferences.getString(PREF_ATQA, NULL_VALUE)),
                decode(preferences.getString(PREF_SAK, NULL_VALUE)),
                decode(preferences.getString(PREF_UID, NULL_VALUE)),
                decode(preferences.getString(PREF_FINGERPRINT, NULL_VALUE)));
    }

    private static boolean clearSnapshot(Context context) {
        return preferences(context).edit().clear().commit();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String encode(String value) {
        return value == null ? NULL_VALUE : value;
    }

    private static String decode(String value) {
        return NULL_VALUE.equals(value) ? null : value;
    }

    static byte[] parseHexSetting(String value, int... allowedLengths) {
        if (value == null) throw new IllegalArgumentException("Missing hex setting");
        String compact = value.trim()
                .replace("0x", "")
                .replace("0X", "")
                .replaceAll("[\\s:-]", "");
        if (!compact.matches("[0-9A-Fa-f]+") || (compact.length() & 1) != 0) {
            throw new IllegalArgumentException("Invalid hex setting: " + value);
        }
        byte[] result = new byte[compact.length() / 2];
        boolean lengthAllowed = false;
        for (int allowed : allowedLengths) {
            if (result.length == allowed) lengthAllowed = true;
        }
        if (!lengthAllowed) {
            throw new IllegalArgumentException(
                    "Unexpected hex setting length " + result.length);
        }
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(compact.charAt(i * 2), 16);
            int low = Character.digit(compact.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Invalid hex setting: " + value);
            }
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    private static String printable(String value) {
        return value == null ? "<unset>" : value;
    }

    private static void addSnapshotFirmwareEvidence(
            OemOperationReport report, Snapshot snapshot) {
        if (snapshot.fingerprint == null) return;
        report.notes.add("Snapshot firmware: " + snapshot.fingerprint);
        if (!snapshot.fingerprint.equals(Build.FINGERPRINT)) {
            report.notes.add("Snapshot firmware differs from the current Build.FINGERPRINT");
        }
    }

    private static String describe(Throwable failure) {
        Throwable root = unwrap(failure);
        String message = root.getMessage();
        return root.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof InvocationTargetException
                && ((InvocationTargetException) current).getCause() != null) {
            current = ((InvocationTargetException) current).getCause();
        }
        return current;
    }

    private static Exception asException(Throwable failure) {
        if (failure instanceof Exception) return (Exception) failure;
        return new IllegalStateException(describe(failure), failure);
    }

    static final class Api {
        final Object adapter;
        final Method setHceTypeAConfig;

        Api(Object adapter, Method setHceTypeAConfig) {
            this.adapter = adapter;
            this.setHceTypeAConfig = setHceTypeAConfig;
        }
    }

    static final class Probe {
        final boolean targeted;
        final boolean libraryAvailable;
        final boolean methodResolved;
        final boolean adapterResolved;
        final String failure;
        final Api api;

        Probe(boolean targeted, boolean libraryAvailable, boolean methodResolved,
                boolean adapterResolved, String failure, Api api) {
            this.targeted = targeted;
            this.libraryAvailable = libraryAvailable;
            this.methodResolved = methodResolved;
            this.adapterResolved = adapterResolved;
            this.failure = failure;
            this.api = api;
        }

        boolean isAvailable() {
            return targeted && libraryAvailable && methodResolved && adapterResolved;
        }

        static Probe notTargeted() {
            return new Probe(false, false, false, false, null, null);
        }
    }

    static final class ResolutionException extends Exception {
        final Probe probe;

        ResolutionException(Probe probe, String message) {
            super(message);
            this.probe = probe;
        }

        ResolutionException(Probe probe, String message, Throwable cause) {
            super(message, cause);
            this.probe = probe;
        }
    }

    private static final class Snapshot {
        final String mode;
        final String atqa;
        final String sak;
        final String uid;
        final String fingerprint;

        Snapshot(String mode, String atqa, String sak, String uid, String fingerprint) {
            this.mode = mode;
            this.atqa = atqa;
            this.sak = sak;
            this.uid = uid;
            this.fingerprint = fingerprint;
        }
    }
}
