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

    static void observeLegacyReset(LegacyNfcController.ConfigurationIo session,
            RawOperationReport report, LegacyNfcController.CheckedAction reset) throws Exception {
        report.notes.add("Older recovery record has no pre-activation snapshot. "
                + "Current values are observations only.");
        Map<Integer, byte[]> before = observe(session, report, "BEFORE_LEGACY_RESET");
        try {
            reset.run();
        } catch (Exception failure) {
            report.result = "LEGACY_RESET_FAILED_WITHOUT_BASELINE";
            if (before != null && !Thread.currentThread().isInterrupted()
                    && Boolean.TRUE.equals(report.resumeSucceeded)) {
                try {
                    Map<Integer, byte[]> after = observe(session, report, "AFTER_FAILED_LEGACY_RESET");
                    if (after != null) {
                        StringBuilder changes = new StringBuilder();
                        for (int id : OBSERVED_PARAMETERS) {
                            if (!Arrays.equals(before.get(id), after.get(id))) {
                                if (changes.length() > 0) changes.append(", ");
                                changes.append("0x").append(Integer.toHexString(id));
                            }
                        }
                        report.notes.add(changes.length() == 0
                                ? "All seven observed parameters match the pre-reset read."
                                : "Parameters differing between the two reads: " + changes);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure.addSuppressed(interrupted);
                }
            }
            throw failure;
        }
    }

    private static Map<Integer, byte[]> observe(LegacyNfcController.ConfigurationIo session,
            RawOperationReport report, String stage) throws InterruptedException {
        try {
            Map<Integer, byte[]> values = session.readConfiguration(OBSERVED_PARAMETERS,
                    report, report.operation, report.candidate, stage);
            for (int id : OBSERVED_PARAMETERS) {
                if (values.get(id) == null) throw new IllegalStateException("Incomplete readback");
            }
            return values;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (Exception unavailable) {
            report.notes.add(stage + " unavailable: " + unavailable.getMessage());
            return null;
        }
    }
}
