package dev.dunno.emula;

import android.content.Context;
import android.content.pm.PackageManager;

/** Read-only selection evidence and instructions; never selects or invokes a writer. */
final class OnePlusRecoveryGuidance {
    enum Status { NOT_TARGETED, NOT_ELIGIBLE, SNAPSHOT_UNREADABLE, RAW_RESTORE_PENDING,
        OTHER_RESTORE_PENDING, RAW_MODE_REQUIRED, SELECTED_PENDING_CHECKS,
        ANDROID_OEM_PENDING_CHECKS, ANDROID_OEM_READY }

    private OnePlusRecoveryGuidance() {}

    static Status status(boolean forced, boolean target, boolean blocked,
            OnePlusVendorNfcController.Snapshot saved, boolean archived,
            boolean rawPending, boolean otherPending, boolean reviewedAndroidApi) {
        if (!target) return Status.NOT_TARGETED;
        if (rawPending) return Status.RAW_RESTORE_PENDING;
        if (otherPending) return Status.OTHER_RESTORE_PENDING;
        if (OnePlusAndroidRecovery.eligible(forced, target, blocked, reviewedAndroidApi,
                saved, false, false)) return Status.ANDROID_OEM_PENDING_CHECKS;
        if (!forced && blocked && reviewedAndroidApi && saved == null) return Status.ANDROID_OEM_READY;
        if (!OnePlusRawRecovery.selectsTrial(true, true, blocked, saved, archived)) return Status.NOT_ELIGIBLE;
        return forced ? Status.SELECTED_PENDING_CHECKS : Status.RAW_MODE_REQUIRED;
    }

    static Status inspect(Context context) {
        if (!OnePlusRawRecovery.isTarget()) return Status.NOT_TARGETED;
        try {
            return status(context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                            .getBoolean("force_raw_mode", false), true,
                    OnePlusVendorNfcController.isPermissionBlocked(context),
                    OnePlusVendorNfcController.loadSnapshot(context), OnePlusRawRecovery.storage(context).archived(),
                    OnePlusRawRecovery.hasPendingRestore(context), OnePlusAndroidRecovery.otherPending(context),
                    OnePlusAndroidRecovery.reviewedStack(context)
                            && OemNfcController.inspectCapabilities(context).androidOemExtension);
        } catch (RuntimeException invalid) { return Status.SNAPSHOT_UNREADABLE; }
    }

    static String rawModeHint(Context context) {
        return inspect(context) == Status.RAW_MODE_REQUIRED
                ? context.getString(R.string.oneplus_raw_mode_required) : null;
    }

    static boolean vendorPermissionDenied(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (OnePlusVendorNfcController.isPermissionDenied(error)) return true;
        }
        return false;
    }

    static String failureHint(Context context, Throwable error) {
        return vendorPermissionDenied(error) ? rawModeHint(context) : null;
    }

    static String describe(Context context, boolean includeGuidance) {
        StringBuilder text = new StringBuilder(OnePlusRawRecovery.diagnosticSummary(context));
        if (!OnePlusRawRecovery.isTarget()) return text.toString();
        Status selection = inspect(context);
        text.append("OnePlus recovery selection: ").append(selection)
                .append("\nOnePlus vendor safe-NFC permission: ")
                .append(context.checkSelfPermission("com.oplus.permission.safe.NFC")
                        == PackageManager.PERMISSION_GRANTED ? "granted" : "missing")
                .append("\nOnePlus saved/current HCE metadata: ");
        try {
            OnePlusVendorNfcController.Snapshot saved = OnePlusVendorNfcController.loadSnapshot(context);
            text.append(saved == null ? "no pending vendor record"
                    : OnePlusRawRecovery.same(saved, OnePlusVendorNfcController.currentSnapshot(context))
                            ? "match" : "mismatch");
            text.append("\nNormal-mode Android OEM route: ").append(saved == null
                    ? "no pending vendor recovery"
                    : selection == Status.ANDROID_OEM_PENDING_CHECKS
                            ? "pending metadata and controller checks"
                            : "deferred by pending OnePlus vendor recovery");
        } catch (RuntimeException unreadable) { text.append("unreadable"); }
        if (!includeGuidance) return text.append('\n').toString();
        if (selection == Status.ANDROID_OEM_PENDING_CHECKS || selection == Status.ANDROID_OEM_READY) {
            text.append("\nNext step: keep Raw mode off and tap a card. This firmware's Android API uses "
                    + "WRITE_SECURE_SETTINGS. Send a report after the attempt and record what the reader sees; "
                    + "the service's success status does not expose controller write results.");
        } else if (selection == Status.RAW_MODE_REQUIRED) {
            text.append("\nNext step: ").append(context.getString(R.string.oneplus_raw_mode_required));
        } else if (selection == Status.SELECTED_PENDING_CHECKS) {
            text.append("\nNext step: activate a card with Emula open. Selection does not prove activation; "
                    + "snapshot and controller checks must still pass.");
        }
        return text.append('\n').toString();
    }
}
