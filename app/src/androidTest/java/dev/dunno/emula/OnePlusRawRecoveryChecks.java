package dev.dunno.emula;

import android.content.Context;
import android.content.SharedPreferences;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual private storage and failed-commit semantics, without NFC hardware calls. */
final class OnePlusRawRecoveryChecks {
    static void storage(Context context) {
        String name = "oneplus_recovery_test_" + UUID.randomUUID();
        SharedPreferences raw = context.getSharedPreferences(name, Context.MODE_PRIVATE);
        SharedPreferences vendor = context.getSharedPreferences(name + "_vendor", Context.MODE_PRIVATE);
        try {
            String firmware = OnePlusRawRecovery.FIRMWARE;
            check(vendor.edit().putBoolean("pending", true).putInt("version", 1)
                    .putString("fingerprint", firmware).putString("mode", "0")
                    .putString("atqa", "0400").putString("sak", "20").putString("uid", "08000000").commit());
            OnePlusVendorNfcController.Snapshot saved = OnePlusVendorNfcController.loadSnapshot(vendor);
            Map<Integer, byte[]> config = baseline();
            OnePlusRawRecovery.Storage store = new OnePlusRawRecovery.Storage(raw, vendor);

            // A failed archive commit must never release the original vendor marker.
            expectFailure(() -> new OnePlusRawRecovery.Storage(failFirstCommit(raw), vendor)
                    .archiveAndRelease(saved, config));
            check(vendor.getBoolean("pending", false));

            // A marker commit can fail after changing the in-memory map. Re-arm it.
            expectFailure(() -> new OnePlusRawRecovery.Storage(raw, failFirstCommit(vendor))
                    .archiveAndRelease(saved, config));
            check(vendor.getBoolean("pending", false));
            check(store.archived());
            store.archiveAndRelease(saved, config);
            check(!vendor.getBoolean("pending", true));
            check(vendor.getString("uid", "").equals("08000000"));
            check(raw.getString("archive_uid", "").equals("08000000"));

            store.save(config);
            OnePlusRawRecovery.Storage reopened = new OnePlusRawRecovery.Storage(
                    context.getSharedPreferences(name, Context.MODE_PRIVATE), vendor);
            check(reopened.pending() && reopened.load().size() == 7);
            check(reopened.load().get(0x59).length == 0);
            expectFailure(() -> new OnePlusRawRecovery.Storage(failFirstCommit(raw), vendor).clear());
            check(reopened.pending() && reopened.load().size() == 7);
            reopened.clear();
            check(!reopened.pending() && reopened.archived());
            check(raw.getString("archive_mode", "").equals("0"));

            // A later Android disable baseline is distinct from the immutable vendor archive.
            Map<Integer, byte[]> androidIdle = baseline();
            androidIdle.put(0x58, new byte[] {0});
            reopened.save(androidIdle);
            OnePlusRawRecovery.Storage afterRestart = new OnePlusRawRecovery.Storage(
                    context.getSharedPreferences(name, Context.MODE_PRIVATE), vendor);
            check(afterRestart.load().get(0x58)[0] == 0);
            check(RawNciCodec.decodeConfiguration(raw.getString("archive_configuration", "")).get(0x58)[0] == 0x41);
            afterRestart.clear();
            check(afterRestart.archived() && !afterRestart.pending());

            // Another vendor session must not overwrite the first accepted archive.
            check(vendor.edit().putBoolean("pending", true).putString("uid", "01020304").commit());
            OnePlusVendorNfcController.Snapshot changed = OnePlusVendorNfcController.loadSnapshot(vendor);
            expectFailure(() -> reopened.archiveAndRelease(changed, config));
            check(vendor.getBoolean("pending", false));
            check(raw.getString("archive_uid", "").equals("08000000"));
        } finally {
            context.deleteSharedPreferences(name);
            context.deleteSharedPreferences(name + "_vendor");
        }
    }

    private static SharedPreferences failFirstCommit(SharedPreferences delegate) {
        AtomicBoolean failed = new AtomicBoolean();
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class}, (proxy, method, arguments) -> {
                    if (!"edit".equals(method.getName())) return method.invoke(delegate, arguments);
                    SharedPreferences.Editor editor = delegate.edit();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[] {SharedPreferences.Editor.class}, (editProxy, editMethod, editArgs) -> {
                                Object result = editMethod.invoke(editor, editArgs);
                                if ("commit".equals(editMethod.getName()) && !failed.getAndSet(true)) return false;
                                return result instanceof SharedPreferences.Editor ? editProxy : result;
                            });
                });
    }

    private static Map<Integer, byte[]> baseline() {
        Map<Integer, byte[]> result = new LinkedHashMap<>();
        result.put(0x30, new byte[] {4}); result.put(0x31, new byte[] {0});
        result.put(0x32, new byte[] {0x20}); result.put(0x33, new byte[] {8, 0, 0, 0});
        result.put(0x58, new byte[] {0x41}); result.put(0x59, new byte[0]); result.put(0x85, new byte[] {1});
        return result;
    }
    private static void expectFailure(Runnable work) {
        try { work.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("Expected a failed storage operation");
    }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("OnePlus recovery storage invariant"); }
}
