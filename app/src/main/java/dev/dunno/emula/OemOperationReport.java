package dev.dunno.emula;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
        XIAOMI_NXP_TRANSIT
    }

    final Operation operation;
    final List<String> notes = new ArrayList<>();
    Path path;
    String stage = "STARTING";
    String profile;
    byte[] atqa;
    byte[] sak;
    byte[] uid;
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

    OemOperationReport(Operation operation) {
        this.operation = operation;
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

        if (path == Path.MOTOROLA_ST_VENDOR_HCE) {
            text.append('\n').append("MOTOROLA ST VENDOR HCE\n");
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
            text.append('\n').append("PRECEDING MOTOROLA ST ATTEMPT\n")
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
