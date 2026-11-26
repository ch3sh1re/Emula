package dev.dunno.emula;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.nfc.NfcAdapter;
import android.os.Build;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** OPPO NXP RF candidate, selected only by the existing consented raw path. */
@SuppressLint("PrivateApi")
final class OppoNxpRawController {
    static final String PREFS = "oppo_nxp_rf_restore";
    static final String PLE_FIRMWARE = "OPPO/PLE110/OP5E03L1:16/BP2A.250605.015/"
            + "V.567a4f5-30d58c9-3120268:user/release-keys";
    static final String CPH_RU_FIRMWARE = "OPPO/CPH2865RU/OP6307L1:16/BP2A.250605.015/"
            + "B.2a4e8cb-48d166-4af2fb:user/release-keys";
    static final String CPH_EEA_FIRMWARE = "OPPO/CPH2865EEA/OP6307L1:16/BP2A.250605.015/"
            + "B.R5T2.4f4bbc8-2a13891-2a2a487:user/release-keys";
    static final String PLE_APK = "016b97a36a6366af7881c287ae6c11712525f7cae24d13483c0b37fae06d882a";
    static final String CPH_APK = "e9c72611e850a8f323a7f47252b6360c91370573ed509b9476c80f95013a8b20";
    static final String PLE_JNI = "caa834e033abba97b14307a56bf1119cc09d247d6444af935c10b01aa0feaa13";
    static final String CPH_JNI = "ea597517d68014e8a0748e3c3077f35e285f2feb5a7e12e8444634314818b670";
    private static final int[] IDS = {0x30, 0x31, 0x32, 0x33, 0x58, 0x59, 0x85};
    private static final String NXP = "com.nxp.nfc.NxpNfcAdapter";
    private static volatile String selection = "not checked";

    private OppoNxpRawController() {}

    static boolean targets(int sdk, String manufacturer, String model, String firmware) {
        if (!"OPPO".equalsIgnoreCase(manufacturer)) return false;
        // CPH2865 uses runtime API/stack checks across regions and subsequent builds.
        if ("CPH2865".equalsIgnoreCase(model)) return sdk >= 36;
        return sdk == 36 && "PLE110".equalsIgnoreCase(model) && PLE_FIRMWARE.equals(firmware);
    }

    static boolean targets() {
        return targets(Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL, Build.FINGERPRINT);
    }

    static boolean reviewed(String model, String apk, String jni, String chip) {
        return supportedChip(chip) && (("PLE110".equalsIgnoreCase(model) && PLE_APK.equals(apk) && PLE_JNI.equals(jni))
                || ("CPH2865".equalsIgnoreCase(model) && CPH_APK.equals(apk) && CPH_JNI.equals(jni)));
    }

    static boolean acceptsStack(String model, String apk, String jni, String chip) {
        if ("CPH2865".equalsIgnoreCase(model)) {
            // Hashes identify this transaction's recovery baseline, not an allowed-build list.
            return supportedChip(chip) && completeHash(apk) && completeHash(jni);
        }
        return reviewed(model, apk, jni, chip);
    }

    private static boolean supportedChip(String chip) {
        // The inspected service dispatches these IDs to the SN-family native implementation.
        return Arrays.asList("SN100T", "SN100U", "SN110T", "PN560", "SN220T",
                "SN220F", "SN220P", "SN220U", "SN300S").contains(chip);
    }

    private static boolean completeHash(String hash) {
        return hash != null && hash.matches("[0-9a-f]{64}");
    }

    static boolean hasPendingRestore(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("pending", false);
    }

    static String diagnosticSummary(Context context) {
        if (!targets() && !hasPendingRestore(context)) return "";
        String scope = "CPH2865".equalsIgnoreCase(Build.MODEL)
                ? "CPH2865; all regions/builds; runtime API/chip and recovery identity required"
                : "scoped; reviewed APK/JNI/chip required";
        return "\nOPPO NXP RF candidate: " + (targets() ? scope : "not targeted")
                + "\nPending OPPO NXP RF restore: " + (hasPendingRestore(context) ? "yes" : "no")
                + "\nOPPO NXP RF selection: " + selection
                + "\nOPPO NXP RF writes: UID only, changeRfParams(lastCMD=false); reader unverified\n";
    }

    /** Worker-only, read-only selection. An unavailable candidate preserves the old raw path. */
    static boolean available(Context context) {
        if (!targets()) return false;
        return available(new AndroidPlatform(context));
    }

    static boolean available(Platform platform) {
        try {
            platform.identity(new RawOperationReport(RawOperationReport.Operation.ACTIVATE));
            selection = "device/stack policy accepted and NXP API resolved; writes not yet verified";
            return true;
        } catch (Exception | LinkageError failure) {
            selection = "unavailable; existing generic raw path retained: " + failure;
            return false;
        }
    }

    static void change(Context context, byte[] uid, RawOperationReport.Operation operation) throws Exception {
        RawOperationReport report = new RawOperationReport(operation);
        report.candidate = RawOperationReport.Candidate.UID_ONLY;
        report.pollingMethod = "OPPO_NXP_NATIVE_RF_DISCOVERY";
        LegacyNfcController.recordOperation(report);
        change(new AndroidPlatform(context), uid, report);
    }

    interface Platform {
        String identity(RawOperationReport report) throws Exception;
        Snapshot load();
        void save(Snapshot snapshot);
        void clear();
        Map<Integer, byte[]> read(RawOperationReport report) throws Exception;
        int write(byte[] command, boolean lastCommand) throws Exception;
        void settle() throws Exception;
    }

    static final class Snapshot {
        final String identity;
        final Map<Integer, byte[]> configuration;
        Snapshot(String identity, Map<Integer, byte[]> configuration) {
            if (identity == null || identity.isEmpty()) throw new IllegalStateException("Missing OPPO firmware identity");
            requireComplete(configuration);
            this.identity = identity;
            this.configuration = copy(configuration);
        }
    }

    static void change(Platform platform, byte[] uid, RawOperationReport report) throws Exception {
        boolean invoked = false;
        Snapshot original = null;
        try {
            report.stage = "CHECK_OPPO_RF_SCOPE";
            if (uid != null && !UidCodec.isSupportedLength(uid.length)) {
                throw new IllegalArgumentException("UID must contain 4, 7, or 10 bytes");
            }
            original = platform.load();
            if (uid == null && original == null) {
                report.result = "NO_PENDING_OPPO_RESTORE";
                report.stage = "COMPLETE";
                return;
            }
            String identity = platform.identity(report);
            if (original != null && !identity.equals(original.identity)) {
                throw new IllegalStateException("OPPO firmware/stack changed; original recovery record retained");
            }
            report.stage = "SNAPSHOT_ORIGINAL_CONFIG";
            Map<Integer, byte[]> current = platform.read(report);
            requireComplete(current);
            report.uidBefore = OemOperationReport.copy(current.get(0x33));
            if (uid == null) {
                restore(platform, original, current, report);
                return;
            }
            if (original == null) {
                original = new Snapshot(identity, current);
                platform.save(original);
            } else if (!sameExceptUid(original.configuration, current)) {
                throw new IllegalStateException("Non-UID NFC parameters changed; OPPO recovery retained");
            }
            report.stage = "OPPO_NXP_SET_UID";
            invoked = true;
            write(platform, uid, report);
            platform.settle();
            report.stage = "VERIFY_UID_AFTER_NATIVE_RF_RESUME";
            Map<Integer, byte[]> after = platform.read(report);
            requireComplete(after);
            report.uidAfterResume = OemOperationReport.copy(after.get(0x33));
            if (!Arrays.equals(uid, after.get(0x33)) || !sameExceptUid(original.configuration, after)) {
                report.result = "UID_READBACK_MISMATCH";
                throw new IllegalStateException("OPPO UID/readback differs after native RF resume");
            }
            report.result = "UID_ONLY_VERIFIED";
            report.stage = "COMPLETE";
        } catch (Exception failure) {
            String failedStage = report.stage;
            report.primaryFailure = describe(failure);
            report.stage = "FAILED";
            report.notes.add("Failure stage: " + failedStage);
            if (!invoked && report.requests.stream().noneMatch(r -> "SET_CONFIG".equals(r.command))) {
                report.result = "PREPARATION_FAILED";
            }
            if (invoked && original != null) {
                RawOperationReport rollback = new RawOperationReport(RawOperationReport.Operation.ROLLBACK_AFTER_ACTIVATION_FAILURE);
                rollback.candidate = RawOperationReport.Candidate.UID_ONLY;
                rollback.pollingMethod = report.pollingMethod;
                report.rollback = rollback;
                try {
                    // A failed/timeout read cannot be reused as a restoration snapshot.
                    restore(platform, original, platform.read(rollback), rollback);
                } catch (Exception recovery) {
                    rollback.stage = "FAILED";
                    rollback.primaryFailure = describe(recovery);
                    report.rollbackFailure = describe(recovery);
                    failure.addSuppressed(recovery);
                }
            }
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw failure;
        }
    }

    private static void restore(Platform platform, Snapshot original, Map<Integer, byte[]> current,
            RawOperationReport report) throws Exception {
        requireComplete(current);
        if (!sameExceptUid(original.configuration, current)) {
            throw new IllegalStateException("Non-UID NFC parameters differ; no unrelated settings will be overwritten");
        }
        boolean same = Arrays.equals(original.configuration.get(0x33), current.get(0x33));
        if (!same) {
            report.stage = "RESTORE_OPPO_UID";
            write(platform, original.configuration.get(0x33), report);
            platform.settle();
            report.stage = "VERIFY_OPPO_ORIGINAL_CONFIG";
            Map<Integer, byte[]> after = platform.read(report);
            requireComplete(after);
            if (!sameExceptUid(original.configuration, after)
                    || !Arrays.equals(original.configuration.get(0x33), after.get(0x33))) {
                throw new IllegalStateException("Original OPPO configuration not restored exactly");
            }
        }
        platform.clear();
        report.result = same ? "ORIGINAL_CONFIG_ALREADY_PRESENT" : "ORIGINAL_CONFIG_RESTORED";
        report.stage = "COMPLETE";
    }

    private static void write(Platform platform, byte[] uid, RawOperationReport report) throws Exception {
        byte[] command = uidCommand(uid);
        RawOperationReport.RequestRecord request = report.addRequest(report.operation,
                RawOperationReport.Candidate.UID_ONLY, report.stage, "SET_CONFIG", command);
        request.transport = "NXP_CHANGE_RF_PARAMS (lastCMD=false; full NCI frame)";
        long started = System.nanoTime();
        try {
            request.vendorStatus = platform.write(command, false);
            if (request.vendorStatus != 0) {
                throw new IllegalStateException("NXP changeRfParams returned " + request.vendorStatus);
            }
        } catch (Exception failure) {
            request.failure = describe(failure);
            throw failure;
        } finally {
            request.durationMillis = (System.nanoTime() - started) / 1_000_000;
        }
    }

    static byte[] uidCommand(byte[] uid) {
        if (uid == null || (uid.length != 0 && !UidCodec.isSupportedLength(uid.length))) {
            throw new IllegalArgumentException("Invalid saved/requested NFCID1 length");
        }
        byte[] frame = new byte[6 + uid.length];
        frame[0] = 0x20;
        frame[1] = 0x02;
        frame[2] = (byte) (3 + uid.length);
        frame[3] = 1;
        frame[4] = 0x33;
        frame[5] = (byte) uid.length;
        System.arraycopy(uid, 0, frame, 6, uid.length);
        return frame;
    }

    static void requireComplete(Map<Integer, byte[]> values) {
        if (values == null) throw new IllegalStateException("Missing OPPO NFC snapshot");
        for (int id : IDS) {
            byte[] value = values.get(id);
            if (value == null || (id == 0x33 ? value.length != 0 && !UidCodec.isSupportedLength(value.length)
                    : id == 0x59 ? value.length > 48 : value.length != 1)) {
                throw new IllegalStateException("Invalid/missing OPPO snapshot parameter 0x" + Integer.toHexString(id));
            }
        }
    }

    private static boolean sameExceptUid(Map<Integer, byte[]> a, Map<Integer, byte[]> b) {
        for (int id : IDS) if (id != 0x33 && !Arrays.equals(a.get(id), b.get(id))) return false;
        return true;
    }

    private static Map<Integer, byte[]> copy(Map<Integer, byte[]> original) {
        Map<Integer, byte[]> copy = new LinkedHashMap<>();
        for (int id : IDS) copy.put(id, original.get(id).clone());
        return copy;
    }

    static final class Storage {
        private final SharedPreferences preferences;
        Storage(SharedPreferences preferences) { this.preferences = preferences; }
        Snapshot load() {
            if (!preferences.getBoolean("pending", false)) return null;
            if (preferences.getInt("version", 0) != 1) throw new IllegalStateException("Unknown OPPO snapshot version");
            return new Snapshot(preferences.getString("identity", null),
                    RawNciCodec.decodeConfiguration(preferences.getString("configuration", null)));
        }
        void save(Snapshot snapshot) {
            if (!preferences.edit().putBoolean("pending", true).putInt("version", 1)
                    .putString("identity", snapshot.identity)
                    .putString("configuration", RawNciCodec.encodeConfiguration(snapshot.configuration)).commit()) {
                throw new IllegalStateException("Could not persist OPPO recovery state; no activation allowed");
            }
        }
        @SuppressLint("ApplySharedPref") // Recovery must be durable; apply() cannot report a failed commit.
        void clear() {
            if (!preferences.edit().putBoolean("pending", false).commit()) {
                // SharedPreferences may update its memory map even when disk commit fails.
                preferences.edit().putBoolean("pending", true).commit();
                throw new IllegalStateException("Could not clear OPPO recovery marker");
            }
        }
    }

    private static final class AndroidPlatform implements Platform {
        private final Context context;
        private final Storage store;
        private Object adapter;
        private Method writer;
        AndroidPlatform(Context context) {
            this.context = context.getApplicationContext();
            store = new Storage(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
        }
        public Snapshot load() { return store.load(); }
        public void save(Snapshot snapshot) { store.save(snapshot); }
        public void clear() { store.clear(); }
        public String identity(RawOperationReport report) throws Exception {
            if (!targets()) throw new IllegalStateException("OPPO RF candidate does not target this device/build");
            ApplicationInfo info = context.getPackageManager().getApplicationInfo("com.android.nfc", 0);
            if (info.splitSourceDirs != null && info.splitSourceDirs.length != 0) {
                throw new IllegalStateException("Split OPPO NFC service has not been reviewed");
            }
            NfcFileEvidence files = new NfcFileEvidence();
            NfcFileEvidence.Result apk = files.inspect(new File(info.sourceDir));
            NfcFileEvidence.Result jni = files.inspect(new File("/system/lib64/libsn100nfc_nci.so"));
            report.notes.add("OPPO NFC APK: " + apk.description);
            report.notes.add("OPPO SN-family JNI: " + jni.description);
            Class<?> vendor = Class.forName("com.vendor.nfc.VendorNfcAdapter", false, context.getClassLoader());
            Object vendorAdapter = invoke(vendor.getMethod("getVendorNfcAdapter"), null);
            if (vendorAdapter == null) throw new IllegalStateException("OPPO vendor adapter unavailable");
            Object chip = invoke(vendor.getMethod("getNfcc"), vendorAdapter);
            report.notes.add("OPPO active chip: " + chip);
            String chipName = Objects.toString(chip, "");
            if ("CPH2865".equalsIgnoreCase(Build.MODEL)) {
                report.notes.add("Reference APK/JNI match: "
                        + (reviewed(Build.MODEL, apk.sha256, jni.sha256, chipName) ? "yes" : "no"));
            }
            if (!acceptsStack(Build.MODEL, apk.sha256, jni.sha256, chipName)) {
                throw new IllegalStateException("CPH2865".equalsIgnoreCase(Build.MODEL)
                        ? "OPPO RF candidate needs a supported NXP chip and complete current APK/JNI hashes for recovery"
                        : "OPPO APK/JNI/chip does not match the reviewed RF implementation");
            }
            Class<?> type = Class.forName(NXP, false, context.getClassLoader());
            writer = type.getMethod("changeRfParams", byte[].class, boolean.class);
            if (writer.getReturnType() != int.class) throw new NoSuchMethodException("Unexpected changeRfParams result type");
            adapter = invoke(type.getMethod("getNxpNfcAdapter", NfcAdapter.class), null,
                    NfcAdapter.getDefaultAdapter(context));
            if (adapter == null) throw new IllegalStateException("OPPO NXP Binder unavailable");
            return Build.FINGERPRINT + "\n" + apk.sha256 + "\n" + jni.sha256 + "\n" + chip;
        }
        public Map<Integer, byte[]> read(RawOperationReport report) throws Exception {
            LegacyNfcDiagnostics.ProbeResult result = LegacyNfcDiagnostics.runReadOnlyProbe(context);
            report.notes.add(report.stage + "\n" + result.detailedSummary);
            return result.configuration;
        }
        public int write(byte[] command, boolean lastCommand) throws Exception {
            Object value = invoke(writer, adapter, command.clone(), lastCommand);
            if (!(value instanceof Integer)) throw new IllegalStateException("Unexpected NXP RF response");
            return (Integer) value;
        }
        public void settle() throws InterruptedException { Thread.sleep(500); }
    }

    private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("OPPO vendor invocation failed", cause);
        }
    }

    private static String describe(Exception failure) {
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }
}
