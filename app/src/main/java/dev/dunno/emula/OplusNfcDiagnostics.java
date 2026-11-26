package dev.dunno.emula;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Read-only identities and firmware gates found in the reviewed OPPO services. */
@SuppressLint("PrivateApi")
final class OplusNfcDiagnostics {
    private static final Once probe = new Once();
    private OplusNfcDiagnostics() {}

    static String collect(Context context) {
        if (!targets(Build.MANUFACTURER)) return "";
        Context app = context.getApplicationContext();
        return "OPLUS READ-ONLY SERVICE DETAILS\n"
                + probe.read(() -> inspect(app), 2000);
    }

    static boolean targets(String manufacturer) {
        return "oppo".equalsIgnoreCase(manufacturer) || "oneplus".equalsIgnoreCase(manufacturer);
    }

    private static String inspect(Context context) {
        StringBuilder text = new StringBuilder();
        try {
            Class<?> type = Class.forName("com.oplus.content.OplusFeatureConfigManager", false,
                    context.getClassLoader());
            Object manager = invoke(type.getMethod("getInstance"), null);
            Object region = invoke(type.getMethod("hasFeature", String.class), manager,
                    "oplus.software.connectivity.region_CN");
            text.append("Region CN feature: ").append(region).append('\n');
        } catch (Exception | LinkageError error) {
            text.append("Region CN feature: unavailable (").append(describe(error)).append(")\n");
        }
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            String value = (String) invoke(type.getMethod("get", String.class), null,
                    "ro.oplus.nfc.hce_accesscard");
            text.append("ro.oplus.nfc.hce_accesscard: ")
                    .append(value == null || value.isEmpty() ? "not reported" : value).append('\n');
        } catch (Exception | LinkageError error) {
            text.append("ro.oplus.nfc.hce_accesscard: unavailable (").append(describe(error)).append(")\n");
        }
        try {
            Class<?> type = Class.forName("com.vendor.nfc.VendorNfcAdapter", false, context.getClassLoader());
            Method getter = type.getMethod("getNfcc");
            Object adapter = invoke(type.getMethod("getVendorNfcAdapter"), null);
            if (adapter == null) throw new IllegalStateException("Vendor NFC adapter is null");
            Object chip = invoke(getter, adapter);
            text.append("Vendor getNfcc: ").append(chip == null || chip.toString().isEmpty()
                    ? "empty response" : chip);
        } catch (Exception | LinkageError error) {
            text.append("Vendor getNfcc: unavailable (").append(describe(error)).append(')');
        }
        return text.toString();
    }

    private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw error;
        }
    }

    private static String describe(Throwable error) {
        return error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
    }

    /** A timed-out Binder call may still run: retain it instead of starting duplicate calls. */
    static final class Once {
        private FutureTask<String> task;
        String read(Callable<String> action, long timeoutMillis) {
            FutureTask<String> current;
            synchronized (this) {
                if (task == null) {
                    task = new FutureTask<>(action);
                    Thread worker = new Thread(task, "OplusNfcReadOnlyProbe");
                    worker.setDaemon(true);
                    worker.start();
                }
                current = task;
            }
            try { return current.get(timeoutMillis, TimeUnit.MILLISECONDS); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return "Read-only vendor probe interrupted";
            } catch (TimeoutException error) {
                return "Read-only vendor probe still pending; no duplicate call was started";
            } catch (ExecutionException error) {
                return "Read-only vendor probe failed: " + describe(error.getCause());
            }
        }
    }
}
