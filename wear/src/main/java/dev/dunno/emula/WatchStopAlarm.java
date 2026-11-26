package dev.dunno.emula;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** A precise in-process alarm plus a process-independent backup for the same NFC session. */
public final class WatchStopAlarm extends BroadcastReceiver {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static AlarmManager.OnAlarmListener listener;
    private static Runnable awakeStop;

    private static PendingIntent intent(Context context, String id, int flags) {
        Intent action = new Intent(context, WatchStopAlarm.class)
                .setData(new Uri.Builder().scheme("emula-timer").authority("stop").appendPath(id).build());
        return PendingIntent.getBroadcast(context, 0, action, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context context, String id, long when) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, intent(context, id, 0));
        // AlarmManager can round up very short intervals even while the watch is awake.
        awakeStop = () -> WatchNfcRuntime.WORK.execute(() -> stopIfDue(context, id));
        listener = awakeStop::run;
        try {
            alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, "Emula auto-stop", listener, MAIN);
            MAIN.postDelayed(awakeStop, Math.max(0, when - SystemClock.elapsedRealtime()));
        } catch (RuntimeException failure) {
            cancel(context, id);
            throw failure;
        }
    }

    static void cancel(Context context, String id) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (awakeStop != null) {
            MAIN.removeCallbacks(awakeStop);
            awakeStop = null;
        }
        if (listener != null) {
            alarms.cancel(listener);
            listener = null;
        }
        PendingIntent backup = intent(context, id, PendingIntent.FLAG_NO_CREATE);
        if (backup != null) {
            alarms.cancel(backup);
            backup.cancel();
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (intent.getData() == null) return;
        String id = intent.getData().getLastPathSegment();
        PendingResult result = goAsync();
        WatchNfcRuntime.WORK.execute(() -> {
            try { stopIfDue(context.getApplicationContext(), id); }
            finally { result.finish(); }
        });
    }

    private static void stopIfDue(Context context, String id) {
        WatchSessionStore store = new WatchSessionStore(context);
        WatchNfcSession.Pending pending = store.load();
        if (pending == null || !pending.isDue(id, SystemClock.elapsedRealtime())) return;
        StringBuilder evidence = new StringBuilder();
        Exception problem = null;
        try {
            WatchNfcRuntime.requireNfc(context, android.nfc.NfcAdapter.getDefaultAdapter(context));
            new WatchNfcSession(store, new WatchHardwareBackend(context, evidence), Build.FINGERPRINT).restore();
        } catch (Exception failure) {
            problem = failure; // The recovery record remains available for a manual Stop.
        }
        WatchNfcRuntime.recordTileResult(context, "TILE AUTO-STOP", evidence, problem);
        WatchCardTileService.refresh(context, WatchNfcRuntime.tilePreferences(context).getInt("lastStartTile", -1));
    }
}
