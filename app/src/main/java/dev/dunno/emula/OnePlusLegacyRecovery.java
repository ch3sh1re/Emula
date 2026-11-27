package dev.dunno.emula;

import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only migration of flag-only records on reviewed firmware. */
final class OnePlusLegacyRecovery {
    private static final Pattern ONEPLUS_12_STOCK =
            Pattern.compile("OnePlus/[^/:]+/OP595DL1:([^/:]+)/[^/:]+/[^/:]+:user/release-keys");
    private static final Pattern ONEPLUS_15_ANDROID_16 =
            Pattern.compile("OnePlus/[^/:]+/OP611FL1:16/[^/:]+/[^/:]+:user/release-keys");
    static final OnePlusLegacyRecovery ONEPLUS_11 = new OnePlusLegacyRecovery(
            "OnePlus 11", "oneplus11", "OnePlus/CPH2447/OP594DL1:16/TP1A.220905.001/"
                    + "T.R4T3.3b5d87c-159204d-16035e1:user/release-keys");
    static final OnePlusLegacyRecovery ONEPLUS_12 = new OnePlusLegacyRecovery(
            "OnePlus 12", "oneplus12", "OnePlus/CPH2573IN/OP595DL1:16/BP2A.250605.015/"
                    + "U.R4T3.4f93090-29426eb-296fc63:user/release-keys");

    final String displayName, fingerprint, checkpointKey, archiveKey;
    private static final int[] OBSERVED_PARAMETERS = {0x30, 0x31, 0x32, 0x33, 0x58, 0x59, 0x85};

    private OnePlusLegacyRecovery(String displayName, String preferencePrefix, String fingerprint) {
        this.displayName = displayName;
        this.fingerprint = fingerprint;
        this.checkpointKey = preferencePrefix + "_pending_boot";
        this.archiveKey = preferencePrefix + "_archived_legacy_record";
    }

    static OnePlusLegacyRecovery forDevice(String model, String firmware) {
        if (ONEPLUS_11.fingerprint.equals(firmware)) return ONEPLUS_11;
        // Keep the preference names already shipped for the Indian build.
        if (ONEPLUS_12.fingerprint.equals(firmware)) return ONEPLUS_12;
        if (isOnePlus12Android16(firmware)) {
            return new OnePlusLegacyRecovery(ONEPLUS_12.displayName, "oneplus12_" + firmware, firmware);
        }
        // The UID-first variant has its own restore procedure for records without a snapshot.
        if (firmware != null && model != null && !model.isEmpty()
                && ONEPLUS_15_ANDROID_16.matcher(firmware).matches()
                && !RawNfcDeviceCatalog.prefersUidOnly("OnePlus", model)) {
            return new OnePlusLegacyRecovery("OnePlus 15", "oneplus15_" + firmware, firmware);
        }
        return null;
    }

    static boolean isOnePlus12Android16(String firmware) {
        if (firmware == null) return false;
        Matcher match = ONEPLUS_12_STOCK.matcher(firmware);
        return match.matches() && "16".equals(match.group(1));
    }

    static boolean supportsUidSnapshot(int sdk, String firmware) {
        return sdk >= 35 && firmware != null && ONEPLUS_12_STOCK.matcher(firmware).matches();
    }

    // A session opened by an older build must keep its original six-field restore.
    static boolean useUidSnapshot(int sdk, String firmware, boolean pending, boolean savedUidOnly) {
        return supportsUidSnapshot(sdk, firmware) && (!pending || savedUidOnly);
    }

    interface SnapshotReader {
        Map<Integer, byte[]> read() throws Exception;
    }

    static Map<Integer, byte[]> baseline(String model, String firmware, String savedFirmware, boolean pending,
            boolean activating, boolean uidOnly, SnapshotReader saved, SnapshotReader live) throws Exception {
        boolean needsBaseline = uidOnly || forDevice(model, firmware) != null;
        Map<Integer, byte[]> original;
        if (savedFirmware != null) {
            if (!needsBaseline || !savedFirmware.equals(firmware)) {
                throw new IllegalStateException("Saved NFC recovery belongs to a different firmware build");
            }
            original = saved.read();
        } else {
            if (pending && uidOnly) throw new IllegalStateException("Missing firmware for the saved UID session");
            if (!needsBaseline || !activating) return null;
            if (pending) throw new IllegalStateException("Complete the pending NFC recovery before activation");
            original = live.read();
        }
        checkBaseline(original);
        return original;
    }

    static byte snapshotBitFrameSdd(Map<Integer, byte[]> original) {
        byte[] field = original == null ? null : original.get(0x30);
        if (field == null || field.length != 1 || (field[0] & 0xe0) != 0
                || Integer.bitCount(field[0]) != 1) {
            throw new IllegalStateException("OnePlus 12 activation requires a valid Type A bit frame in the NFC snapshot");
        }
        return field[0];
    }

    private static void checkBaseline(Map<Integer, byte[]> original) {
        if (!LegacyNfcController.isCompleteExperimentalSnapshot(original)) {
            throw new IllegalStateException("Could not snapshot every NFC parameter before writing");
        }
        for (int id : new int[] {0x30, 0x31, 0x32, 0x58, 0x85}) {
            if (original.get(id).length != 1) throw new IllegalStateException("Invalid NFC snapshot field");
        }
        int uidLength = original.get(0x33).length;
        if (uidLength != 0 && !UidCodec.isSupportedLength(uidLength)) {
            throw new IllegalStateException("Invalid NFC snapshot identifier length");
        }
    }

    // Observed on CPH2447, CPH2573 and CPH2745. All seven must match in a fresh post-boot read.
    // This is a post-boot compatibility check, never a payload or a saved original.
    private static final byte[][] REPORTED_VALUES = {
            {4}, {0}, {0x20}, {8, 0, 0, 0}, {0x74}, {}, {1}
    };

    static boolean oldRecord(String model, String firmware, boolean pending, boolean unverified,
            boolean hasSnapshotFirmware) {
        return forDevice(model, firmware) != null && pending && !unverified && !hasSnapshotFirmware;
    }

    enum BootState { NO_CHECKPOINT, UNAVAILABLE, COUNTER_DECREASED, SAME_BOOT, LATER_BOOT }

    static BootState bootState(int firstBoot, int currentBoot) {
        if (firstBoot < 0) return BootState.NO_CHECKPOINT;
        if (currentBoot < 0) return BootState.UNAVAILABLE;
        if (currentBoot < firstBoot) return BootState.COUNTER_DECREASED;
        return currentBoot == firstBoot ? BootState.SAME_BOOT : BootState.LATER_BOOT;
    }

    static void checkAfterRestart(int firstBoot, int currentBoot, RawOperationReport report,
            SnapshotReader probe) throws Exception {
        BootState state = bootState(firstBoot, currentBoot);
        if (state != BootState.SAME_BOOT && state != BootState.LATER_BOOT) {
            report.result = "RESTART_UNVERIFIED";
            throw new IllegalStateException("Could not verify the phone's restart. The recovery record was kept.");
        }
        if (state == BootState.SAME_BOOT) {
            report.result = "RESTART_REQUIRED";
            throw new IllegalStateException("Restart your phone once, then open Emula with NFC on.");
        }
        report.stage = "CHECK_AFTER_RESTART";
        report.result = "POST_BOOT_READ_UNVERIFIED";
        if (!matchesPostBootProfile(probe.read())) {
            report.result = "POST_BOOT_PROFILE_MISMATCH";
            throw new IllegalStateException("NFC settings after restart differ from the reviewed profile. "
                    + "The recovery record was kept.");
        }
    }

    static boolean matchesPostBootProfile(Map<Integer, byte[]> values) {
        if (values == null) return false;
        for (int i = 0; i < OBSERVED_PARAMETERS.length; i++) {
            if (!Arrays.equals(REPORTED_VALUES[i], values.get(OBSERVED_PARAMETERS[i]))) return false;
        }
        return true;
    }
}
