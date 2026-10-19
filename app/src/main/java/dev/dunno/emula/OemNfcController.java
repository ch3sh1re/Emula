package dev.dunno.emula;

import android.content.Context;
import android.nfc.NfcAdapter;
import android.os.Build;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

final class OemNfcController {
    private static final Class<?>[] EMULATE_NFC_A_TAG_PARAMETERS = new Class<?>[] {
            boolean.class, byte.class, byte.class, byte.class,
            byte[].class, byte.class, byte[].class
    };
    private static final EmulationProfile PIXEL_PROFILE = new EmulationProfile(
            "pixel-minimal", (byte) 0x04, (byte) 0x00, (byte) 0x00, (byte) 0x00);
    private static final EmulationProfile CTS_TYPE_4A_PROFILE = new EmulationProfile(
            "cts-type-4a", (byte) 0x06, (byte) 0x0C, (byte) 0x20, (byte) 0x40);

    private static volatile OemOperationReport lastOperationReport;

    private OemNfcController() {}

    /**
     * Checks the API itself rather than SDK_INT: Android 16 QPR2 still reports API level 36,
     * and an OEM may backport the NFC module independently from the platform release.
     */
    static boolean isUidEmulationSupported(Context context) {
        return inspectCapabilities(context).isSupported();
    }

    static Capabilities inspectCapabilities(Context context) {
        OnePlusVendorNfcController.Probe vendor =
                OnePlusVendorNfcController.probe(context);
        boolean androidOemExtension;
        String androidFailure = null;
        try {
            resolveAndroidApi(context);
            androidOemExtension = true;
        } catch (Throwable failure) {
            androidOemExtension = false;
            androidFailure = describe(failure);
        }
        return new Capabilities(androidOemExtension, androidFailure, vendor,
                OnePlusVendorNfcController.hasPendingRestore(context),
                MotorolaStNfcController.probe(context),
                MotorolaStNfcController.hasPendingRestore(context));
    }

    static String lastOperationForDisplay() {
        OemOperationReport report = lastOperationReport;
        return report == null
                ? "LAST OEM NFC OPERATION\n\nNone yet"
                : report.formatForDisplay();
    }

    static String lastOperationDetailed() {
        OemOperationReport report = lastOperationReport;
        return report == null
                ? "LAST OEM NFC OPERATION\n\nNone yet"
                : report.formatDetailed();
    }

    static OemOperationReport getLastOperationReport() {
        return lastOperationReport;
    }

    static void restoreDefaults(Context context) throws Exception {
        restoreDefaults(context, OemOperationReport.Operation.DEACTIVATE);
    }

    static void restoreDefaults(Context context, OemOperationReport.Operation operation)
            throws Exception {
        setCardEnabled(context, new byte[UidCodec.DEFAULT_UID_LENGTH], false, operation);
    }

    static void setCardEnabled(Context context, byte[] uid, boolean enable) throws Exception {
        setCardEnabled(context, uid, enable,
                enable ? OemOperationReport.Operation.ACTIVATE
                        : OemOperationReport.Operation.DEACTIVATE);
    }

    private static void setCardEnabled(Context context, byte[] uid, boolean enable,
            OemOperationReport.Operation operation) throws Exception {
        OemOperationReport report = new OemOperationReport(operation);
        lastOperationReport = report;
        if (uid == null || !UidCodec.isSupportedLength(uid.length)) {
            report.failure = "UID must contain 4, 7, or 10 bytes";
            report.stage = "FAILED";
            throw new IllegalArgumentException(report.failure);
        }

        try {
            report.stage = "CHECK_NFC";
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
            if (adapter == null) throw new IllegalStateException("NFC adapter is unavailable");
            if (!adapter.isEnabled()) throw new IllegalStateException("NFC is disabled");

            if (MotorolaStNfcController.isTargetDevice() || MotorolaStNfcController.hasPendingRestore(context)) {
                boolean pending = MotorolaStNfcController.hasPendingRestore(context);
                if (enable || pending) {
                    OemOperationReport st = report;
                    if (MotorolaStNfcController.tryFirst(enable,
                            () -> MotorolaStNfcController.setCardEnabled(context, uid, enable, st),
                            () -> MotorolaStNfcController.hasPendingRestore(context), st)) return;
                    report = new OemOperationReport(operation);
                    report.stAttempt = st;
                    lastOperationReport = report;
                    if (!inspectCapabilities(context).androidOemExtension) {
                        throw new MotorolaFallbackException(st.failure);
                    }
                } else if (!inspectCapabilities(context).androidOemExtension) {
                    // Startup/deactivation must not call ST setters without our own snapshot.
                    report.path = OemOperationReport.Path.MOTOROLA_ST_VENDOR_HCE;
                    report.result = "NO_PENDING_ST_RESTORE";
                    report.stage = "COMPLETE";
                    return;
                }
            }

            if (OnePlusVendorNfcController.isTargetDevice()) {
                try {
                    report.stage = "RESOLVE_ONEPLUS_VENDOR_HCE";
                    OnePlusVendorNfcController.Api vendorApi =
                            OnePlusVendorNfcController.resolveApi(context, report);
                    AppLog.info("NFC-A path=OnePlus vendor HCE, enabled=" + enable
                            + ", device=" + Build.MANUFACTURER + " " + Build.MODEL);
                    OnePlusVendorNfcController.setCardEnabled(
                            context, vendorApi, Arrays.copyOf(uid, uid.length), enable, report);
                    return;
                } catch (OnePlusVendorNfcController.ResolutionException unavailable) {
                    if (OnePlusVendorNfcController.hasPendingRestore(context)) {
                        report.notes.add("OnePlus vendor HCE unavailable; pending state kept: "
                                + describe(unavailable));
                        throw new IllegalStateException(
                                "A pending OnePlus HCE state requires the vendor API",
                                unavailable);
                    }
                    report.notes.add("OnePlus vendor HCE unavailable; trying Android OEM API: "
                            + describe(unavailable));
                }
            }

            report.stage = "RESOLVE_ANDROID_OEM_EXTENSION";
            report.path = OemOperationReport.Path.ANDROID_OEM_EXTENSION;
            EmulationApi api = resolveAndroidApi(context);
            EmulationProfile profile = resolveProfile();
            report.profile = profile.name;
            report.atqa = new byte[] {profile.bitFrameSdd, profile.platformConfig};
            report.sak = new byte[] {profile.selInfo};
            report.uid = Arrays.copyOf(uid, uid.length);
            AppLog.info("NFC-A profile=" + profile.name
                    + ", enabled=" + enable
                    + ", device=" + Build.MANUFACTURER + " " + Build.MODEL);

            report.stage = "ANDROID_EMULATE_NFC_A_TAG";
            Object result = invoke(api.extension, api.emulateMethod,
                    enable,
                    profile.bitFrameSdd,
                    profile.platformConfig,
                    profile.selInfo,
                    Arrays.copyOf(uid, uid.length),
                    profile.rats,
                    null);

            if (!(result instanceof Integer)) {
                throw new IllegalStateException("Unexpected NFC response: " + result);
            }
            int status = (Integer) result;
            if (status == 1) {
                throw new IllegalStateException("NFC is disabled");
            }
            if (status == 2) {
                throw new IllegalStateException("NFC controller rejected the configuration");
            }
            if (status != 0) {
                throw new IllegalStateException("Unknown NFC OEM status " + status);
            }
            report.result = "SUCCESS";
            report.stage = "COMPLETE";
        } catch (Throwable failure) {
            report.failure = describe(failure);
            report.stage = "FAILED";
            throw asException(failure);
        }
    }

    /** ST failed safely and the Android OEM API is absent; phone raw policy may decide next. */
    static final class MotorolaFallbackException extends Exception {
        MotorolaFallbackException(String stFailure) {
            super("Motorola ST candidate failed; no Android OEM extension: " + stFailure);
        }
    }

    private static EmulationProfile resolveProfile() {
        // CTS is Android's reference configuration and the fallback for untested OEMs.
        // Google's NFC stack is the verified exception: with CTS values the tested Pixel
        // sees an RF field but never reaches card activation.
        if ("google".equalsIgnoreCase(Build.MANUFACTURER)) {
            return PIXEL_PROFILE;
        }
        return CTS_TYPE_4A_PROFILE;
    }

    private static EmulationApi resolveAndroidApi(Context context) throws Exception {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
        if (adapter == null) throw new IllegalStateException("NFC adapter is unavailable");

        Method extensionMethod;
        try {
            extensionMethod = findMethod(adapter.getClass(),
                    "getNfcOemExtension", new Class<?>[0]);
        } catch (NoSuchMethodException e) {
            throw unsupportedApi(e);
        }

        Object extension = invoke(adapter, extensionMethod);
        if (extension == null) throw unsupportedApi(null);

        Method emulateMethod;
        try {
            emulateMethod = findMethod(extension.getClass(),
                    "emulateNfcTechnologyATag", EMULATE_NFC_A_TAG_PARAMETERS);
        } catch (NoSuchMethodException e) {
            throw unsupportedApi(e);
        }
        return new EmulationApi(adapter, extension, emulateMethod);
    }

    private static UnsupportedOperationException unsupportedApi(Throwable cause) {
        return new UnsupportedOperationException(
                "A newer Android version is required", cause);
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>[] parameterTypes)
            throws NoSuchMethodException {
        try {
            return owner.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException publicLookupFailed) {
            return owner.getDeclaredMethod(name, parameterTypes);
        }
    }

    private static Object invoke(Object receiver, Method method, Object... arguments)
            throws Exception {
        try {
            method.setAccessible(true);
        } catch (Throwable ignored) {
            // These methods are public System APIs; hidden API policy may reject setAccessible().
        }
        return method.invoke(receiver, arguments);
    }

    static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException
                && ((InvocationTargetException) current).getCause() != null) {
            current = ((InvocationTargetException) current).getCause();
        }
        return current;
    }

    private static String describe(Throwable throwable) {
        Throwable root = unwrap(throwable);
        String message = root.getMessage();
        return root.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static Exception asException(Throwable throwable) {
        Throwable root = unwrap(throwable);
        if (root instanceof Exception) return (Exception) root;
        return new IllegalStateException(describe(root), root);
    }

    static final class Capabilities {
        final boolean androidOemExtension;
        final String androidFailure;
        final OnePlusVendorNfcController.Probe onePlusVendor;
        final boolean pendingOnePlusRestore;
        final MotorolaStNfcController.Probe motorolaSt;
        final boolean pendingMotorolaStRestore;

        Capabilities(boolean androidOemExtension, String androidFailure,
                OnePlusVendorNfcController.Probe onePlusVendor,
                boolean pendingOnePlusRestore, MotorolaStNfcController.Probe motorolaSt,
                boolean pendingMotorolaStRestore) {
            this.androidOemExtension = androidOemExtension;
            this.androidFailure = androidFailure;
            this.onePlusVendor = onePlusVendor;
            this.pendingOnePlusRestore = pendingOnePlusRestore;
            this.motorolaSt = motorolaSt;
            this.pendingMotorolaStRestore = pendingMotorolaStRestore;
        }

        boolean isSupported() {
            return motorolaSt.available || androidOemExtension || onePlusVendor.isAvailable();
        }

        String preferredPath() {
            if (motorolaSt.available) return "MOTOROLA_ST_VENDOR_HCE";
            if (onePlusVendor.isAvailable()) return "ONEPLUS_VENDOR_HCE";
            if (androidOemExtension) return "ANDROID_OEM_EXTENSION";
            return "UNAVAILABLE";
        }

        String format(boolean detailed) {
            StringBuilder text = new StringBuilder()
                    .append("OEM UID API: ")
                    .append(isSupported() ? "available" : "unavailable")
                    .append('\n')
                    .append("Android OEM extension: ")
                    .append(androidOemExtension ? "available" : "unavailable")
                    .append('\n')
                    .append("OnePlus vendor HCE: ");
            if (!onePlusVendor.targeted) {
                text.append("not targeted");
            } else {
                text.append(onePlusVendor.isAvailable() ? "available" : "unavailable");
            }
            text.append('\n')
                    .append("Motorola ST vendor HCE: ")
                    .append(!motorolaSt.targeted ? "not targeted"
                            : motorolaSt.available ? "available (experimental)" : "unavailable")
                    .append('\n')
                    .append("Pending Motorola ST restore: ")
                    .append(pendingMotorolaStRestore ? "yes" : "no")
                    .append('\n')
                    .append("Preferred OEM path: ").append(preferredPath()).append('\n')
                    .append("Pending OnePlus restore: ")
                    .append(pendingOnePlusRestore ? "yes" : "no");
            if (detailed && onePlusVendor.targeted) {
                text.append('\n')
                        .append("OnePlus library: ")
                        .append(onePlusVendor.libraryAvailable ? "available" : "unavailable")
                        .append('\n')
                        .append("OnePlus method: ")
                        .append(onePlusVendor.methodResolved ? "resolved" : "unavailable")
                        .append('\n')
                        .append("OnePlus adapter: ")
                        .append(onePlusVendor.adapterResolved ? "resolved" : "unavailable");
                if (onePlusVendor.failure != null) {
                    text.append('\n').append("OnePlus probe failure: ")
                            .append(onePlusVendor.failure);
                }
            }
            if (detailed && !androidOemExtension && androidFailure != null) {
                text.append('\n').append("Android OEM probe failure: ")
                        .append(androidFailure);
            }
            if (detailed && motorolaSt.targeted && motorolaSt.failure != null) {
                text.append('\n').append("Motorola ST probe failure: ")
                        .append(motorolaSt.failure);
            }
            return text.toString();
        }
    }

    private static final class EmulationApi {
        final NfcAdapter adapter;
        final Object extension;
        final Method emulateMethod;

        EmulationApi(NfcAdapter adapter, Object extension, Method emulateMethod) {
            this.adapter = adapter;
            this.extension = extension;
            this.emulateMethod = emulateMethod;
        }
    }

    private static final class EmulationProfile {
        final String name;
        final byte bitFrameSdd;
        final byte platformConfig;
        final byte selInfo;
        final byte rats;

        EmulationProfile(String name, byte bitFrameSdd, byte platformConfig,
                byte selInfo, byte rats) {
            this.name = name;
            this.bitFrameSdd = bitFrameSdd;
            this.platformConfig = platformConfig;
            this.selInfo = selInfo;
            this.rats = rats;
        }
    }
}
