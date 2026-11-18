package dev.dunno.emula;

import java.util.Arrays;
import java.util.Map;

/** Recovery on the CPH2447 build whose controller rejected the legacy defaults. */
final class OnePlus11Recovery {
    static final String FIRMWARE = "OnePlus/CPH2447/OP594DL1:16/TP1A.220905.001/"
            + "T.R4T3.3b5d87c-159204d-16035e1:user/release-keys";
    private static final int[] OBSERVED_PARAMETERS = {0x30, 0x31, 0x32, 0x33, 0x58, 0x59, 0x85};

    private OnePlus11Recovery() {}

    interface SnapshotReader {
        Map<Integer, byte[]> read() throws Exception;
    }

    static Map<Integer, byte[]> baseline(String firmware, String savedFirmware, boolean pending,
            boolean activating, SnapshotReader saved, SnapshotReader live) throws Exception {
        if (savedFirmware != null) {
            if (!FIRMWARE.equals(savedFirmware) || !savedFirmware.equals(firmware)) {
                throw new IllegalStateException("Saved NFC recovery belongs to a different firmware build");
            }
            return checkedBaseline(saved.read());
        }
        if (!FIRMWARE.equals(firmware) || !activating) return null;
        if (pending) throw new IllegalStateException("Complete the pending NFC recovery before activation");
        return checkedBaseline(live.read());
    }

    private static Map<Integer, byte[]> checkedBaseline(Map<Integer, byte[]> original) {
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
        return original;
    }

    // The two reads reported for this build agreed on these seven bytes/fields.
    // This is a post-boot compatibility check, never a payload or a saved original.
    private static final byte[][] REPORTED_VALUES = {
            {4}, {0}, {0x20}, {8, 0, 0, 0}, {0x74}, {}, {1}
    };

    static boolean oldRecord(String firmware, boolean pending, boolean unverified,
            boolean hasSnapshotFirmware) {
        return FIRMWARE.equals(firmware) && pending && !unverified && !hasSnapshotFirmware;
    }

    static void checkAfterRestart(int firstBoot, int currentBoot, RawOperationReport report,
            SnapshotReader probe) throws Exception {
        if (currentBoot < 0 || firstBoot < 0 || currentBoot < firstBoot) {
            report.result = "RESTART_UNVERIFIED";
            throw new IllegalStateException("Could not verify the phone's restart. The recovery record was kept.");
        }
        if (firstBoot == currentBoot) {
            report.result = "RESTART_REQUIRED";
            throw new IllegalStateException("Restart your phone once, then open Emula with NFC on.");
        }
        report.stage = "CHECK_AFTER_RESTART";
        report.result = "POST_BOOT_READ_UNVERIFIED";
        Map<Integer, byte[]> values = probe.read();
        for (int i = 0; i < OBSERVED_PARAMETERS.length; i++) {
            if (values == null || !Arrays.equals(REPORTED_VALUES[i], values.get(OBSERVED_PARAMETERS[i]))) {
                report.result = "POST_BOOT_PROFILE_MISMATCH";
                throw new IllegalStateException("NFC settings after restart differ from the reviewed profile. "
                        + "The recovery record was kept.");
            }
        }
    }
}
