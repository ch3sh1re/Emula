package dev.dunno.emula;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Model-scoped ST raw compatibility on Android 15-17; the older OEM experiment
 * remains disabled by default and limited to Android 15.
 * Source: STMicroelectronics/ST54-android-packages-apps-Nfc, android-15,
 * NfcService.StExtrasService.programHceParameters and NfcStExtensions.setNciConfig.
 * Controller/reader behavior on Motorola remains unverified until the device test.
 */
@SuppressLint("PrivateApi")
final class MotorolaStNfcController {
    private static final String ADAPTER =
            "com.st.android.nfc_extensions.NfcAdapterStExtensions";
    private static final String PREFS = "motorola_st_hce_restore";
    private static final int[] PARAMETERS = {0x30, 0x31, 0x32, 0x33, 0x58, 0x59, 0x85};

    private MotorolaStNfcController() {}

    static boolean isTargetDevice() {
        return isTargetDevice(BuildConfig.MOTOROLA_ST_EXPERIMENT,
                Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL, Build.DEVICE);
    }

    static boolean isTargetDevice(boolean experiment, int sdk, String manufacturer,
            String model, String device) {
        return experiment && sdk == 35 && "motorola".equalsIgnoreCase(manufacturer)
                && "moto g stylus 5G - 2024".equalsIgnoreCase(model)
                && "boston".equalsIgnoreCase(device);
    }

    static boolean isRawModeTarget() {
        return isRawModeTarget(BuildConfig.MOTOROLA_ST_RAW_COMPATIBILITY,
                Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL, Build.DEVICE);
    }

    static boolean isRawModeTarget(boolean enabled, int sdk, String manufacturer,
            String model, String device) {
        return enabled && sdk >= 35 && sdk <= 37 && "motorola".equalsIgnoreCase(manufacturer)
                && "moto g stylus 5G - 2024".equalsIgnoreCase(model)
                && "boston".equalsIgnoreCase(device);
    }

    /** Raw includes the automatic fallback when the normal OEM API is unavailable. */
    static boolean useRawMode(boolean targeted, boolean forcedRaw, boolean oemAvailable,
            boolean pending) {
        return pending || (targeted && (forcedRaw || !oemAvailable));
    }

    static boolean attemptRawMode(boolean selected, boolean confirmedRawFallback, boolean pending) {
        return selected && (!confirmedRawFallback || pending);
    }

    /** Disabling new activations must not strand a snapshot from an earlier test build. */
    static boolean isApiAllowed(boolean experiment, boolean pending, int sdk,
            String manufacturer, String model, String device) {
        return isTargetDevice(experiment, sdk, manufacturer, model, device)
                || isRawModeTarget(pending, sdk, manufacturer, model, device);
    }

    static boolean hasPendingRestore(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("pending", false);
    }

    interface Attempt { void run() throws Exception; }

    /** A void return is not success: the attempt includes configuration readback and rollback. */
    static boolean tryFirst(boolean activating, Attempt attempt, BooleanSupplier pending,
            OemOperationReport report) throws Exception {
        try {
            attempt.run();
            return true;
        } catch (Exception | LinkageError failure) {
            report.failure = describe(failure);
            report.stage = "FAILED";
            // Never switch APIs with an outstanding snapshot or failed restoration. This
            // also covers preference commits that failed after changing their in-memory value.
            if (!activating || pending.getAsBoolean() || Boolean.FALSE.equals(report.rollbackSucceeded)
                    || (report.vendorCallSucceeded != null && !Boolean.TRUE.equals(report.rollbackSucceeded))
                    || (Boolean.TRUE.equals(report.rollbackAttempted)
                        && !Boolean.TRUE.equals(report.rollbackSucceeded))) {
                report.notes.add("Fallback blocked: restore the ST session before trying another API.");
                if (failure instanceof Exception) throw (Exception) failure;
                throw new IllegalStateException("ST API linkage failed", failure);
            }
            report.notes.add("ST candidate failed before writing or restored its snapshot; fallback permitted.");
            return false;
        }
    }

    /** Resolves the optional library and Binder-backed adapter; never calls an NFC setter. */
    static Probe probe(Context context) {
        return probe(context, false);
    }

    static Probe probeRawMode(Context context) {
        return probe(context, true);
    }

    private static Probe probe(Context context, boolean rawMode) {
        if (!(rawMode ? isRawModeTarget() : isTargetDevice())) return new Probe(false, false, null);
        try {
            resolveApi(context, rawMode);
            return new Probe(true, true, null);
        } catch (Exception failure) {
            return new Probe(true, false, describe(failure));
        } catch (LinkageError failure) {
            return new Probe(true, false, describe(failure));
        }
    }

    static String rawModeStatus(Context context, boolean detailed) {
        Probe probe = probeRawMode(context);
        String result = "Motorola ST raw path: " + (!probe.targeted ? "not targeted"
                : probe.available ? "available (experimental, raw mode only)" : "unavailable");
        if (probe.targeted) result += "\nST identification: model/API match; controller chip unverified";
        if (detailed && probe.failure != null) result += "\nMotorola ST raw probe failure: " + probe.failure;
        return result;
    }

    static void setCardEnabled(Context context, byte[] uid, boolean enabled,
            OemOperationReport report) throws Exception {
        setCardEnabled(context, uid, enabled, report, false);
    }

    static void setRawCardEnabled(Context context, byte[] uid, boolean enabled,
            OemOperationReport report) throws Exception {
        setCardEnabled(context, uid, enabled, report, true);
    }

    private static void setCardEnabled(Context context, byte[] uid, boolean enabled,
            OemOperationReport report, boolean rawMode) throws Exception {
        report.path = OemOperationReport.Path.MOTOROLA_ST_VENDOR_HCE;
        report.profile = "cts-type-4a / experimental Motorola Boston ST HCE";
        report.atqa = new byte[] {0x06, 0x0C};
        report.sak = new byte[] {0x20};
        report.uid = OemOperationReport.copy(uid);
        report.notes.add("ST programHceParameters returns void; raw GET_CONFIG supplies readback. "
                + "This ST transaction sends no direct raw SET_CONFIG; fallback requires a clean restore state.");

        if (enabled && !(rawMode ? isRawModeTarget() : isTargetDevice())) {
            throw new IllegalStateException("Motorola ST activation is disabled or the device is not targeted");
        }

        Store store = new PreferenceStore(context);
        // Opening the app must not reset the ST service when this app has changed nothing.
        if (!enabled && store.load() == null) {
            report.result = "NO_PENDING_ST_RESTORE";
            report.stage = "COMPLETE";
            return;
        }
        report.stage = "RESOLVE_MOTOROLA_ST_API";
        Api api = resolveApi(context, rawMode);
        change(api, store, Build.FINGERPRINT, uid, enabled, report);
    }

    static void change(Api api, Store store, String firmware, byte[] uid, boolean enabled,
            OemOperationReport report) throws Exception {
        if (uid == null || !UidCodec.isSupportedLength(uid.length)) {
            throw new IllegalArgumentException("UID must contain 4, 7, or 10 bytes");
        }
        Snapshot snapshot = store.load();
        if (snapshot != null && !snapshot.firmware.equals(firmware)) {
            throw new IllegalStateException("Pending ST restore belongs to another firmware");
        }
        if (!enabled) {
            if (snapshot == null) {
                report.result = "NO_PENDING_ST_RESTORE";
                report.stage = "COMPLETE";
                return;
            }
            restore(api, store, snapshot, report);
            return;
        }

        report.stage = "SNAPSHOT_ORIGINAL_ST_CONFIG";
        if (snapshot == null) {
            Map<Integer, byte[]> original = api.readConfiguration(report);
            validateConfiguration(original);
            // The vendor disable operation restores default routing/NDEF-NFCEE. Do not
            // replace a pre-existing custom RF configuration belonging to another app.
            if (!Arrays.equals(new byte[] {1}, original.get(0x85))) {
                throw new IllegalStateException("ST controller already has custom RF configuration");
            }
            snapshot = new Snapshot(firmware, original);
            store.save(snapshot); // Durable before the first vendor write.
            report.snapshotState = "CAPTURED";
        } else {
            validateConfiguration(snapshot.configuration);
            report.snapshotState = "REUSED_PENDING";
        }

        try {
            report.stage = "ST_PROGRAM_HCE_PARAMETERS";
            api.program(true, Arrays.copyOf(uid, uid.length));
            report.vendorCallSucceeded = true;
            report.stage = "VERIFY_ST_CONFIG";
            Map<Integer, byte[]> actual = api.readConfiguration(report);
            Map<Integer, byte[]> expected = expectedConfiguration(uid);
            requireMatches(expected, actual);
            report.result = "ST_CONFIG_READBACK_VERIFIED_READER_UNVERIFIED";
            report.stage = "COMPLETE";
        } catch (Exception failure) {
            report.failure = describe(failure);
            if (report.vendorCallSucceeded == null) report.vendorCallSucceeded = false;
            report.rollbackAttempted = true;
            try {
                OemOperationReport rollback = new OemOperationReport(
                        OemOperationReport.Operation.DEACTIVATE);
                rollback.path = OemOperationReport.Path.MOTOROLA_ST_VENDOR_HCE;
                report.stRollback = rollback;
                restore(api, store, snapshot, rollback);
                report.rollbackSucceeded = true;
            } catch (Exception rollbackFailure) {
                report.rollbackSucceeded = false;
                report.rollbackFailure = describe(rollbackFailure);
                report.stRollback.failure = report.rollbackFailure;
                report.stRollback.stage = "FAILED";
                failure.addSuppressed(rollbackFailure);
            }
            report.stage = "FAILED";
            throw failure;
        }
    }

    private static void restore(Api api, Store store, Snapshot snapshot,
            OemOperationReport report) throws Exception {
        validateConfiguration(snapshot.configuration);
        report.snapshotState = "RESTORE_PENDING";
        report.stage = "ST_RESTORE_VENDOR_DEFAULTS";
        api.program(false, new byte[UidCodec.DEFAULT_UID_LENGTH]);
        report.vendorCallSucceeded = true;
        report.notes.add("Vendor disable resets custom_config and requests default routing/NDEF-NFCEE. "
                + "Exact readback below covers the seven standard NCI parameters only.");
        report.stage = "ST_RESTORE_ORIGINAL_NCI_CONFIG";
        // ST's service coordinates discovery and updates its stack cache for these setters.
        // NFCC_CONFIG_CONTROL is restored last, after all RF parameters.
        for (int id : PARAMETERS) {
            byte[] value = snapshot.configuration.get(id);
            report.notes.add(String.format(java.util.Locale.US,
                    "ST setNciConfig(0x%02X): %s; controller status unavailable from vendor API",
                    id, RawNciCodec.hex(value)));
            api.setConfiguration(id, Arrays.copyOf(value, value.length));
        }
        report.stage = "VERIFY_ST_RESTORE";
        requireMatches(snapshot.configuration, api.readConfiguration(report));
        store.clear(); // Keep the snapshot on every failure, including a silent vendor no-op.
        report.snapshotState = "RESTORED_AND_CLEARED";
        report.result = "ST_ORIGINAL_NCI_CONFIG_VERIFIED";
        report.stage = "COMPLETE";
    }

    private static Map<Integer, byte[]> expectedConfiguration(byte[] uid) {
        Map<Integer, byte[]> values = new LinkedHashMap<>();
        values.put(0x30, new byte[] {0x06});
        values.put(0x31, new byte[] {0x0C});
        values.put(0x32, new byte[] {0x20});
        values.put(0x33, Arrays.copyOf(uid, uid.length));
        values.put(0x58, new byte[] {0x40});
        values.put(0x59, new byte[0]);
        values.put(0x85, new byte[] {0});
        return values;
    }

    private static void validateConfiguration(Map<Integer, byte[]> values) {
        for (int id : PARAMETERS) {
            byte[] value = values.get(id);
            if (value == null || (id != 0x33 && id != 0x59 && value.length != 1)
                    || (id == 0x33 && value.length != 0
                    && !UidCodec.isSupportedLength(value.length))) {
                throw new IllegalStateException("Incomplete or invalid ST configuration snapshot");
            }
        }
    }

    private static void requireMatches(Map<Integer, byte[]> expected,
            Map<Integer, byte[]> actual) {
        for (int id : PARAMETERS) {
            if (!Arrays.equals(expected.get(id), actual.get(id))) {
                throw new IllegalStateException(String.format(java.util.Locale.US,
                        "ST readback mismatch at 0x%02X: expected %s, read %s", id,
                        RawNciCodec.hex(expected.get(id)), RawNciCodec.hex(actual.get(id))));
            }
        }
    }

    private static Api resolveApi(Context context, boolean rawMode) throws Exception {
        if (!isApiAllowed(BuildConfig.MOTOROLA_ST_EXPERIMENT, hasPendingRestore(context),
                Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL, Build.DEVICE)
                && !(rawMode && isRawModeTarget())) {
            throw new IllegalStateException("Motorola ST API is not allowed on this device");
        }
        Class<?> type = Class.forName(ADAPTER, false, context.getClassLoader());
        Method program = type.getMethod("programHceParameters", boolean.class,
                byte.class, byte.class, byte.class, byte[].class, byte.class, byte[].class);
        Method set = type.getMethod("setNciConfig", int.class, byte[].class);
        if (program.getReturnType() != void.class || set.getReturnType() != void.class) {
            throw new NoSuchMethodException("Unexpected ST NFC API signature");
        }
        Object adapter = type.getConstructor().newInstance();
        if (!LegacyNfcDiagnostics.isRawNciApiAvailable(context)) {
            throw new IllegalStateException("Raw GET_CONFIG is required to verify ST operations");
        }
        return new Api() {
            @Override public void program(boolean enabled, byte[] uid) throws Exception {
                invoke(program, adapter, enabled, (byte) 0x06, (byte) 0x0C,
                        (byte) 0x20, uid, (byte) 0x40, new byte[0]);
            }
            @Override public void setConfiguration(int id, byte[] value) throws Exception {
                invoke(set, adapter, id, value);
            }
            @Override public Map<Integer, byte[]> readConfiguration(OemOperationReport report)
                    throws Exception {
                LegacyNfcDiagnostics.ProbeResult result =
                        LegacyNfcDiagnostics.runReadOnlyProbe(context);
                report.notes.add(report.stage + "\n" + result.detailedSummary);
                return result.configuration;
            }
        };
    }

    private static void invoke(Method method, Object receiver, Object... args) throws Exception {
        try {
            method.invoke(receiver, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("ST vendor API failed", cause);
        }
    }

    private static String describe(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    interface Api {
        void program(boolean enabled, byte[] uid) throws Exception;
        void setConfiguration(int id, byte[] value) throws Exception;
        Map<Integer, byte[]> readConfiguration(OemOperationReport report) throws Exception;
    }

    interface Store {
        Snapshot load();
        void save(Snapshot snapshot);
        void clear();
    }

    static final class Snapshot {
        final String firmware;
        final Map<Integer, byte[]> configuration;
        Snapshot(String firmware, Map<Integer, byte[]> configuration) {
            this.firmware = firmware;
            this.configuration = RawNciCodec.decodeConfiguration(
                    RawNciCodec.encodeConfiguration(configuration));
        }
    }

    static final class Probe {
        final boolean targeted;
        final boolean available;
        final String failure;
        Probe(boolean targeted, boolean available, String failure) {
            this.targeted = targeted;
            this.available = available;
            this.failure = failure;
        }
    }

    private static final class PreferenceStore implements Store {
        private final SharedPreferences preferences;
        PreferenceStore(Context context) {
            preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
        @Override public Snapshot load() {
            if (!preferences.getBoolean("pending", false)) return null;
            if (preferences.getInt("version", 0) != 1) {
                throw new IllegalStateException("Unsupported ST restore snapshot version");
            }
            return new Snapshot(preferences.getString("firmware", ""),
                    RawNciCodec.decodeConfiguration(preferences.getString("config", null)));
        }
        @Override public void save(Snapshot snapshot) {
            if (!preferences.edit().putInt("version", 1).putBoolean("pending", true)
                    .putString("firmware", snapshot.firmware)
                    .putString("config", RawNciCodec.encodeConfiguration(snapshot.configuration))
                    .commit()) {
                throw new IllegalStateException("Could not persist original ST configuration");
            }
        }
        @Override public void clear() {
            if (!preferences.edit().clear().commit()) {
                throw new IllegalStateException("Could not clear the ST restore snapshot");
            }
        }
    }
}
