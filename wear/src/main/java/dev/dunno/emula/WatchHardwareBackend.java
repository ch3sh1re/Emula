package dev.dunno.emula;

import android.app.Activity;
import android.os.Build;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;

/** OEM-only activation; retained raw support restores snapshots from older test builds. */
final class WatchHardwareBackend implements WatchNfcSession.Backend {
    private static final int[] MODIFIED_PARAMETERS = {0x85, 0x30, 0x31, 0x32, 0x33, 0x58};
    private final Activity activity;
    private final StringBuilder evidence;

    WatchHardwareBackend(Activity activity, StringBuilder evidence) {
        this.activity = activity;
        this.evidence = evidence;
    }

    @Override
    public String snapshot() throws Exception {
        LegacyNfcDiagnostics.ProbeResult probe = LegacyNfcDiagnostics.runReadOnlyProbe(activity);
        evidence.append("\nBEFORE RAW WRITE\n").append(probe.detailedSummary).append('\n');
        if (!LegacyNfcController.isCompleteExperimentalSnapshot(probe.configuration)) {
            throw new IllegalStateException("Original NFC configuration is incomplete; raw write cancelled");
        }
        Map<Integer, byte[]> modified = new LinkedHashMap<>();
        for (int id : MODIFIED_PARAMETERS) modified.put(id, probe.configuration.get(id));
        return RawNciCodec.encodeConfiguration(modified);
    }

    @Override
    public void activate(WatchNfcSession.Path path, byte[] uid, String snapshot) throws Exception {
        if (path != WatchNfcSession.Path.OEM || !UidCodec.isSupportedLength(uid.length)
                || !WatchReleasePolicy.supportsDevice(Build.MANUFACTURER, Build.MODEL)) {
            throw new UnsupportedOperationException("Watch activation requires SM-L355F, OEM API, and a 4-, 7-, or 10-byte UID");
        }
        try {
            OemNfcController.setCardEnabled(activity, uid, true);
            // The AOSP service can return OK without propagating individual SET statuses.
            // Read back when the firmware exposes raw GET; never infer reader activation.
            LegacyNfcDiagnostics.ProbeResult readback = null;
            if (LegacyNfcDiagnostics.isRawNciApiAvailable(activity)) {
                try {
                    readback = LegacyNfcDiagnostics.runReadOnlyProbe(activity);
                    evidence.append("\nOEM READBACK\n").append(readback.detailedSummary);
                } catch (Exception readFailure) {
                    evidence.append("\nOEM readback unavailable: ").append(readFailure);
                }
            }
            if (readback != null && readback.configuration.containsKey(0x33)
                    && !Arrays.equals(uid, readback.configuration.get(0x33))) {
                throw new IllegalStateException("OEM API reported success, but UID readback did not match");
            }
        } finally {
            evidence.append("\nOEM ACTIVATION\n").append(OemNfcController.lastOperationDetailed());
        }
    }

    @Override
    public void restore(WatchNfcSession.Pending pending) throws Exception {
        if (pending.path == WatchNfcSession.Path.OEM) {
            try {
                OemNfcController.restoreDefaults(activity);
            } finally {
                evidence.append("\nOEM RESTORE\n").append(OemNfcController.lastOperationDetailed());
            }
            return;
        }
        try {
            Map<Integer, byte[]> original = RawNciCodec.decodeConfiguration(pending.snapshot);
            if (!LegacyNfcController.isCompleteExperimentalSnapshot(original)) {
                throw new IllegalStateException("Saved NFC snapshot is incomplete; restore blocked");
            }
            boolean alreadyRestored = false;
            try {
                LegacyNfcDiagnostics.ProbeResult probe = LegacyNfcDiagnostics.runReadOnlyProbe(activity);
                evidence.append("\nBEFORE RESTORE\n").append(probe.detailedSummary);
                alreadyRestored = LegacyNfcController.configurationEquals(original, probe.configuration);
                if (alreadyRestored) LegacyNfcController.recordAlreadyRestored(
                        RawOperationReport.Operation.DEACTIVATE, probe);
            } catch (Exception readFailure) {
                evidence.append("\nRestore pre-read failed: ").append(readFailure);
            }
            if (!alreadyRestored) LegacyNfcController.setCardEnabled(activity, new byte[4], false,
                    RawOperationReport.Operation.DEACTIVATE, true, original);
        } finally {
            evidence.append("\nRAW RESTORE\n").append(LegacyNfcController.lastOperationDetailed());
        }
    }
}
