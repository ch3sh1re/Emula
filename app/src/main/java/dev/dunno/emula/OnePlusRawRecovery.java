package dev.dunno.emula;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Archives a pending vendor restore before a compatible phone enters Raw mode. */
final class OnePlusRawRecovery {
    static final String FIRMWARE = "OnePlus/CPH2747/OP611FL1:17/CP2A.260605.016/"
            + "B.R4T3.55357f5-2eb9868-2eda512:user/release-keys";
    static final String PREFS = "oneplus_raw_recovery";
    private static final int[] IDS = {0x30, 0x31, 0x32, 0x33, 0x58, 0x59, 0x85};

    private OnePlusRawRecovery() {}

    static boolean isTarget(int sdk, String manufacturer, String model, String fingerprint) {
        return sdk == 37 && "OnePlus".equalsIgnoreCase(manufacturer)
                && "CPH2747".equalsIgnoreCase(model) && FIRMWARE.equals(fingerprint);
    }

    static boolean isTarget() {
        return isTarget(Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL, Build.FINGERPRINT);
    }

    static Storage storage(Context context) {
        return new Storage(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                context.getSharedPreferences(OnePlusVendorNfcController.PREFS, Context.MODE_PRIVATE));
    }

    static boolean hasPendingRestore(Context context) { return storage(context).pending(); }

    static boolean selected(Context context, boolean forced) {
        if (!forced || !isTarget() || !OnePlusVendorNfcController.isPermissionBlocked(context)) return false;
        try {
            OnePlusVendorNfcController.Snapshot saved = OnePlusVendorNfcController.loadSnapshot(context);
            return selectsTrial(forced, true, true, saved, storage(context).archived());
        } catch (RuntimeException invalid) { return false; }
    }

    static boolean selectsTrial(boolean forced, boolean target, boolean blocked,
            OnePlusVendorNfcController.Snapshot saved, boolean archived) {
        if (!forced || !target || !blocked) return false;
        return saved == null ? archived : "0".equals(saved.mode) && FIRMWARE.equals(saved.fingerprint);
    }

    static String diagnosticSummary(Context context) {
        Storage store = storage(context);
        if (!isTarget() && !store.pending() && !store.archived()) return "";
        return "\nOnePlus scoped raw archive: " + (store.archived() ? "retained" : "none")
                + "\nOnePlus scoped raw restore: " + (store.pending() ? "pending" : "none") + "\n";
    }

    interface Platform {
        boolean allowed();
        default boolean acceptsAndroidDisabledIdle() { return false; }
        OnePlusVendorNfcController.Snapshot vendorSnapshot();
        OnePlusVendorNfcController.Snapshot settings();
        Map<Integer, byte[]> read() throws Exception;
        void archiveAndRelease(OnePlusVendorNfcController.Snapshot original,
                Map<Integer, byte[]> accepted) throws Exception;
        Map<Integer, byte[]> pending();
        void save(Map<Integer, byte[]> original) throws Exception;
        void clear() throws Exception;
        void write(byte[] uid, boolean enabled, RawOperationReport.Operation operation,
                Map<Integer, byte[]> original) throws Exception;
        void rollbackEvidence(Throwable primary, Throwable rollback);
    }

    /** No setters: preserve the old marker, accept only the explicitly selected raw baseline. */
    static void prepare(Platform platform) throws Exception {
        prepare(platform, false);
    }

    /** The reviewed Android path uses the same read-only checks and durable archive. */
    static void prepare(Platform platform, boolean androidOem) throws Exception {
        prepare(platform, androidOem, FIRMWARE, FIRMWARE);
    }

    static void prepareForAndroid(Platform platform, String sourceFirmware, String currentFirmware) throws Exception {
        prepare(platform, true, sourceFirmware, currentFirmware);
    }

    private static void prepare(Platform platform, boolean androidOem, String sourceFirmware,
            String currentFirmware) throws Exception {
        if (!platform.allowed()) throw new IllegalStateException("OnePlus recovery firmware/access changed");
        OnePlusVendorNfcController.Snapshot saved = platform.vendorSnapshot();
        if (saved == null) return;
        OemOperationReport report = new OemOperationReport(OemOperationReport.Operation.STARTUP_RESTORE);
        report.path = OemOperationReport.Path.ONEPLUS_VENDOR_HCE;
        report.stage = "CHECK_DISABLED_VENDOR_STATE";
        report.snapshotState = "RESTORE_PENDING";
        report.originalGuardMode = saved.mode;
        boolean systemUpdate = !Objects.equals(sourceFirmware, currentFirmware);
        if (systemUpdate) {
            report.notes.add("Snapshot firmware: " + saved.fingerprint);
            report.notes.add("Observed firmware: " + currentFirmware);
        }
        OemNfcController.recordOperation(report);
        try {
            if (sourceFirmware == null || currentFirmware == null || !"0".equals(saved.mode)
                    || !sourceFirmware.equals(saved.fingerprint)) {
                throw new IllegalStateException("Original OnePlus recovery record changed");
            }
            OnePlusVendorNfcController.Snapshot live = platform.settings();
            if (!sameMetadata(saved, live) || !currentFirmware.equals(live.fingerprint)) {
                throw new IllegalStateException("Original OnePlus settings differ; vendor recovery retained");
            }
            Map<Integer, byte[]> current = platform.read();
            if (!archiveIdleConfiguration(current, sourceFirmware, currentFirmware)) {
                throw new IllegalStateException("NFC readback differs from the known idle profiles or is incomplete; recovery retained");
            }
            if (!platform.allowed() || !same(live, platform.settings())
                    || !same(saved, platform.vendorSnapshot())) {
                throw new IllegalStateException("OnePlus state changed during the check; recovery retained");
            }
            report.stage = "ARCHIVE_DISABLED_VENDOR_STATE";
            platform.archiveAndRelease(saved, copy(current));
            report.stage = "COMPLETE";
            if (systemUpdate) {
                report.result = "ARCHIVED_AFTER_SYSTEM_UPDATE";
                report.notes.add("Android 16 vendor metadata and seven idle NFC parameters verified on Android 17. "
                        + "Original record archived; no NFC setter called.");
            } else {
                report.result = androidOem ? "ARCHIVED_FOR_ANDROID_OEM" : "ARCHIVED_FOR_EXPLICIT_RAW_TRIAL";
                report.notes.add(androidOem
                        ? "Reviewed Android OEM route selected. Original vendor record retained; matching metadata "
                                + "and all seven reported idle parameters checked without setters. "
                                + "Historical vendor restoration and reader success are not claimed."
                        : "Raw mode selected. Original vendor record retained; current system-managed "
                                + "settings accepted as the raw baseline. Historical vendor restoration is not claimed.");
            }
            report.snapshotState = "ARCHIVED";
        } catch (Exception failure) {
            report.stage = "FAILED";
            report.failure = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            throw failure;
        }
    }

    /** A persisted seven-parameter baseline survives card switching and process restarts. */
    static void change(Platform platform, byte[] uid) throws Exception {
        Map<Integer, byte[]> baseline = prepareChange(platform, uid);
        if (uid == null) {
            if (baseline == null) return;
            platform.write(baseline.get(0x33), false, RawOperationReport.Operation.DEACTIVATE, baseline);
            platform.clear();
            return;
        }
        try {
            platform.write(uid, true, RawOperationReport.Operation.ACTIVATE, baseline);
        } catch (Exception primary) {
            Throwable rollback = null;
            try {
                platform.write(baseline.get(0x33), false,
                        RawOperationReport.Operation.ROLLBACK_AFTER_ACTIVATION_FAILURE, baseline);
                platform.clear();
            } catch (Exception failure) {
                rollback = failure;
                primary.addSuppressed(failure);
            }
            platform.rollbackEvidence(primary, rollback);
            throw primary;
        }
    }

    private static Map<Integer, byte[]> prepareChange(Platform platform, byte[] uid) throws Exception {
        RawOperationReport report = new RawOperationReport(uid == null
                ? RawOperationReport.Operation.DEACTIVATE : RawOperationReport.Operation.ACTIVATE);
        report.candidate = RawOperationReport.Candidate.UID_ONLY;
        report.stage = "CHECK_ONEPLUS_SCOPE";
        try {
            if (!platform.allowed()) throw new IllegalStateException("OnePlus raw recovery firmware/access changed");
            report.stage = "LOAD_UID_SNAPSHOT";
            Map<Integer, byte[]> original = platform.pending();
            if (uid == null) {
                if (original != null) requireComplete(original);
                return original;
            }
            report.stage = "CHECK_UID";
            if (!UidCodec.isSupportedLength(uid.length)) throw new IllegalArgumentException("Invalid UID length");
            report.stage = "CHECK_DISABLED_VENDOR_STATE";
            prepare(platform);
            report.stage = "CHECK_ONEPLUS_HCE_MODE";
            OnePlusVendorNfcController.Snapshot currentSettings = platform.settings();
            if (currentSettings == null || !"0".equals(currentSettings.mode)
                    || !FIRMWARE.equals(currentSettings.fingerprint)) {
                throw new IllegalStateException("OnePlus HCE mode changed; no UID was written");
            }
            if (original == null) {
                report.stage = "READ_IDLE_BASELINE";
                original = platform.read();
                report.stage = "CHECK_IDLE_BASELINE";
                boolean androidDisabledIdle = platform.acceptsAndroidDisabledIdle();
                report.notes.add("Reviewed Android-disabled idle state allowed: " + androidDisabledIdle);
                if (original != null) report.notes.add("Observed idle baseline: "
                        + RawNciCodec.encodeConfiguration(original));
                if (!rawIdleConfiguration(original, androidDisabledIdle)) {
                    throw new IllegalStateException("NFC baseline differs from the accepted idle state; no UID was written");
                }
            }
            report.stage = "SAVE_UID_SNAPSHOT";
            Map<Integer, byte[]> baseline = copy(original);
            platform.save(baseline); // No SET until the complete recovery record commits.
            return baseline;
        } catch (Exception failure) {
            LegacyNfcController.recordPreparationFailure(report, failure);
            throw failure;
        }
    }

    static boolean same(OnePlusVendorNfcController.Snapshot a, OnePlusVendorNfcController.Snapshot b) {
        return sameMetadata(a, b) && Objects.equals(a.fingerprint, b.fingerprint);
    }

    static boolean sameMetadata(OnePlusVendorNfcController.Snapshot a, OnePlusVendorNfcController.Snapshot b) {
        return a != null && b != null && Objects.equals(a.mode, b.mode)
                && Objects.equals(a.atqa, b.atqa) && Objects.equals(a.sak, b.sak)
                && Objects.equals(a.uid, b.uid);
    }

    static boolean reportedIdleConfiguration(Map<Integer, byte[]> config) {
        return idleConfiguration(config, (byte) 0x41);
    }

    // The second full profile was observed after boot on OnePlus 11, 12 and 15.
    // Accept it only for archiving an earlier OS record, never as a raw write baseline.
    private static boolean archiveIdleConfiguration(Map<Integer, byte[]> config, String saved, String observed) {
        if (saved.equals(observed)) return reportedIdleConfiguration(config);
        return OnePlusAndroidRecovery.sameDeviceUpdate(saved, observed)
                && (reportedIdleConfiguration(config) || OnePlusLegacyRecovery.matchesPostBootProfile(config));
    }

    /** Android 17 disable writes 0x58=00; preserve that live baseline, never rewrite it to 41. */
    static boolean rawIdleConfiguration(Map<Integer, byte[]> config, boolean androidDisabledIdle) {
        return reportedIdleConfiguration(config)
                || (androidDisabledIdle && idleConfiguration(config, (byte) 0));
    }

    private static boolean idleConfiguration(Map<Integer, byte[]> config, byte rats) {
        if (config == null) return false;
        Map<Integer, byte[]> expected = new LinkedHashMap<>();
        expected.put(0x30, new byte[] {4}); expected.put(0x31, new byte[] {0});
        expected.put(0x32, new byte[] {0x20}); expected.put(0x33, new byte[] {8, 0, 0, 0});
        expected.put(0x58, new byte[] {rats}); expected.put(0x59, new byte[0]);
        expected.put(0x85, new byte[] {1});
        for (int id : IDS) if (!Arrays.equals(expected.get(id), config.get(id))) return false;
        return true;
    }

    static void requireComplete(Map<Integer, byte[]> config) {
        if (config == null) throw new IllegalStateException("Missing OnePlus raw baseline");
        for (int id : IDS) if (config.get(id) == null) throw new IllegalStateException("Incomplete OnePlus raw baseline");
    }

    private static Map<Integer, byte[]> copy(Map<Integer, byte[]> config) {
        requireComplete(config);
        Map<Integer, byte[]> result = new LinkedHashMap<>();
        for (int id : IDS) result.put(id, Arrays.copyOf(config.get(id), config.get(id).length));
        return result;
    }

    static final class Storage {
        final SharedPreferences prefs;
        final SharedPreferences vendor;
        Storage(SharedPreferences prefs, SharedPreferences vendor) { this.prefs = prefs; this.vendor = vendor; }
        boolean archived() {
            try {
                return FIRMWARE.equals(prefs.getString("archive_firmware", null))
                        && FIRMWARE.equals(prefs.getString("archive_observed_firmware", FIRMWARE))
                        && "0".equals(prefs.getString("archive_mode", null))
                        && reportedIdleConfiguration(RawNciCodec.decodeConfiguration(
                                prefs.getString("archive_configuration", "")));
            } catch (RuntimeException invalid) { return false; }
        }
        boolean pending() { return prefs.getBoolean("pending", false); }
        Map<Integer, byte[]> load() {
            if (!pending()) return null;
            if (!FIRMWARE.equals(prefs.getString("firmware", null))) {
                throw new IllegalStateException("OnePlus raw baseline firmware mismatch");
            }
            Map<Integer, byte[]> result = RawNciCodec.decodeConfiguration(prefs.getString("configuration", ""));
            requireComplete(result);
            return result;
        }
        void save(Map<Integer, byte[]> original) {
            requireComplete(original);
            if (!prefs.edit().putBoolean("pending", true).putString("firmware", FIRMWARE)
                    .putString("configuration", RawNciCodec.encodeConfiguration(original)).commit()) {
                throw new IllegalStateException("Could not persist OnePlus raw baseline");
            }
        }
        void clear() {
            if (!prefs.edit().putBoolean("pending", false).commit()) {
                // commit may update memory even if disk persistence fails. Keep recovery visible.
                boolean rearmed = prefs.edit().putBoolean("pending", true).commit();
                throw new IllegalStateException(rearmed ? "Could not finish OnePlus raw recovery; retry retained"
                        : "Could not persist OnePlus raw recovery completion or retry marker");
            }
        }
        void archiveAndRelease(OnePlusVendorNfcController.Snapshot saved, Map<Integer, byte[]> current) {
            if (!FIRMWARE.equals(saved.fingerprint)) throw new IllegalStateException("OnePlus raw archive firmware mismatch");
            archiveAndRelease(saved, current, FIRMWARE);
        }

        void archiveAndRelease(OnePlusVendorNfcController.Snapshot saved, Map<Integer, byte[]> current,
                String observedFirmware) {
            if (!"0".equals(saved.mode) || saved.fingerprint == null || observedFirmware == null
                    || !archiveIdleConfiguration(current, saved.fingerprint, observedFirmware)) {
                throw new IllegalStateException("Invalid OnePlus recovery archive");
            }
            if (!same(saved, OnePlusVendorNfcController.loadSnapshot(vendor))) {
                throw new IllegalStateException("OnePlus vendor snapshot changed");
            }
            if (prefs.contains("archive_firmware")) {
                OnePlusVendorNfcController.Snapshot archived = new OnePlusVendorNfcController.Snapshot(
                        prefs.getString("archive_mode", null), prefs.getString("archive_atqa", null),
                        prefs.getString("archive_sak", null), prefs.getString("archive_uid", null),
                        prefs.getString("archive_firmware", null));
                // Original same-firmware archives predate the observed-firmware field.
                if (!same(saved, archived) || !observedFirmware.equals(prefs.getString(
                        "archive_observed_firmware", archived.fingerprint)) || !RawNciCodec.encodeConfiguration(current)
                        .equals(prefs.getString("archive_configuration", null))) {
                    throw new IllegalStateException("An older OnePlus recovery archive is retained");
                }
            } else if (prefs.contains("archive_observed_firmware") || prefs.contains("archive_configuration")) {
                throw new IllegalStateException("An unreadable OnePlus recovery archive is retained");
            }
            if (!prefs.edit().putString("archive_firmware", saved.fingerprint)
                    .putString("archive_observed_firmware", observedFirmware)
                    .putString("archive_mode", saved.mode).putString("archive_atqa", saved.atqa)
                    .putString("archive_sak", saved.sak).putString("archive_uid", saved.uid)
                    .putString("archive_configuration", RawNciCodec.encodeConfiguration(current))
                    .putLong("archive_time", System.currentTimeMillis()).commit()) {
                throw new IllegalStateException("Could not archive the original OnePlus recovery state");
            }
            if (!vendor.edit().putBoolean(OnePlusVendorNfcController.PREF_PENDING, false).commit()) {
                boolean rearmed = vendor.edit().putBoolean(OnePlusVendorNfcController.PREF_PENDING, true).commit();
                throw new IllegalStateException(rearmed ? "Could not release the archived OnePlus recovery marker"
                        : "Could not persist the OnePlus marker update; original archive retained");
            }
        }
    }

    static final class AndroidPlatform implements Platform {
        final Context context;
        final Context pollingContext;
        final Storage store;
        final boolean androidOem;
        RawOperationReport activation;
        boolean writeAttempted;
        AndroidPlatform(Context context, Context pollingContext) {
            this(context, pollingContext, false);
        }
        AndroidPlatform(Context context, Context pollingContext, boolean androidOem) {
            this.context = context; this.pollingContext = pollingContext; store = storage(context);
            this.androidOem = androidOem;
        }
        public boolean allowed() { return androidOem ? OnePlusAndroidRecovery.selected(context) : isTarget(); }
        public boolean acceptsAndroidDisabledIdle() {
            return store.archived() && OnePlusAndroidRecovery.reviewedStack(context);
        }
        public OnePlusVendorNfcController.Snapshot vendorSnapshot() { return OnePlusVendorNfcController.loadSnapshot(context); }
        public OnePlusVendorNfcController.Snapshot settings() { return OnePlusVendorNfcController.currentSnapshot(context); }
        public Map<Integer, byte[]> read() throws Exception { return LegacyNfcDiagnostics.runReadOnlyProbe(context).configuration; }
        public void archiveAndRelease(OnePlusVendorNfcController.Snapshot original, Map<Integer, byte[]> accepted) {
            if (androidOem && (!FIRMWARE.equals(original.fingerprint) || !FIRMWARE.equals(Build.FINGERPRINT))) {
                // An observation made after an OTA is never a baseline for the raw trial.
                new Storage(OnePlusAndroidRecovery.updateArchive(context), store.vendor)
                        .archiveAndRelease(original, accepted, Build.FINGERPRINT);
            } else {
                store.archiveAndRelease(original, accepted);
            }
        }
        public Map<Integer, byte[]> pending() { return store.load(); }
        public void save(Map<Integer, byte[]> original) { store.save(original); }
        public void clear() { store.clear(); }
        public void write(byte[] uid, boolean enabled, RawOperationReport.Operation operation,
                Map<Integer, byte[]> original) throws Exception {
            writeAttempted = true;
            try { LegacyNfcController.setUidSnapshotEnabled(pollingContext, uid, enabled, operation, original); }
            finally { if (enabled) activation = LegacyNfcController.getLastOperationReport(); }
        }
        public void rollbackEvidence(Throwable primary, Throwable rollback) {
            LegacyNfcController.attachRollback(activation, LegacyNfcController.getLastOperationReport(), rollback);
        }
    }
}
