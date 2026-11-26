package dev.dunno.emula;

import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercise durable recovery and failed commits using Android storage, without NFC calls. */
final class OppoNxpRawChecks {
    static void storage(Context context) {
        String name = "oppo_rf_test_" + UUID.randomUUID();
        SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
        try {
            Map<Integer, byte[]> values = new LinkedHashMap<>();
            values.put(0x30, new byte[] {4}); values.put(0x31, new byte[] {0});
            values.put(0x32, new byte[] {0x20}); values.put(0x33, new byte[] {8,0,0,0});
            values.put(0x58, new byte[] {0x74}); values.put(0x59, new byte[0]);
            values.put(0x85, new byte[] {1});
            OppoNxpRawController.Snapshot original = new OppoNxpRawController.Snapshot("firmware/apk/jni/chip", values);
            OppoNxpRawController.Storage store = new OppoNxpRawController.Storage(prefs);
            store.save(original);
            OppoNxpRawController.Storage reopened = new OppoNxpRawController.Storage(
                    context.getSharedPreferences(name, Context.MODE_PRIVATE));
            check(reopened.load().configuration.size() == 7);
            check(reopened.load().configuration.get(0x59).length == 0);
            expectFailure(() -> new OppoNxpRawController.Storage(failFirstCommit(prefs)).clear());
            check(reopened.load() != null);
            check(reopened.load().identity.equals(original.identity));
            store.clear();
            check(reopened.load() == null);
            expectFailure(() -> new OppoNxpRawController.Storage(failFirstCommit(prefs)).save(original));
            // Even a failed disk commit may change the memory map; the caller must abort writes.
            check(reopened.load() != null);
            check(prefs.edit().putInt("version", 99).commit());
            expectFailure(reopened::load);
        } finally { context.deleteSharedPreferences(name); }
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

    private static void expectFailure(Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("Expected a failed OPPO recovery operation");
    }
    private static void check(boolean result) { if (!result) throw new AssertionError("OPPO recovery invariant"); }
}
