package dev.dunno.emula;

import android.content.Context;
import android.content.SharedPreferences;

final class WatchSessionStore implements WatchNfcSession.Store {
    static final String REVISION = "revision";
    final SharedPreferences preferences;
    private final Context context;
    private static volatile boolean activeInThisProcess;

    WatchSessionStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences("watch_restore", Context.MODE_PRIVATE);
    }

    long revision() {
        return preferences.getLong(REVISION, 0);
    }

    // All NFC actions and tile taps are serialized on WatchNfcRuntime.WORK.
    boolean consumeTap(long expectedRevision) {
        return expectedRevision == revision()
                && preferences.edit().putLong(REVISION, expectedRevision + 1).commit();
    }

    boolean needsStartupRestore() {
        return load() != null && !activeInThisProcess;
    }

    @Override
    public WatchNfcSession.Pending load() {
        if (!preferences.getBoolean("pending", false)) return null;
        return new WatchNfcSession.Pending(
                WatchNfcSession.Path.valueOf(preferences.getString("path", "")),
                preferences.getString("firmware", ""),
                preferences.getString("snapshot", ""),
                preferences.getString("cardId", ""),
                preferences.getBoolean("active", false),
                preferences.getString("timerId", ""), preferences.getLong("stopAtElapsed", 0));
    }

    @Override
    public void save(WatchNfcSession.Pending pending) {
        // Arm the fallback before the first hardware write, including if this process dies.
        if (!pending.active && !pending.timerId.isEmpty()) {
            WatchStopAlarm.schedule(context, pending.timerId, pending.stopAtElapsed);
        }
        if (!preferences.edit().putBoolean("pending", true)
                .putString("path", pending.path.name()).putString("firmware", pending.firmware)
                .putString("snapshot", pending.snapshot).putString("cardId", pending.cardId)
                .putBoolean("active", pending.active).putString("timerId", pending.timerId)
                .putLong("stopAtElapsed", pending.stopAtElapsed)
                .putLong(REVISION, revision() + 1).commit()) {
            if (!pending.active && !pending.timerId.isEmpty()) WatchStopAlarm.cancel(context, pending.timerId);
            throw new IllegalStateException("Could not save the NFC snapshot; operation stopped");
        }
        activeInThisProcess = pending.active;
    }

    @Override
    public void clear() {
        String timer = preferences.getString("timerId", "");
        if (!preferences.edit().clear().putLong(REVISION, revision() + 1).commit()) {
            throw new IllegalStateException("Could not clear NFC recovery state");
        }
        activeInThisProcess = false;
        if (!timer.isEmpty()) WatchStopAlarm.cancel(context, timer);
    }
}
