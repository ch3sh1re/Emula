package dev.dunno.emula;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;

/** Read-only firmware identity, method inspection and separately bounded OPlus getters. */
@SuppressLint("PrivateApi")
final class NfcStackDiagnostics {
    private NfcStackDiagnostics() {}

    static String collect(Context context) {
        NfcFileEvidence files = new NfcFileEvidence();
        StringBuilder text = new StringBuilder("NFC STACK DETAILS\n")
                .append("Read-only metadata and method inspection; configuration methods are not invoked.\n");
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo("com.android.nfc", 0);
            text.append("NFC package: ").append(info.packageName).append(' ')
                    .append(info.versionName).append(" (").append(info.getLongVersionCode()).append(")\n");
            ApplicationInfo app = info.applicationInfo;
            if (app != null) {
                text.append("NFC APK: ").append(app.sourceDir).append('\n');
                NfcFileEvidence.Result apk = app.sourceDir == null
                        ? new NfcFileEvidence.Result("path unavailable") : files.inspect(new File(app.sourceDir));
                text.append("NFC APK fingerprint: ").append(apk.description).append('\n');
                text.append(NfcFirmwareProfiles.describe(apk.sha256));
                text.append("NFC native library directory: ").append(app.nativeLibraryDir).append('\n');
                if (app.splitSourceDirs != null) {
                    for (int i = 0; i < Math.min(app.splitSourceDirs.length, 4); i++) {
                        String split = app.splitSourceDirs[i];
                        text.append("NFC split APK: ").append(split).append('\n');
                        appendFile(text, files, "NFC split fingerprint", split);
                    }
                    if (app.splitSourceDirs.length > 4) text.append("Additional split APKs omitted (collection limit).\n");
                }
                appendNativeFiles(text, files, app.nativeLibraryDir);
            }
        } catch (Exception | LinkageError error) {
            text.append("NFC package: unavailable (").append(describe(error)).append(")\n");
        }
        try {
            ApplicationInfo self = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_SHARED_LIBRARY_FILES);
            boolean found = false;
            if (self.sharedLibraryFiles != null) {
                for (String library : self.sharedLibraryFiles) {
                    if (library.toLowerCase(Locale.ROOT).contains("nfc")) {
                        text.append("NFC framework library: ").append(library).append('\n');
                        appendFile(text, files, "NFC framework fingerprint", library);
                        found = true;
                    }
                }
            }
            if (!found) text.append("NFC framework libraries: none reported for this app\n");
        } catch (Exception | LinkageError error) {
            text.append("NFC framework libraries: unavailable (").append(describe(error)).append(")\n");
        }
        for (String key : new String[] {"nfc.nfc_chip_type", "nfc.node"}) {
            try {
                String value = (String) Class.forName("android.os.SystemProperties")
                        .getMethod("get", String.class).invoke(null, key);
                text.append(key).append(": ").append(value == null || value.isEmpty()
                        ? "not reported" : value).append('\n');
            } catch (Exception | LinkageError error) {
                text.append(key).append(": unavailable (").append(describe(error)).append(")\n");
            }
        }
        String permission = "com.oplus.permission.safe.NFC";
        text.append(permission).append(": ").append(context.checkSelfPermission(permission)
                == PackageManager.PERMISSION_GRANTED ? "granted" : "not granted").append('\n');
        ClassLoader loader = context.getClassLoader();
        appendMethod(text, loader, "android.nfc.NfcOemExtension", "emulateNfcTechnologyATag",
                boolean.class, byte.class, byte.class, byte.class, byte[].class, byte.class, byte[].class);
        appendMethod(text, loader, "com.vendor.nfc.VendorNfcAdapter", "getVendorNfcAdapter");
        appendMethod(text, loader, "com.vendor.nfc.VendorNfcAdapter", "setHceTypeAConfig",
                boolean.class, byte[].class, byte[].class, byte[].class);
        appendMethod(text, loader, "com.nxp.nfc.NxpNfcAdapter", "setNfcTransitConfig", String.class);
        appendMethod(text, loader, "com.nxp.nfc.NxpNfcAdapter", "getNxpNfcAdapter", NfcAdapter.class);
        appendMethod(text, loader, "com.nxp.nfc.NxpNfcAdapter", "getNxpNfcAdapter", NfcAdapter.class, Context.class);
        appendMethod(text, loader, "com.nxp.nfc.NxpNfcAdapter", "setConfig", String.class, String.class);
        appendMethod(text, loader, "com.nxp.nfc.NxpNfcAdapter", "setConfig", String.class);
        appendMethod(text, loader, "com.st.android.nfc_extensions.NfcAdapterStExtensions", "programHceParameters",
                boolean.class, byte.class, byte.class, byte.class, byte[].class, byte.class, byte[].class);
        text.append("Class/method presence does not establish service permission or UID emulation support.");
        text.append("\nFile hashes identify code for firmware comparison; listed libraries are not proof that they are loaded.");
        String oplus = OplusNfcDiagnostics.collect(context);
        if (!oplus.isEmpty()) text.append("\n\n").append(oplus);
        return text.toString();
    }

    private static void appendFile(StringBuilder text, NfcFileEvidence files, String label, String path) {
        text.append(label).append(": ").append(path == null ? "path unavailable"
                : files.describe(new File(path))).append('\n');
    }

    private static void appendNativeFiles(StringBuilder text, NfcFileEvidence evidence, String path) {
        if (path == null) return;
        try {
            File[] files = new File(path).listFiles((dir, name) -> isNativeNfcLibraryName(name));
            if (files == null || files.length == 0) {
                text.append("NFC native files: not listed or directory unreadable\n");
                return;
            }
            Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            for (int i = 0; i < Math.min(files.length, 6); i++) {
                text.append("NFC native file: ").append(files[i].getPath()).append('\n');
                appendFile(text, evidence, "NFC native fingerprint", files[i].getPath());
            }
            if (files.length > 6) text.append("Additional native files omitted (collection limit).\n");
        } catch (SecurityException error) {
            text.append("NFC native files: unavailable (").append(describe(error)).append(")\n");
        }
    }

    static boolean isNativeNfcLibraryName(String name) {
        return name.endsWith(".so") && (name.contains("nfc") || name.startsWith("libnci"));
    }

    private static void appendMethod(StringBuilder text, ClassLoader loader,
            String owner, String method, Class<?>... parameters) {
        text.append(owner).append('#').append(method).append('(');
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) text.append(", ");
            text.append(parameters[i].getSimpleName());
        }
        text.append("): ").append(inspectMethod(loader, owner, method, parameters)).append('\n');
    }

    static String inspectMethod(ClassLoader loader, String owner, String method, Class<?>... parameters) {
        Class<?> type;
        try {
            type = Class.forName(owner, false, loader);
        } catch (Exception | LinkageError error) {
            return "class unavailable (" + describe(error) + ")";
        }
        try {
            type.getMethod(method, parameters);
            return "present; not invoked";
        } catch (Exception | LinkageError error) {
            return "class present; method unavailable (" + describe(error) + ")";
        }
    }

    private static String describe(Throwable error) {
        return error.getClass().getSimpleName() + (error.getMessage() == null
                ? "" : ": " + error.getMessage());
    }
}
