package dev.dunno.emula;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;

import java.io.FileInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** HONOR 90 candidate, based on the published HNBRC-M1 NFC service; reader unverified. */
@SuppressLint("PrivateApi")
final class HonorStNfcController {
    private static final String ADAPTER = "com.st.android.nfc_extensions.NfcAdapterStExtensions";
    private static final String DESCRIPTOR = "com.st.android.nfc_extensions.INfcAdapterStExtensions";
    private static final String PREFS = "honor_st_hce_restore";
    private static FutureTask<String> nxpProbe;

    private HonorStNfcController() {}

    static boolean isTargetDevice() {
        return isTargetDevice(Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL, Build.DEVICE);
    }

    static boolean isTargetDevice(int sdk, String manufacturer, String model, String device) {
        return sdk >= 35 && matchesDevice(manufacturer, model, device);
    }

    static boolean matchesDevice(String manufacturer, String model, String device) {
        return "HONOR".equalsIgnoreCase(manufacturer) && "REA-NX9".equalsIgnoreCase(model)
                && "HNREA".equalsIgnoreCase(device);
    }

    static boolean hasPendingRestore(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("pending", false);
    }

    /** No controller GET/SET or system-setting changes during capability inspection. */
    static MotorolaStNfcController.Probe probe(Context context) {
        if (!isTargetDevice()) return new MotorolaStNfcController.Probe(false, false, null);
        try {
            String excluded = nxpControllerFailure();
            if (excluded != null) return new MotorolaStNfcController.Probe(true, false, excluded);
            resolveApi(context);
            return new MotorolaStNfcController.Probe(true, true, null);
        } catch (Exception | LinkageError failure) {
            return new MotorolaStNfcController.Probe(true, false, describe(failure));
        }
    }

    static void setCardEnabled(Context context, byte[] uid, boolean enabled,
            OemOperationReport report) throws Exception {
        report.path = OemOperationReport.Path.HONOR_ST_VENDOR_HCE;
        report.profile = "cts-type-4a / HONOR REA-NX9 ST candidate (reader unverified)";
        report.atqa = new byte[] {0x06, 0x0C};
        report.sak = new byte[] {0x20};
        report.uid = OemOperationReport.copy(uid);
        report.notes.add("Writes use ST programHceParameters/setNciConfig; only GET_CONFIG uses raw NCI. "
                + "HONOR's value-only getNciConfig cannot reliably prove empty parameters.");
        if (enabled && !isTargetDevice()) {
            throw new IllegalStateException("HONOR ST activation is not targeted on this device/build");
        }
        Store store = new Store(context);
        if (!enabled && store.load() == null) {
            report.result = "NO_PENDING_ST_RESTORE";
            report.stage = "COMPLETE";
            return;
        }
        report.stage = "RESOLVE_HONOR_ST_API";
        MotorolaStNfcController.Api api = resolveApi(context);
        // Share the tested snapshot/write/readback/rollback transaction, with a separate store.
        MotorolaStNfcController.change(api, store, Build.FINGERPRINT, uid, enabled, report);
    }

    private static MotorolaStNfcController.Api resolveApi(Context context) throws Exception {
        if (!matchesDevice(Build.MANUFACTURER, Build.MODEL, Build.DEVICE)) {
            throw new IllegalStateException("HONOR ST API is not allowed on this device");
        }
        String excluded = nxpControllerFailure();
        if (excluded != null) throw new IllegalStateException(excluded);
        Class<?> type = Class.forName(ADAPTER, false, context.getClassLoader());
        Method program = type.getMethod("programHceParameters", boolean.class, byte.class,
                byte.class, byte.class, byte[].class, byte.class, byte[].class);
        Method set = type.getMethod("setNciConfig", int.class, byte[].class);
        if (program.getReturnType() != void.class || set.getReturnType() != void.class) {
            throw new NoSuchMethodException("Unexpected HONOR ST API signature");
        }
        Object service = invoke(type.getMethod("getNfcAdapterStExtensionsInterface"), null);
        if (!(service instanceof IInterface)) throw new IllegalStateException("ST service is unavailable");
        IBinder binder = ((IInterface) service).asBinder();
        if (binder == null || !binder.isBinderAlive() || !DESCRIPTOR.equals(binder.getInterfaceDescriptor())) {
            throw new IllegalStateException("ST Binder is unavailable or has an unexpected interface");
        }
        Object receiver = type.getConstructor().newInstance();
        if (!LegacyNfcDiagnostics.isRawNciApiAvailable(context)) {
            throw new IllegalStateException("Read-only GET_CONFIG API is required for HONOR ST verification");
        }
        return new MotorolaStNfcController.Api() {
            @Override public void program(boolean enabled, byte[] uid) throws Exception {
                invoke(program, receiver, enabled, (byte) 0x06, (byte) 0x0C, (byte) 0x20,
                        Arrays.copyOf(uid, uid.length), (byte) 0x40, new byte[0]);
            }
            @Override public void setConfiguration(int id, byte[] value) throws Exception {
                invoke(set, receiver, id, Arrays.copyOf(value, value.length));
            }
            @Override public Map<Integer, byte[]> readConfiguration(OemOperationReport report) throws Exception {
                LegacyNfcDiagnostics.ProbeResult probe = LegacyNfcDiagnostics.runReadOnlyProbe(context);
                report.notes.add(report.stage + "\n" + probe.detailedSummary);
                return probe.configuration;
            }
        };
    }

    /** Run on the NFC worker when preparing a report. All calls here are read-only. */
    static String diagnostics(Context context) {
        if (!"HONOR".equalsIgnoreCase(Build.MANUFACTURER)) return "";
        StringBuilder text = new StringBuilder("HONOR NFC DETAILS\n")
                .append("ST candidate scope: ").append(isTargetDevice() ? "REA-NX9 / API 35+" : "not targeted")
                .append("\nHardware/reader support: unverified\n");
        appendNfcPackage(context, text);
        for (String key : new String[] {"nfc.nfc_chip_type", "nfc.node", "persist.st_nfc_no_extensions"}) {
            try {
                String value = property(key);
                text.append(key).append(": ").append("".equals(value) ? "not reported" : value).append('\n');
            } catch (Exception | LinkageError error) {
                text.append(key).append(": unavailable (").append(describe(error)).append(")\n");
            }
        }
        try (FileInputStream file = new FileInputStream("/sys/nfc/nfc_chip_type")) {
            byte[] value = new byte[256];
            int length = file.read(value);
            text.append("sysfs chip: ").append(length > 0
                    ? new String(value, 0, length, StandardCharsets.UTF_8).trim() : "not reported").append('\n');
        } catch (Exception error) {
            text.append("sysfs chip: unavailable (").append(describe(error)).append(")\n");
        }
        // Presence is useful evidence even on an NXP phone; do not call its configuration setters.
        for (String name : new String[] {"com.hihonor.nfc.HwNfcAdapter", "com.nxp.nfc.NxpNfcAdapter", ADAPTER}) {
            try {
                Class.forName(name, false, context.getClassLoader());
                text.append(name).append(": present\n");
            } catch (Exception | LinkageError error) {
                text.append(name).append(": ").append(describe(error)).append('\n');
            }
        }
        MotorolaStNfcController.Probe probe = probe(context);
        text.append("ST methods/Binder/GET API: ").append(probe.available ? "resolved" : "unavailable");
        if (probe.failure != null) text.append("\nST resolution failure: ").append(probe.failure);
        text.append("\nPending HONOR ST restore: ").append(hasPendingRestore(context) ? "yes" : "no");
        if (isTargetDevice() && nxpControllerFailure() != null) {
            text.append("\n\n").append(nxpDiagnostics(context));
        }
        return text.toString();
    }

    /** Class presence does not identify the active chip: HONOR ships both vendor libraries. */
    static boolean isNxpController(String chip, String node) {
        String name = chip == null ? "" : chip.trim().toLowerCase(Locale.ROOT);
        String path = node == null ? "" : node.trim().toLowerCase(Locale.ROOT);
        return name.equals("sn220u") || name.equals("sn110u") || name.equals("pn560")
                || name.equals("pn80t") || path.equals("/dev/nq-nci") || path.equals("/dev/pn544");
    }

    private static String nxpControllerFailure() {
        String chip = "";
        String node = "";
        try { chip = property("nfc.nfc_chip_type"); } catch (Exception | LinkageError ignored) { }
        try { node = property("nfc.node"); } catch (Exception | LinkageError ignored) { }
        return isNxpController(chip, node)
                ? "NXP controller detected (" + chip + "; " + node + "); ST HCE is not applicable"
                : null;
    }

    private static String property(String key) throws Exception {
        Class<?> properties = Class.forName("android.os.SystemProperties");
        return (String) invoke(properties.getMethod("get", String.class), null, key);
    }

    private static void appendNfcPackage(Context context, StringBuilder text) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo("com.android.nfc", 0);
            text.append("NFC package: ").append(info.packageName).append(' ')
                    .append(info.versionName).append(" (").append(info.getLongVersionCode()).append(")\n");
            ApplicationInfo app = info.applicationInfo;
            if (app != null) {
                text.append("NFC APK: ").append(app.sourceDir).append('\n');
                if (app.splitSourceDirs != null) {
                    for (String split : app.splitSourceDirs) text.append("NFC split APK: ").append(split).append('\n');
                }
            }
            ApplicationInfo self = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_SHARED_LIBRARY_FILES);
            if (self.sharedLibraryFiles != null) {
                for (String library : self.sharedLibraryFiles) {
                    if (library.toLowerCase(Locale.ROOT).contains("nfc")) {
                        text.append("NFC framework library: ").append(library).append('\n');
                    }
                }
            }
        } catch (Exception | LinkageError error) {
            text.append("NFC package details: unavailable (").append(describe(error)).append(")\n");
        }
    }

    private static String nxpDiagnostics(Context context) {
        FutureTask<String> task;
        synchronized (HonorStNfcController.class) {
            // A Binder call may outlive our wait. Keep one task per process, including after a
            // timeout, so repeated report preparation cannot stack up blocked vendor calls.
            if (nxpProbe == null) {
                Context app = context.getApplicationContext();
                nxpProbe = new FutureTask<>(() -> inspectNxp(app));
                Thread worker = new Thread(nxpProbe, "HonorNxpReadOnlyProbe");
                worker.setDaemon(true);
                worker.start();
            }
            task = nxpProbe;
        }
        try {
            return task.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return "HONOR NXP DETAILS\nRead-only probe interrupted";
        } catch (TimeoutException error) {
            return "HONOR NXP DETAILS\nRead-only vendor probe still pending; no duplicate call was started";
        } catch (Exception | LinkageError error) {
            return "HONOR NXP DETAILS\nRead-only vendor probe failed: " + describe(error);
        }
    }

    private static String inspectNxp(Context context) {
        StringBuilder text = new StringBuilder("HONOR NXP DETAILS\n")
                .append("UID emulation: not established; no configuration setter is called\n");
        try {
            Class<?> type = Class.forName("com.nxp.nfc.NxpNfcAdapter", false, context.getClassLoader());
            Method[] methods = type.getMethods();
            Arrays.sort(methods, Comparator.comparing(Method::toString));
            for (Method method : methods) {
                String name = method.getName();
                if (name.equals("getNxpNfcAdapter") || name.equals("getNfcChip")
                        || name.equals("getFwVersion") || name.equals("getFirmwareVersion")
                        || name.equals("setConfig") || name.equals("getNciConfig")
                        || name.equals("setNciConfig")) {
                    text.append("NXP method: ").append(method.toString()).append('\n');
                }
            }
            // These three getters and the factory were inspected in HONOR's published library.
            // setConfig writes a transit file; its presence is not an emulation capability.
            Object adapter = invoke(type.getMethod("getNxpNfcAdapter", NfcAdapter.class), null,
                    NfcAdapter.getDefaultAdapter(context));
            if (adapter == null) throw new IllegalStateException("NXP adapter returned null");
            text.append("NXP adapter: resolved\n");
            for (String name : new String[] {"getNfcChip", "getFirmwareVersion", "getFwVersion"}) {
                try {
                    Object value = invoke(type.getMethod(name), adapter);
                    text.append(name).append(": ").append(value instanceof byte[]
                            ? RawNciCodec.hex((byte[]) value) : String.valueOf(value)).append('\n');
                } catch (Exception | LinkageError error) {
                    text.append(name).append(": unavailable (").append(describe(error)).append(")\n");
                }
            }
        } catch (Exception | LinkageError error) {
            text.append("NXP resolution failure: ").append(describe(error)).append('\n');
        }
        return text.toString();
    }

    private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("HONOR ST API failed", cause);
        }
    }

    private static String describe(Throwable error) {
        return OemNfcController.unwrap(error).getClass().getSimpleName() + ": "
                + OemNfcController.unwrap(error).getMessage();
    }

    private static final class Store implements MotorolaStNfcController.Store {
        private final SharedPreferences preferences;
        Store(Context context) { preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
        @Override public MotorolaStNfcController.Snapshot load() {
            if (!preferences.getBoolean("pending", false)) return null;
            if (preferences.getInt("version", 0) != 1) {
                throw new IllegalStateException("Unsupported HONOR ST snapshot version");
            }
            return new MotorolaStNfcController.Snapshot(preferences.getString("firmware", ""),
                    RawNciCodec.decodeConfiguration(preferences.getString("config", null)));
        }
        @Override public void save(MotorolaStNfcController.Snapshot snapshot) {
            if (!preferences.edit().putInt("version", 1).putBoolean("pending", true)
                    .putString("firmware", snapshot.firmware)
                    .putString("config", RawNciCodec.encodeConfiguration(snapshot.configuration)).commit()) {
                throw new IllegalStateException("Could not persist the HONOR ST snapshot");
            }
        }
        @Override public void clear() {
            if (!preferences.edit().clear().commit()) {
                throw new IllegalStateException("Could not clear the HONOR ST snapshot");
            }
        }
    }
}
