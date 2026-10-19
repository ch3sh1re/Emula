package dev.dunno.emula;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.os.Build;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;

/** Resolves capability methods without invoking NFC configuration or polling controls. */
final class WatchNfcCapabilities {
    final String report;
    final boolean adapterAvailable;
    final boolean nfcEnabled;
    final boolean permissionGranted;
    final boolean rawApiAvailable;

    private WatchNfcCapabilities(String report, boolean adapterAvailable,
            boolean nfcEnabled, boolean permissionGranted, boolean rawApiAvailable) {
        this.report = report;
        this.adapterAvailable = adapterAvailable;
        this.nfcEnabled = nfcEnabled;
        this.permissionGranted = permissionGranted;
        this.rawApiAvailable = rawApiAvailable;
    }

    static WatchNfcCapabilities inspect(Context context) {
        PackageManager packages = context.getPackageManager();
        boolean permission = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        StringBuilder report = new StringBuilder("EMULA WATCH READ-ONLY DIAGNOSTICS\n")
                .append("Time: ").append(Instant.now()).append('\n')
                .append("Package: ").append(BuildConfig.APPLICATION_ID).append('\n')
                .append("Version: ").append(BuildConfig.VERSION_NAME).append(" (")
                .append(BuildConfig.VERSION_CODE).append(")\n\nDEVICE\n")
                .append("Manufacturer: ").append(Build.MANUFACTURER).append('\n')
                .append("Model: ").append(Build.MODEL).append('\n')
                .append("Device: ").append(Build.DEVICE).append('\n')
                .append("Product: ").append(Build.PRODUCT).append('\n')
                .append("Hardware: ").append(Build.HARDWARE).append('\n')
                .append("Fingerprint: ").append(Build.FINGERPRINT).append('\n')
                .append("Android: ").append(Build.VERSION.RELEASE)
                .append(" / API ").append(Build.VERSION.SDK_INT).append('\n')
                .append("Security patch: ").append(Build.VERSION.SECURITY_PATCH)
                .append("\n\nFEATURES\n");
        for (String feature : new String[] {
                "android.hardware.type.watch", "android.hardware.nfc",
                "android.hardware.nfc.hce", "android.hardware.nfc.hcef",
                "android.hardware.nfc.ese", "android.hardware.nfc.uicc"}) {
            report.append(feature).append(": ")
                    .append(packages.hasSystemFeature(feature)).append('\n');
        }
        report.append("\nCAPABILITIES\nWRITE_SECURE_SETTINGS: ")
                .append(permission ? "granted" : "not granted").append('\n');

        NfcAdapter adapter;
        boolean enabled;
        try {
            adapter = NfcAdapter.getDefaultAdapter(context);
            enabled = adapter != null && adapter.isEnabled();
        } catch (Throwable failure) {
            report.append("NFC adapter failure: ").append(describe(failure)).append('\n');
            return new WatchNfcCapabilities(report.toString(), false, false, permission, false);
        }
        report.append("NFC adapter: ").append(adapter == null ? "unavailable" : "available")
                .append('\n').append("NFC enabled: ").append(enabled).append('\n');
        if (adapter == null) {
            return new WatchNfcCapabilities(report.toString(), false, false, permission, false);
        }

        boolean raw = LegacyNfcDiagnostics.isRawNciApiAvailable(context);
        report.append("Raw NCI API: ").append(raw ? "available" : "unavailable").append('\n');
        try {
            Method getter = findMethod(adapter.getClass(), "getNfcOemExtension");
            report.append("getNfcOemExtension method: available\n");
            Object extension = getter.invoke(adapter);
            if (extension == null) throw new IllegalStateException("OEM extension is null");
            report.append("OEM extension object: available\n");
            appendMethod(report, extension.getClass(), "emulateNfcTechnologyATag",
                    boolean.class, byte.class, byte.class, byte.class,
                    byte[].class, byte.class, byte[].class);
            appendMethod(report, extension.getClass(), "pausePolling", long.class);
            appendMethod(report, extension.getClass(), "resumePolling");
        } catch (Throwable failure) {
            report.append("OEM extension resolution failure: ").append(describe(failure))
                    .append('\n');
        }
        appendMethod(report, adapter.getClass(), "setDiscoveryTechnology",
                android.app.Activity.class, int.class, int.class);
        report.append("\nMethod presence does not confirm permission to change UID, ")
                .append("controller support, or activation by an external reader.\n")
                .append("No emulation, SET_CONFIG, or polling changes are performed.\n");
        return new WatchNfcCapabilities(report.toString(), true, enabled, permission, raw);
    }

    private static void appendMethod(StringBuilder report, Class<?> owner, String name,
            Class<?>... parameters) {
        try {
            findMethod(owner, name, parameters);
            report.append(name).append(" method: available\n");
        } catch (Throwable failure) {
            report.append(name).append(" method: unavailable (")
                    .append(describe(failure)).append(")\n");
        }
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        try {
            return owner.getMethod(name, parameters);
        } catch (NoSuchMethodException failure) {
            return owner.getDeclaredMethod(name, parameters);
        }
    }

    static String describe(Throwable failure) {
        while (failure instanceof InvocationTargetException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
    }
}
