package dev.dunno.emula;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Handles pending vendor recovery for the supported Android 17 NFC service. */
final class OnePlusAndroidRecovery {
    private OnePlusAndroidRecovery() {}

    // Old snapshots contain only a fingerprint. Build the complete source identity
    // using this device's product so regional products are never interchanged.
    static boolean fromReviewedAndroid16(String saved, String product) {
        return product != null && !product.isEmpty() && ("OnePlus/" + product
                + "/OP611FL1:16/BP2A.250605.015/B.R4T3.1abf132_aeee51_aea830:user/release-keys").equals(saved);
    }

    static boolean reviewedStack(Context context) {
        return OnePlusVendorNfcController.reviewedAndroidService(context);
    }

    static boolean otherPending(Context context) {
        return context.getSharedPreferences("legacy_nfc", Context.MODE_PRIVATE)
                .getBoolean("pending_restore", false)
                || OppoNxpRawController.hasPendingRestore(context)
                || XiaomiNfcController.hasPendingRestore(context)
                || MotorolaStNfcController.hasPendingRestore(context)
                || StPhoneNfcController.hasPendingRestore(context) || HonorStNfcController.hasPendingRestore(context);
    }

    static boolean eligible(boolean forced, boolean reviewed, boolean blocked, boolean androidApi,
            OnePlusVendorNfcController.Snapshot saved, boolean rawPending, boolean otherPending,
            String currentFirmware, String product) {
        return !forced && reviewed && blocked && androidApi && !rawPending && !otherPending
                && currentFirmware != null && !currentFirmware.isEmpty() && saved != null && "0".equals(saved.mode)
                && (currentFirmware.equals(saved.fingerprint) || fromReviewedAndroid16(saved.fingerprint, product));
    }

    static boolean selected(Context context) {
        if (!reviewedStack(context)) return false;
        try {
            return eligible(context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                            .getBoolean("force_raw_mode", false), true,
                    OnePlusVendorNfcController.isPermissionBlocked(context),
                    OemNfcController.inspectCapabilities(context).androidOemExtension,
                    OnePlusVendorNfcController.loadSnapshot(context),
                    OnePlusRawRecovery.hasPendingRestore(context), otherPending(context),
                    Build.FINGERPRINT, Build.PRODUCT);
        } catch (RuntimeException unreadable) { return false; }
    }

    static void prepare(Context context) throws Exception {
        OnePlusVendorNfcController.Snapshot saved = OnePlusVendorNfcController.loadSnapshot(context);
        if (saved == null) return;
        OnePlusRawRecovery.prepareForAndroid(new OnePlusRawRecovery.AndroidPlatform(context, context, true),
                saved.fingerprint, Build.FINGERPRINT);
    }

    static SharedPreferences updateArchive(Context context) {
        // Keep an earlier OTA's record intact when the phone updates again.
        UUID buildId = UUID.nameUUIDFromBytes(Build.FINGERPRINT.getBytes(StandardCharsets.UTF_8));
        return context.getSharedPreferences("oneplus_android_ota_archive_" + buildId, Context.MODE_PRIVATE);
    }

    static String updateSummary(Context context) {
        if (!reviewedStack(context)) return "";
        SharedPreferences archive = updateArchive(context);
        try {
            if (!Build.FINGERPRINT.equals(archive.getString("archive_observed_firmware", null))) return "";
            return "\nOnePlus Android OEM recovery archive: " + archive.getString("archive_firmware", "")
                    + "\nObserved on: " + Build.FINGERPRINT + "\n";
        } catch (ClassCastException unreadable) {
            return "\nOnePlus Android OEM recovery archive: unreadable\n";
        }
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
