package dev.dunno.emula;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Explicit, firmware-scoped trial of the NXP transit-config API. Phone only. */
@SuppressLint("PrivateApi")
final class XiaomiNfcController {
    static final String TARGET_FINGERPRINT =
            "Redmi/garnet_global/garnet:15/AQ3A.240912.001/OS2.0.206.0.VNRMIXM:user/release-keys";
    private static final String PREFS = "xiaomi_nxp_transit";
    private static final int RESTORE_VERSION = 1;
    private static final VendorCallGate CALLS = new VendorCallGate();
    private static volatile boolean restarting;
    private static volatile long restartFinished = Long.MIN_VALUE;

    private XiaomiNfcController() {}

    static boolean isTargetDevice() {
        return isTargetDevice(Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL,
                Build.DEVICE, Build.PRODUCT, Build.FINGERPRINT);
    }

    static boolean isTargetDevice(int sdk, String manufacturer, String model,
            String device, String product, String fingerprint) {
        return sdk == 35 && "Xiaomi".equalsIgnoreCase(manufacturer)
                && "2312DRA50G".equals(model) && "garnet".equals(device)
                && "garnet_global".equals(product) && TARGET_FINGERPRINT.equals(fingerprint);
    }

    static boolean hasPendingRestore(Context context) {
        return preferences(context).getBoolean("pending", false);
    }

    static boolean consentAccepted(Context context) {
        return Build.FINGERPRINT.equals(preferences(context).getString("accepted_firmware", null));
    }

    static void rememberConsent(Context context) {
        if (!preferences(context).edit().putString("accepted_firmware", Build.FINGERPRINT).commit()) {
            throw new IllegalStateException("Could not save Xiaomi NFC approval");
        }
    }

    /** No setters. Read chip identity and firmware through the actual vendor services. */
    static String capabilities(Context context, boolean includeProbe) {
        if (!isTargetDevice() && !hasPendingRestore(context)) return "Xiaomi NXP trial: not targeted";
        StringBuilder text = new StringBuilder("Xiaomi NXP trial: ")
                .append(isTargetDevice() ? "targeted (experimental)" : "firmware changed")
                .append("\nXiaomi reset pending: ").append(hasPendingRestore(context) ? "yes" : "no")
                .append("\nXiaomi trial approved: ").append(consentAccepted(context) ? "yes" : "no");
        if (!includeProbe) return text.toString();
        try {
            Api api = resolveApi(context);
            text.append("\nXiaomi chip ID: ").append(api.chipId)
                    .append("\nNXP firmware bytes: ").append(api.firmware)
                    .append("\nNXP setConfig: resolved");
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
            report.stage = "CHECK_FIRMWARE";
            if (!isTargetDevice()) throw new IllegalStateException("Xiaomi NFC trial is not enabled for this firmware");
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
        int setConfig(String value) throws Exception;
        boolean nfcReady() throws Exception;
    }

    interface Store {
        String load();
        void save(String fingerprint);
        void clear();
    }

    /** Transaction logic is exercised with a fake service; no raw commands or fallback. */
    static void transact(ConfigApi api, Store store, String fingerprint, byte[] uid,
            OemOperationReport report) throws Exception {
        report.path = OemOperationReport.Path.XIAOMI_NXP_TRANSIT;
        report.profile = "garnet-206 / NXP transit config (experimental)";
        report.notes.add("Factory reset replaces prior transit overrides; no original-file snapshot is available.");
        report.notes.add("A vendor acknowledgement does not verify RF UID, file deletion, payment, or transit cards.");
        String pending = store.load();
        if (pending != null && !fingerprint.equals(pending)) {
            throw new IllegalStateException("Xiaomi reset belongs to a different firmware; recovery marker retained");
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
        String config = configuration(uid); // Validate before persisting or invoking any setter.
        report.uid = OemOperationReport.copy(uid);
        report.stage = "SAVE_PENDING_XIAOMI_RESET";
        store.save(fingerprint); // Durable before either the service or HAL can write a file.
        report.snapshotState = "FACTORY_RESET_PENDING (not an original-configuration snapshot)";
        try {
            report.stage = "XIAOMI_SET_TRANSIT_CONFIG";
            report.vendorConfig = config;
            report.vendorStatus = api.setConfig(config);
            requireAccepted(api, report.vendorStatus);
            report.result = "VENDOR_CONFIG_ACCEPTED_READER_UNVERIFIED";
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
        report.stage = "XIAOMI_REQUEST_FACTORY_RESET";
        report.snapshotState = "FACTORY_RESET_PENDING";
        report.vendorConfig = "<null: remove transit override>";
        report.vendorStatus = api.setConfig(null);
        requireAccepted(api, report.vendorStatus);
        store.clear(); // Keep recovery pending on rejected reset, dead service or disabled NFC.
        report.snapshotState = "RESET_ACKNOWLEDGED; original override not restored";
        report.result = "DEFAULT_RESET_ACCEPTED_NFC_READY";
        report.stage = "COMPLETE";
    }

    private static void requireAccepted(ConfigApi api, int status) throws Exception {
        if (status != 0) throw new IllegalStateException("NXP setConfig rejected: " + status);
        if (!api.nfcReady()) throw new IllegalStateException("NFC did not become ready after NXP setConfig");
    }

    /** Preserve the 206 firmware's CORE_CONF parameters, changing only NFCID1 and config control. */
    static String configuration(byte[] uid) {
        if (uid == null || !UidCodec.isSupportedLength(uid.length)) {
            throw new IllegalArgumentException("UID must contain 4, 7, or 10 bytes");
        }
        Map<Integer, byte[]> values = new LinkedHashMap<>();
        // Disable automatic RF parameter updates before installing the explicit NFCID1.
        values.put(0x85, new byte[] {0});
        int[][] defaults = {{0x28, 0}, {0x21, 0}, {0x30, 4}, {0x31, 0}, {0x32, 0x60},
                {0x38, 1}, {0x54, 6}, {0x50, 2}, {0x5B, 0}, {0x3E, 0}, {0x80, 1},
                {0x81, 1}, {0x82, 0x0E}, {0x18, 1}, {0x68, 1}};
        for (int[] pair : defaults) values.put(pair[0], new byte[] {(byte) pair[1]});
        values.put(0x33, uid.clone());
        int[] ids = values.keySet().stream().mapToInt(Integer::intValue).toArray();
        byte[] payload = RawNciCodec.configPayload(values, ids);
        byte[] command = new byte[payload.length + 3];
        command[0] = 0x20;
        command[1] = 0x02;
        command[2] = (byte) payload.length;
        System.arraycopy(payload, 0, command, 3, payload.length);
        return "NXP_CORE_CONF={" + RawNciCodec.hex(command).replace(" ", ",") + "}\n";
    }

    /** A hung Binder call cannot be interrupted safely. Keep it single-flight after timeout. */
    static final class VendorCallGate {
        private FutureTask<Integer> inFlight;

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

    static boolean ownsRestartBroadcast() {
        long finished = restartFinished;
        return restarting || (finished != Long.MIN_VALUE && SystemClock.elapsedRealtime() - finished < 1_000);
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
        if (!isTargetDevice()) throw new IllegalStateException("Unsupported Xiaomi firmware");
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

        Api(Object receiver, Method setter, Method firmwareGetter, NfcAdapter adapter,
                Context context, String chipId, String firmware) {
            this.receiver = receiver; this.setter = setter; this.firmwareGetter = firmwareGetter;
            this.adapter = adapter; this.packageName = context.getPackageName(); this.chipId = chipId;
            this.firmware = firmware;
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
                    restartFinished = SystemClock.elapsedRealtime();
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
        SharedPreferences prefs = preferences(context);
        return new Store() {
            @Override public String load() {
                if (!prefs.getBoolean("pending", false)) return null;
                if (prefs.getInt("version", 0) != RESTORE_VERSION) {
                    throw new IllegalStateException("Unknown Xiaomi reset marker version");
                }
                String firmware = prefs.getString("firmware", null);
                if (firmware == null || firmware.isEmpty()) throw new IllegalStateException("Missing Xiaomi reset firmware");
                return firmware;
            }
            @Override public void save(String fingerprint) {
                if (!prefs.edit().putBoolean("pending", true).putInt("version", RESTORE_VERSION)
                        .putString("firmware", fingerprint).commit()) {
                    throw new IllegalStateException("Could not retain Xiaomi NFC recovery state");
                }
            }
            @Override public void clear() {
                if (!prefs.edit().remove("pending").remove("version").remove("firmware").commit()) {
                    throw new IllegalStateException("Could not clear Xiaomi NFC recovery state");
                }
            }
        };
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
