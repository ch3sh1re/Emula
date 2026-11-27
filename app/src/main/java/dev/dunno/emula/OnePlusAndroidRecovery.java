package dev.dunno.emula;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks inactive OnePlus vendor records without replaying them across a system update. */
final class OnePlusAndroidRecovery {
    private static final Pattern STOCK_BUILD = Pattern.compile(
            "OnePlus/([^/:\\s]+)/([^/:\\s]+):(16|17)/[^/:\\s]+/[^/:\\s]+:user/release-keys");

    private OnePlusAndroidRecovery() {}

    static boolean onePlusAndroid17(int sdk, String manufacturer) {
        return sdk == 37 && "OnePlus".equalsIgnoreCase(manufacturer);
    }

    // Old app-private snapshots contain only the CDD fingerprint. Check both builds
    // against the running product and device, without tying them to an OTA sequence number.
    static boolean updatedFrom16(String saved, String current, String product, String device) {
        return product != null && device != null && current != null
                && current.startsWith("OnePlus/" + product + "/" + device + ":17/")
                && sameDeviceUpdate(saved, current);
    }

    static boolean sameDeviceUpdate(String saved, String current) {
        if (saved == null || current == null) return false;
        Matcher before = STOCK_BUILD.matcher(saved);
        Matcher after = STOCK_BUILD.matcher(current);
        return before.matches() && after.matches()
                && "16".equals(before.group(3)) && "17".equals(after.group(3))
                && before.group(1).equals(after.group(1)) && before.group(2).equals(after.group(2));
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

    static boolean eligible(boolean forced, OnePlusVendorNfcController.Snapshot saved,
            boolean rawPending, boolean otherPending, String current, String product, String device,
            BooleanSupplier sameBuildAndroidRoute) {
        if (forced || rawPending || otherPending || saved == null || !"0".equals(saved.mode)
                || current == null || current.isEmpty()) return false;
        if (current.equals(saved.fingerprint)) return sameBuildAndroidRoute.getAsBoolean();
        return updatedFrom16(saved.fingerprint, current, product, device);
    }

    static boolean selected(Context context) {
        if (!onePlusAndroid17(Build.VERSION.SDK_INT, Build.MANUFACTURER)) return false;
        try {
            OnePlusVendorNfcController.Snapshot saved = OnePlusVendorNfcController.loadSnapshot(context);
            if (saved == null) return false;
            return eligible(context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                            .getBoolean("force_raw_mode", false), saved,
                    OnePlusRawRecovery.hasPendingRestore(context), otherPending(context),
                    Build.FINGERPRINT, Build.PRODUCT, Build.DEVICE, () -> reviewedStack(context)
                            && OnePlusVendorNfcController.isPermissionBlocked(context)
                            && OemNfcController.inspectCapabilities(context).androidOemExtension);
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
        if (!onePlusAndroid17(Build.VERSION.SDK_INT, Build.MANUFACTURER)) return "";
        SharedPreferences archive = updateArchive(context);
        try {
            if (!Build.FINGERPRINT.equals(archive.getString("archive_observed_firmware", null))) return "";
            return "\nOnePlus vendor recovery archive: " + archive.getString("archive_firmware", "")
                    + "\nObserved on: " + Build.FINGERPRINT + "\n";
        } catch (ClassCastException unreadable) {
            return "\nOnePlus vendor recovery archive: unreadable\n";
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
