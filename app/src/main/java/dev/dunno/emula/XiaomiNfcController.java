package dev.dunno.emula;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Map;

/** Model-scoped access to the NXP transit-config API. Phone only. */
@SuppressLint("PrivateApi")
final class XiaomiNfcController {
    static final String KNOWN_BASELINE_FINGERPRINT =
            "Redmi/garnet_global/garnet:15/AQ3A.240912.001/OS2.0.206.0.VNRMIXM:user/release-keys";
    private static final String PREFS = "xiaomi_nxp_transit";
    private static final int RESTORE_VERSION = 2;
    private static final VendorCallGate CALLS = new VendorCallGate();
    private static volatile boolean restarting;

    private XiaomiNfcController() {}

    static boolean isTargetDevice() {
        return isTargetDevice(Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL,
                Build.DEVICE, Build.PRODUCT);
    }

    static boolean isTargetDevice(int sdk, String manufacturer, String model,
            String device, String product) {
        if (sdk < 35 || !"Xiaomi".equalsIgnoreCase(manufacturer)) return false;
        return ("2312DRA50G".equals(model) && "garnet".equals(device)
                && "garnet_global".equals(product))
                || ("22081212UG".equals(model) && "diting".equals(device)
                && "ditingp_global".equals(product))
                || isTanzaniteDevice(sdk, manufacturer, model, device, product);
    }

    static boolean isTanzaniteDevice(int sdk, String manufacturer, String model,
            String device, String product) {
        return sdk >= 35 && "Xiaomi".equalsIgnoreCase(manufacturer)
                && "24117RN76E".equals(model) && "tanzanite".equals(device)
                && "tanzanite_eea".equals(product);
    }

    private static boolean isTanzaniteDevice() {
        return isTanzaniteDevice(Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL,
                Build.DEVICE, Build.PRODUCT);
    }

    static boolean permitsRawFallback(boolean tanzanite, boolean activating, boolean pending,
            boolean vendorIdle, OemOperationReport report) {
        return tanzanite && activating && !pending && vendorIdle && report != null
                && report.path == OemOperationReport.Path.XIAOMI_NXP_TRANSIT
                && report.transitCalls.isEmpty();
    }

    static boolean permitsRawFallback(Context context, boolean activating, OemOperationReport report) {
        return permitsRawFallback(isTanzaniteDevice(), activating, hasPendingRestore(context),
                CALLS.isIdle() && !Thread.currentThread().isInterrupted(), report);
    }

    static boolean hasPendingRestore(Context context) {
        return preferences(context).getBoolean("pending", false);
    }

    /** No setters. Read chip identity and firmware through the actual vendor services. */
    static String capabilities(Context context, boolean includeProbe) {
        if (!isTargetDevice() && !hasPendingRestore(context)) return "Xiaomi NXP trial: not targeted";
        StringBuilder text = new StringBuilder("Xiaomi NXP trial: ")
                .append(isTargetDevice() ? "targeted (experimental)" : "firmware changed")
                .append("\nXiaomi reset pending: ").append(hasPendingRestore(context) ? "yes" : "no");
        if (!includeProbe) return text.toString();
        try {
            Api api = resolveApi(context);
            text.append("\nXiaomi chip ID: ").append(api.chipId)
                    .append("\nNXP firmware bytes: ").append(api.firmware)
                    .append("\nNXP setConfig: resolved");
            Recovery pending = store(context).load();
            XiaomiNxpConfig.Plan plan = api.prepare(new byte[UidCodec.DEFAULT_UID_LENGTH],
                    pending == null ? null : pending.reset); // Read only.
            text.append("\nXiaomi base configuration: ").append(api.configurationSource())
                    .append("\nXiaomi config carrier: ").append(plan.carrier);
        } catch (Throwable error) {
            text.append("\nXiaomi probe: ").append(describe(error));
        }
        return text.toString();
    }

    static void change(Context context, byte[] uid, OemOperationReport.Operation operation)
            throws Exception {
        OemOperationReport report = new OemOperationReport(operation);
        report.path = OemOperationReport.Path.XIAOMI_NXP_TRANSIT;
        OemNfcController.recordOperation(report);
        Store store = store(context);
        try {
            report.stage = "CHECK_DEVICE";
            if (!isTargetDevice()) throw new IllegalStateException("Xiaomi NFC path is not enabled for this device");
            // Startup without our pending marker must never delete another app's override.
            if (uid == null && store.load() == null) {
                report.result = "NO_PENDING_XIAOMI_RESET";
                report.stage = "COMPLETE";
                return;
            }
            report.stage = "RESOLVE_XIAOMI_NXP";
            Api api = resolveApi(context);
            report.chipId = api.chipId;
            report.vendorFirmware = api.firmware;
            transact(api, store, Build.FINGERPRINT, uid, report);
        } catch (Throwable error) {
            report.failure = describe(error);
            report.stage = "FAILED";
            throw asException(error);
        }
    }

    interface ConfigApi {
        XiaomiNxpConfig.Plan prepare(byte[] uid) throws Exception;
        default XiaomiNxpConfig.Plan prepare(byte[] uid, String savedReset) throws Exception { return prepare(uid); }
        default boolean usesLiveSnapshot() { return false; }
        default void verifyConfiguration(String config, OemOperationReport report) throws Exception {}
        default String configurationSource() { return "not reported"; }
        default String preparationEvidence() { return null; }
        int setConfig(String value) throws Exception;
        boolean nfcReady() throws Exception;
    }

    interface Store {
        Recovery load();
        void save(Recovery recovery);
        void clear();
    }

    static final class Recovery {
        final String fingerprint;
        final String reset;
        final boolean legacy;

        Recovery(String fingerprint, String reset, boolean legacy) {
            this.fingerprint = fingerprint;
            this.reset = reset;
            this.legacy = legacy;
        }
    }

    /** Transaction logic is exercised with a fake service; no raw commands or fallback. */
    static void transact(ConfigApi api, Store store, String fingerprint, byte[] uid,
            OemOperationReport report) throws Exception {
        report.path = OemOperationReport.Path.XIAOMI_NXP_TRANSIT;
        report.profile = "Xiaomi / NXP transit config (experimental)";
        report.notes.add(api.usesLiveSnapshot()
                ? "Seven standard NCI parameters are snapshotted; the proprietary transit file is not backed up."
                : "Factory reset replaces prior transit overrides; no original-file snapshot is available.");
        report.notes.add("A vendor acknowledgement does not verify RF UID, file deletion, payment, or transit cards.");
        Recovery pending = store.load();
        if (pending != null && !fingerprint.equals(pending.fingerprint)) {
            throw new IllegalStateException("Xiaomi reset belongs to a different firmware; recovery marker retained");
        }
        if (pending != null && pending.legacy && api.usesLiveSnapshot()) {
            throw new IllegalStateException("Tanzanite recovery has no saved NCI snapshot");
        }
        if (uid == null) {
            if (pending == null) {
                report.result = "NO_PENDING_XIAOMI_RESET";
                report.stage = "COMPLETE";
                return;
            }
            reset(api, store, report);
            return;
        }
        report.stage = "READ_FIRMWARE_CORE_CONFIG";
        XiaomiNxpConfig.Plan plan = api.prepare(uid, pending == null ? null : pending.reset);
        if (pending != null && !pending.legacy && !java.util.Objects.equals(pending.reset, plan.reset)) {
            throw new IllegalStateException("Xiaomi configuration changed; stop the previous session first");
        }
        report.notes.add("Baseline configuration: " + api.configurationSource());
        if (api.preparationEvidence() != null) report.notes.add(api.preparationEvidence());
        if (api.usesLiveSnapshot()) report.notes.add("Saved NCI restoration configuration: " + plan.reset);
        report.notes.add("Configuration carrier: " + plan.carrier);
        report.uid = OemOperationReport.copy(uid);
        report.stage = "SAVE_PENDING_XIAOMI_RESET";
        store.save(new Recovery(fingerprint, plan.reset, false)); // Durable before either writer.
        report.snapshotState = api.usesLiveSnapshot() ? "NCI_SNAPSHOT_PENDING"
                : "FACTORY_RESET_PENDING (not an original-configuration snapshot)";
        try {
            writeConfig(api, report, "XIAOMI_SET_TRANSIT_CONFIG", plan.activation);
            if (api.usesLiveSnapshot()) {
                report.stage = "XIAOMI_VERIFY_CONFIG";
                api.verifyConfiguration(plan.activation, report);
            }
            report.result = api.usesLiveSnapshot() ? "NCI_CONFIG_READBACK_VERIFIED_READER_UNVERIFIED"
                    : "VENDOR_CONFIG_ACCEPTED_READER_UNVERIFIED";
            report.stage = "COMPLETE";
        } catch (Throwable primary) {
            report.failure = describe(primary);
            report.rollbackAttempted = true;
            try {
                OemOperationReport rollback = new OemOperationReport(OemOperationReport.Operation.DEACTIVATE);
                rollback.path = OemOperationReport.Path.XIAOMI_NXP_TRANSIT;
                report.vendorRollback = rollback;
                reset(api, store, rollback);
                report.rollbackSucceeded = true;
            } catch (Throwable rollback) {
                report.rollbackSucceeded = false;
                report.rollbackFailure = describe(rollback);
                if (report.vendorRollback != null) {
                    report.vendorRollback.failure = report.rollbackFailure;
                    report.vendorRollback.stage = "FAILED";
                }
            }
            report.stage = "FAILED";
            throw asException(primary);
        }
    }

    private static void reset(ConfigApi api, Store store, OemOperationReport report) throws Exception {
        report.stage = api.usesLiveSnapshot() ? "XIAOMI_PREPARE_NCI_RESTORE" : "XIAOMI_PREPARE_FACTORY_RESET";
        report.snapshotState = api.usesLiveSnapshot() ? "NCI_RESTORE_PENDING" : "FACTORY_RESET_PENDING";
        Recovery recovery = store.load();
        if (recovery == null) throw new IllegalStateException("Missing Xiaomi recovery state");
        if (recovery.legacy) {
            if (api.usesLiveSnapshot()) throw new IllegalStateException("Tanzanite recovery has no saved NCI snapshot");
            // Upgrade build 35's marker before any reset. Its CORE_CONF may have been skipped too.
            XiaomiNxpConfig.Plan plan = api.prepare(null);
            recovery = new Recovery(recovery.fingerprint, plan.reset, false);
            store.save(recovery);
            report.notes.add("Upgraded legacy recovery; configuration carrier: " + plan.carrier);
        }
        if (recovery.reset != null) {
            if (api.usesLiveSnapshot()) XiaomiNxpConfig.snapshotFromReset(recovery.reset);
            else XiaomiNxpConfig.validateReset(recovery.reset);
            writeConfig(api, report, api.usesLiveSnapshot() ? "XIAOMI_APPLY_SAVED_NCI_CONFIG"
                    : "XIAOMI_APPLY_FIRMWARE_DEFAULTS", recovery.reset);
        } else if (api.usesLiveSnapshot()) {
            throw new IllegalStateException("Tanzanite recovery snapshot is missing");
        }
        writeConfig(api, report, "XIAOMI_REMOVE_TRANSIT_OVERRIDE", null);
        if (api.usesLiveSnapshot()) {
            report.stage = "XIAOMI_VERIFY_RESTORED_CONFIG";
            api.verifyConfiguration(recovery.reset, report);
        }
        store.clear(); // Keep recovery pending on rejected reset, dead service or disabled NFC.
        report.snapshotState = api.usesLiveSnapshot() ? "SEVEN_NCI_PARAMETERS_RESTORED"
                : "RESET_ACKNOWLEDGED; original override not restored";
        report.result = api.usesLiveSnapshot() ? "NCI_RESTORE_READBACK_VERIFIED" : "DEFAULT_RESET_ACCEPTED_NFC_READY";
        report.stage = "COMPLETE";
    }

    private static void writeConfig(ConfigApi api, OemOperationReport report, String stage, String value)
            throws Exception {
        report.stage = stage;
        report.vendorConfig = value == null ? "<null: remove transit override>" : value;
        report.vendorStatus = null;
        OemOperationReport.TransitCall call = new OemOperationReport.TransitCall(stage, report.vendorConfig);
        report.transitCalls.add(call);
        try {
            report.vendorStatus = api.setConfig(value);
            call.status = report.vendorStatus;
            requireAccepted(api, report.vendorStatus);
        } catch (Throwable error) {
            call.failure = describe(error);
            throw asException(error);
        }
    }

    private static void requireAccepted(ConfigApi api, int status) throws Exception {
        if (status != 0) throw new IllegalStateException("NXP setConfig rejected: " + status);
        if (!api.nfcReady()) throw new IllegalStateException("NFC did not become ready after NXP setConfig");
    }

    /** A hung Binder call cannot be interrupted safely. Keep it single-flight after timeout. */
    static final class VendorCallGate {
        private FutureTask<Integer> inFlight;

        synchronized boolean isIdle() { return inFlight == null || inFlight.isDone(); }

        int call(java.util.concurrent.Callable<Integer> work, long timeoutMillis) throws Exception {
            FutureTask<Integer> task;
            synchronized (this) {
                if (inFlight != null && !inFlight.isDone()) {
                    throw new IllegalStateException("Previous Xiaomi NFC call is still running; reset remains pending");
                }
                task = new FutureTask<>(work);
                inFlight = task;
                Thread thread = new Thread(task, "emula-xiaomi-nfc");
                thread.setDaemon(true);
                thread.start();
            }
            try {
                return task.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                // Do not cancel: cancellation marks a Future done while Binder may still write.
                throw new IllegalStateException("Xiaomi NFC call timed out; reset remains pending", timeout);
            } catch (ExecutionException error) {
                throw asException(error.getCause());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw error;
            }
        }
    }

    static boolean ownsRestartBroadcast(Context context) {
        // OFF may be delivered long after the setter returned. A persistent override
        // also survives a manual OFF/ON cycle; only a final OFF invalidates its UID.
        return restarting || hasPendingRestore(context);
    }

    private static Api resolveApi(Context context) throws Exception {
        Api[] result = new Api[1];
        CALLS.call(() -> {
            result[0] = resolveApiUnchecked(context);
            return 0;
        }, 4_000);
        return result[0];
    }

    private static Api resolveApiUnchecked(Context context) throws Exception {
        if (!isTargetDevice()) throw new IllegalStateException("Unsupported Xiaomi device");
        ClassLoader loader = context.getClassLoader();
        Class<?> miClass = Class.forName("com.xiaomi.nfc.MiNfcAdapter", false, loader);
        Object mi = miClass.getConstructor(Context.class).newInstance(context);
        String chipId = (String) miClass.getMethod("getChipId").invoke(mi);
        if (!"0xa3".equalsIgnoreCase(chipId) && !"0xa4".equalsIgnoreCase(chipId)) {
            throw new IllegalStateException("Unexpected Xiaomi NXP chip ID: " + chipId);
        }
        Class<?> nxpClass = Class.forName("com.nxp.nfc.NxpNfcAdapter", false, loader);
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
        Object nxp = nxpClass.getMethod("getNxpNfcAdapter", NfcAdapter.class).invoke(null, adapter);
        if (nxp == null) throw new IllegalStateException("NXP vendor interface is unavailable");
        Method firmware = nxpClass.getMethod("getFwVersion");
        byte[] version = (byte[]) firmware.invoke(nxp);
        if (!validFirmware(version)) throw new IllegalStateException("No NXP firmware response");
        return new Api(nxp, nxpClass.getMethod("setConfig", String.class, String.class),
                firmware, adapter, context, chipId, RawNciCodec.hex(version));
    }

    static boolean validFirmware(byte[] version) {
        if (version == null || version.length != 3) return false;
        int bits = (version[0] & 255) | (version[1] & 255) | (version[2] & 255);
        return bits != 0 && !(version[0] == -1 && version[1] == -1 && version[2] == -1);
    }

    interface WriteLedger {
        int unfinishedBoot();
        void begin(int boot);
        void finish();
    }

    /** Process death does not cancel an in-progress Binder transaction in the NFC service. */
    static int trackedWrite(WriteLedger ledger, int boot, java.util.concurrent.Callable<Integer> write)
            throws Exception {
        if (boot < 0) throw new IllegalStateException("Cannot identify this boot; Xiaomi NFC write refused");
        if (ledger.unfinishedBoot() == boot) {
            throw new IllegalStateException("A Xiaomi NFC call was interrupted; restart the phone before resetting NFC");
        }
        ledger.begin(boot);
        Throwable primary = null;
        try {
            return write.call();
        } catch (Throwable error) {
            primary = error;
            throw asException(error);
        } finally {
            try { ledger.finish(); }
            catch (Throwable error) {
                if (primary != null) primary.addSuppressed(error);
                else throw asException(error);
            }
        }
    }

    private static final class Api implements ConfigApi {
        final Object receiver;
        final Method setter;
        final Method firmwareGetter;
        final NfcAdapter adapter;
        final String packageName;
        final String chipId;
        final String firmware;
        final WriteLedger ledger;
        final int boot;
        final Context context;
        String configSource;
        String snapshotEvidence;

        Api(Object receiver, Method setter, Method firmwareGetter, NfcAdapter adapter,
                Context context, String chipId, String firmware) {
            this.receiver = receiver; this.setter = setter; this.firmwareGetter = firmwareGetter;
            this.adapter = adapter; this.packageName = context.getPackageName(); this.chipId = chipId;
            this.firmware = firmware;
            this.context = context.getApplicationContext();
            boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            SharedPreferences prefs = preferences(context);
            ledger = new WriteLedger() {
                @Override public int unfinishedBoot() { return prefs.getInt("unfinished_call_boot", -1); }
                @Override public void begin(int currentBoot) {
                    if (!prefs.edit().putInt("unfinished_call_boot", currentBoot).commit()) {
                        throw new IllegalStateException("Could not save Xiaomi NFC call state");
                    }
                }
                @Override public void finish() {
                    if (!prefs.edit().remove("unfinished_call_boot").commit()) {
                        throw new IllegalStateException("Could not save Xiaomi NFC call completion");
                    }
                }
            };
        }

        @Override public boolean usesLiveSnapshot() { return isTanzaniteDevice(); }

        @Override public XiaomiNxpConfig.Plan prepare(byte[] uid) throws Exception {
            return prepare(uid, null);
        }

        @Override public XiaomiNxpConfig.Plan prepare(byte[] uid, String savedReset) throws Exception {
            String baseline;
            try (FileInputStream input = new FileInputStream("/vendor/etc/libnfc-nxp.conf")) {
                baseline = XiaomiNxpConfig.read(input);
                configSource = "/vendor/etc/libnfc-nxp.conf (current firmware)";
            } catch (IOException unavailable) {
                if (!KNOWN_BASELINE_FINGERPRINT.equals(Build.FINGERPRINT)) {
                    throw new IOException("Cannot read this firmware's NFC configuration; no setter was invoked", unavailable);
                }
                baseline = "NXP_SET_CONFIG_ALWAYS=0x01\n" + XiaomiNxpConfig.CORE_CONF_206;
                configSource = "preserved Global 206 baseline (exact firmware match)";
            }
            if (usesLiveSnapshot()) {
                Map<Integer, byte[]> snapshot = null;
                if (savedReset == null) {
                    LegacyNfcDiagnostics.ProbeResult probe = LegacyNfcDiagnostics.runReadOnlyProbe(context);
                    snapshot = probe.configuration;
                    snapshotEvidence = "Original tanzanite configuration\n" + probe.detailedSummary;
                }
                configSource += savedReset == null ? " + live seven-parameter GET snapshot"
                        : " + saved seven-parameter snapshot";
                return XiaomiNxpConfig.snapshotPlan(baseline, uid, snapshot, savedReset);
            }
            String rfBaseline = null;
            if (!XiaomiNxpConfig.setConfigAlways(baseline)) {
                try (FileInputStream input = new FileInputStream("/vendor/etc/libnfc-nxp_RF.conf")) {
                    rfBaseline = XiaomiNxpConfig.read(input);
                    configSource += " + /vendor/etc/libnfc-nxp_RF.conf";
                } catch (IOException unavailable) {
                    throw new IOException("Cannot read firmware RF blocks; no setter was invoked", unavailable);
                }
            }
            return XiaomiNxpConfig.plan(baseline, rfBaseline, uid);
        }

        @Override public void verifyConfiguration(String config, OemOperationReport report) throws Exception {
            LegacyNfcDiagnostics.ProbeResult probe = LegacyNfcDiagnostics.runReadOnlyProbe(context);
            report.notes.add(report.stage + "\n" + probe.detailedSummary);
            if (!XiaomiNxpConfig.matchesSnapshot(config, probe.configuration)) {
                throw new IllegalStateException("Tanzanite NCI readback does not match the saved/expected configuration");
            }
        }

        @Override public String configurationSource() { return configSource; }
        @Override public String preparationEvidence() { return snapshotEvidence; }

        @Override public int setConfig(String value) throws Exception {
            return CALLS.call(() -> {
                restarting = true;
                try {
                    return trackedWrite(ledger, boot, () -> {
                        Object status = setter.invoke(receiver, value, packageName);
                        if (!(status instanceof Integer)) throw new IllegalStateException("Invalid NXP setConfig response");
                        return (Integer) status;
                    });
                } finally {
                    restarting = false;
                }
            }, 15_000);
        }

        @Override public boolean nfcReady() throws Exception {
            return CALLS.call(() -> adapter != null && adapter.isEnabled()
                    && validFirmware((byte[]) firmwareGetter.invoke(receiver)) ? 1 : 0, 4_000) == 1;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Store store(Context context) {
        return store(preferences(context), isTanzaniteDevice());
    }

    static Store store(SharedPreferences prefs) {
        return store(prefs, false);
    }

    static Store store(SharedPreferences prefs, boolean liveSnapshot) {
        return new Store() {
            @Override public Recovery load() {
                if (!prefs.getBoolean("pending", false)) return null;
                int version = prefs.getInt("version", 0);
                if (version != 1 && version != RESTORE_VERSION) {
                    throw new IllegalStateException("Unknown Xiaomi reset marker version");
                }
                String firmware = prefs.getString("firmware", null);
                if (firmware == null || firmware.isEmpty()) throw new IllegalStateException("Missing Xiaomi reset firmware");
                if (version == 1) return new Recovery(firmware, null, true);
                String reset = prefs.getString("reset_config", null);
                if (reset == null) throw new IllegalStateException("Missing Xiaomi reset configuration");
                validateRecovery(reset.isEmpty() ? null : reset, liveSnapshot);
                return new Recovery(firmware, reset.isEmpty() ? null : reset, false);
            }
            @Override public void save(Recovery recovery) {
                validateRecovery(recovery.reset, liveSnapshot);
                if (!prefs.edit().putBoolean("pending", true).putInt("version", RESTORE_VERSION)
                        .putString("firmware", recovery.fingerprint)
                        .putString("reset_config", recovery.reset == null ? "" : recovery.reset).commit()) {
                    throw new IllegalStateException("Could not retain Xiaomi NFC recovery state");
                }
            }
            @Override public void clear() {
                if (!prefs.edit().remove("pending").remove("version").remove("firmware")
                        .remove("reset_config").commit()) {
                    throw new IllegalStateException("Could not clear Xiaomi NFC recovery state");
                }
            }
        };
    }

    private static void validateRecovery(String reset, boolean liveSnapshot) {
        if (liveSnapshot) XiaomiNxpConfig.snapshotFromReset(reset);
        else if (reset != null) XiaomiNxpConfig.validateReset(reset);
    }

    private static String describe(Throwable error) {
        Throwable root = OemNfcController.unwrap(error);
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }

    private static Exception asException(Throwable error) {
        Throwable root = OemNfcController.unwrap(error);
        return root instanceof Exception ? (Exception) root : new IllegalStateException(describe(root), root);
    }
}
