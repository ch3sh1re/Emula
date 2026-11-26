package dev.dunno.emula;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory evidence for the most recent Android/OEM NFC operation. */
final class OemOperationReport {
    enum Operation {
        ACTIVATE,
        DEACTIVATE,
        STARTUP_RESTORE
    }

    enum Path {
        ANDROID_OEM_EXTENSION,
        ONEPLUS_VENDOR_HCE,
        MOTOROLA_ST_VENDOR_HCE,
        HONOR_ST_VENDOR_HCE,
        XIAOMI_NXP_TRANSIT
    }

    final Operation operation;
    final List<String> notes = new ArrayList<>();
    Path path;
    String stage = "STARTING";
    String profile;
    byte[] atqa;
    byte[] sak;
    byte[] rats;
    byte[] uid;
    Integer androidStatus;
    String androidReadbackStatus;
    LegacyNfcDiagnostics.ProbeResult androidReadback;
    Boolean vendorLibraryAvailable;
    Boolean vendorAdapterResolved;
    Boolean vendorMethodResolved;
    String vendorResolutionFailure;
    String snapshotState;
    String originalGuardMode;
    Boolean vendorCallSucceeded;
    String guardModeAfterVendorCall;
    Boolean guardNeutralized;
    String guardModeAfterNeutralize;
    Boolean originalMetadataRestored;
    Boolean rollbackAttempted;
    Boolean rollbackSucceeded;
    String rollbackFailure;
    String result;
    String failure;
    OemOperationReport stRollback;
    OemOperationReport stAttempt;
    String chipId;
    String vendorFirmware;
    String vendorConfig;
    Integer vendorStatus;
    OemOperationReport vendorRollback;
    final List<TransitCall> transitCalls = new ArrayList<>();

    static final class TransitCall {
        final String stage;
        final String request;
        Integer status;
        String failure;

        TransitCall(String stage, String request) {
            this.stage = stage;
            this.request = request;
        }
    }

    OemOperationReport(Operation operation) {
        this.operation = operation;
    }

    boolean isCompletedAndroidCall() {
        return path == Path.ANDROID_OEM_EXTENSION && Integer.valueOf(0).equals(androidStatus)
                && "COMPLETE".equals(stage);
    }

    void recordAndroidReadback(LegacyNfcDiagnostics.ProbeResult probe) {
        androidReadback = probe;
        androidReadbackStatus = classifyAndroidReadback(probe.configuration);
    }

    String classifyAndroidReadback(Map<Integer, byte[]> values) {
        if (operation != Operation.ACTIVATE) return "OBSERVED_AFTER_DISABLE";
        if (atqa == null || atqa.length != 2 || sak == null || rats == null || uid == null) {
            return "EXPECTED_VALUES_UNAVAILABLE";
        }
        Map<Integer, byte[]> expected = new LinkedHashMap<>();
        expected.put(0x30, new byte[] {atqa[0]});
        expected.put(0x31, new byte[] {atqa[1]});
        expected.put(0x32, sak);
        expected.put(0x33, uid);
        expected.put(0x58, rats);
        expected.put(0x85, new byte[] {0});
        boolean incomplete = false;
        for (Map.Entry<Integer, byte[]> entry : expected.entrySet()) {
            byte[] actual = values.get(entry.getKey());
            if (actual == null) incomplete = true;
            else if (!Arrays.equals(entry.getValue(), actual)) return "MISMATCH";
        }
        return incomplete ? "INCOMPLETE" : "MATCH";
    }

    String formatForDisplay() {
        return format(false);
    }

    String formatDetailed() {
        return format(true);
    }

    private String format(boolean detailed) {
        StringBuilder text = new StringBuilder("LAST OEM NFC OPERATION\n\n")
                .append("Operation: ").append(operation).append('\n')
                .append("Path: ").append(valueOrDash(path == null ? null : path.name()))
                .append('\n')
                .append("Stage: ").append(valueOrDash(stage)).append('\n');
        append(text, "Result", result);
        append(text, "Profile", profile);
        appendBytes(text, "ATQA", atqa);
        appendBytes(text, "SAK", sak);
        appendBytes(text, "UID", uid);

        if (path == Path.ANDROID_OEM_EXTENSION) {
            appendBytes(text, "RATS TB1", rats);
            append(text, "Android service status", androidStatus == null ? null : androidStatus.toString());
            append(text, "Immediate Android API readback", androidReadbackStatus);
            if (androidReadback != null) {
                text.append("\nPOST-ANDROID-API OBSERVATION\n")
                        .append(detailed ? androidReadback.detailedSummary : androidReadback.userSummary)
                        .append('\n');
            }
        }

        if (path == Path.ONEPLUS_VENDOR_HCE || vendorLibraryAvailable != null) {
            text.append('\n').append("ONEPLUS VENDOR HCE\n");
            append(text, "Library", outcome(vendorLibraryAvailable));
            append(text, "Adapter", outcome(vendorAdapterResolved));
            append(text, "Method", outcome(vendorMethodResolved));
            append(text, "Snapshot", snapshotState);
            append(text, "Original guard mode", originalGuardMode);
            append(text, "Vendor call", outcome(vendorCallSucceeded));
            append(text, "Guard after vendor call", guardModeAfterVendorCall);
            append(text, "Guard neutralized", outcome(guardNeutralized));
            append(text, "Guard after neutralize", guardModeAfterNeutralize);
            append(text, "Original metadata restored", outcome(originalMetadataRestored));
            if (vendorResolutionFailure != null) {
                append(text, "Vendor resolution failure", vendorResolutionFailure);
            }
        }

        if (path == Path.MOTOROLA_ST_VENDOR_HCE || path == Path.HONOR_ST_VENDOR_HCE) {
            text.append('\n').append(path == Path.HONOR_ST_VENDOR_HCE
                    ? "HONOR ST VENDOR HCE\n" : "MOTOROLA ST VENDOR HCE\n");
            append(text, "Snapshot", snapshotState);
            append(text, "Vendor call returned (void)", outcome(vendorCallSucceeded));
            append(text, "External reader", "not verified by this report");
        }

        if (path == Path.XIAOMI_NXP_TRANSIT) {
            text.append('\n').append("XIAOMI NXP TRANSIT TRIAL\n");
            append(text, "Chip ID (firmware-reported)", chipId);
            append(text, "NXP firmware bytes", vendorFirmware);
            append(text, "Recovery", snapshotState);
            append(text, "setConfig status", vendorStatus == null ? null : vendorStatus.toString());
            append(text, "setConfig request", vendorConfig);
            for (int i = 0; i < transitCalls.size(); i++) {
                TransitCall call = transitCalls.get(i);
                text.append("\nTransit call ").append(i + 1).append('\n');
                append(text, "Stage", call.stage);
                append(text, "Request", call.request);
                append(text, "Vendor status", call.status == null ? "no result" : call.status.toString());
                append(text, "Failure", call.failure);
            }
            append(text, "External reader / payment / original override", "not verified by this report");
        }

        if (rollbackAttempted != null || rollbackFailure != null) {
            text.append('\n').append("ROLLBACK\n");
            append(text, "Attempted", outcome(rollbackAttempted));
            append(text, "Succeeded", outcome(rollbackSucceeded));
            append(text, "Failure", rollbackFailure);
        }
        if (failure != null) {
            text.append('\n');
            append(text, "Failure", failure);
        }
        if (detailed) {
            for (String note : notes) append(text, "Note", note);
        }
        if (stRollback != null) {
            text.append('\n').append("ST ROLLBACK EVIDENCE\n")
                    .append(stRollback.format(detailed));
        }
        if (stAttempt != null) {
            text.append('\n').append(stAttempt.path == Path.HONOR_ST_VENDOR_HCE
                    ? "PRECEDING HONOR ST ATTEMPT\n" : "PRECEDING MOTOROLA ST ATTEMPT\n")
                    .append(stAttempt.format(detailed));
        }
        if (vendorRollback != null) {
            text.append('\n').append("VENDOR ROLLBACK EVIDENCE\n")
                    .append(vendorRollback.format(detailed));
        }
        return text.toString();
    }

    private static void append(StringBuilder text, String label, String value) {
        if (value != null) text.append(label).append(": ").append(value).append('\n');
    }

    private static void appendBytes(StringBuilder text, String label, byte[] value) {
        if (value != null) append(text, label, RawNciCodec.hex(value));
    }

    private static String outcome(Boolean value) {
        if (value == null) return "not attempted";
        return value ? "OK" : "FAILED";
    }

    private static String valueOrDash(String value) {
        return value == null || value.isEmpty() ? "—" : value;
    }

    static byte[] copy(byte[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }
}
