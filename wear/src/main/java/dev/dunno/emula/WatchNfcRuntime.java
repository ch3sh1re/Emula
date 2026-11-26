package dev.dunno.emula;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.os.Build;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The activity, tile and stop alarm share one queue and the same recovery record. */
final class WatchNfcRuntime {
    static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    private WatchNfcRuntime() {}

    static WatchNfcSession.Path path(Context context) {
        return WatchActivationPolicy.select(Build.VERSION.SDK_INT,
                context.getPackageManager().hasSystemFeature("android.hardware.type.watch"),
                OemNfcController.inspectCapabilities(context).androidOemExtension,
                LegacyNfcDiagnostics.isRawNciApiAvailable(context),
                !"UNAVAILABLE".equals(LegacyNfcController.pollingMethod(context)));
    }

    static boolean hasRawConsent(Context context) {
        return WatchActivationPolicy.hasConsent(Build.FINGERPRINT, context
                .getSharedPreferences("watch_experiments", Context.MODE_PRIVATE)
                .getString("rawFirmware", ""));
    }

    static void requireNfc(Context context, NfcAdapter adapter) {
        if (adapter == null) throw condition(context, WatchUiError.NFC_UNAVAILABLE, R.string.no_adapter);
        if (!adapter.isEnabled()) throw condition(context, WatchUiError.NFC_OFF, R.string.nfc_off);
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            throw condition(context, WatchUiError.PERMISSION, R.string.grant_required);
        }
    }

    private static WatchUiError.Condition condition(Context context, WatchUiError error, int message) {
        return new WatchUiError.Condition(error, context.getString(message));
    }

    static SharedPreferences tilePreferences(Context context) {
        return context.getSharedPreferences("watch_tile", Context.MODE_PRIVATE);
    }

    static void recordTileResult(Context context, String operation, StringBuilder evidence, Exception failure) {
        String detail = failure == null ? "" : android.util.Log.getStackTraceString(failure);
        tilePreferences(context).edit().putString("lastOperation",
                operation + "\n" + evidence + detail)
                .putString("error", failure == null ? "" : failure.getMessage()).apply();
    }
}
