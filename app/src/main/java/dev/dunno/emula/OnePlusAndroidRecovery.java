package dev.dunno.emula;

import android.content.Context;
import android.content.pm.PackageInfo;

/** Handles pending vendor recovery for the supported Android 17 NFC service. */
final class OnePlusAndroidRecovery {
    private OnePlusAndroidRecovery() {}

    static boolean matchesStack(long code, String version) {
        return code == 170000014L && "17.0.0.260114".equals(version);
    }

    static boolean reviewedStack(Context context) {
        if (!OnePlusRawRecovery.isTarget()) return false;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo("com.android.nfc", 0);
            return matchesStack(info.getLongVersionCode(), info.versionName);
        } catch (Exception unavailable) { return false; }
    }

    static boolean otherPending(Context context) {
        return context.getSharedPreferences("legacy_nfc", Context.MODE_PRIVATE)
                .getBoolean("pending_restore", false)
                || XiaomiNfcController.hasPendingRestore(context)
                || MotorolaStNfcController.hasPendingRestore(context)
                || HonorStNfcController.hasPendingRestore(context);
    }

    static boolean eligible(boolean forced, boolean reviewed, boolean blocked, boolean androidApi,
            OnePlusVendorNfcController.Snapshot saved, boolean rawPending, boolean otherPending) {
        return !forced && reviewed && blocked && androidApi && !rawPending && !otherPending
                && saved != null && "0".equals(saved.mode)
                && OnePlusRawRecovery.FIRMWARE.equals(saved.fingerprint);
    }

    static boolean selected(Context context) {
        if (!reviewedStack(context)) return false;
        try {
            return eligible(context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                            .getBoolean("force_raw_mode", false), true,
                    OnePlusVendorNfcController.isPermissionBlocked(context),
                    OemNfcController.inspectCapabilities(context).androidOemExtension,
                    OnePlusVendorNfcController.loadSnapshot(context),
                    OnePlusRawRecovery.hasPendingRestore(context), otherPending(context));
        } catch (RuntimeException unreadable) { return false; }
    }

    static void prepare(Context context) throws Exception {
        OnePlusRawRecovery.prepare(new OnePlusRawRecovery.AndroidPlatform(context, context, true), true);
    }

    interface Reader { LegacyNfcDiagnostics.ProbeResult read() throws Exception; }

    static void observe(Context context, OemOperationReport report) {
        if (reviewedStack(context)) observe(report, () -> LegacyNfcDiagnostics.runReadOnlyProbe(context));
    }

    /** Observation only: the service status is not the controller's SET_CONFIG status. */
    static void observe(OemOperationReport report, Reader reader) {
        if (report == null || !report.isCompletedAndroidCall()) return;
        try {
            report.recordAndroidReadback(reader.read());
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            report.androidReadbackStatus = "UNAVAILABLE";
            report.notes.add("Post-Android-API readback: " + unavailable.getClass().getSimpleName()
                    + ": " + unavailable.getMessage());
        }
    }
}
