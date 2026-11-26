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

import java.util.concurrent.atomic.AtomicReference;

/** A precise in-process alarm plus a process-independent backup for the same NFC session. */
public final class WatchStopAlarm extends BroadcastReceiver {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static AlarmManager.OnAlarmListener listener;
    private static Runnable awakeStop;
    // Only the serial NFC worker records and reads the active tile's presentation time.
    private static String displayedTimer = "";
    private static long displayedUntilElapsed;
    private record StopFeedback(int tileId, long revision, Runnable tick) {}
    private static final AtomicReference<StopFeedback> waitingForTile = new AtomicReference<>();

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

    static void activeTileDelivered(WatchNfcSession.Pending pending, int seconds) {
        if (!pending.active || pending.timerId.isEmpty() || pending.timerId.equals(displayedTimer)) return;
        displayedTimer = pending.timerId;
        displayedUntilElapsed = SystemClock.elapsedRealtime() + seconds * 1000L;
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (intent.getData() == null) return;
        String id = intent.getData().getLastPathSegment();
        PendingResult result = goAsync();
        WatchNfcRuntime.WORK.execute(() -> {
            long feedbackAt = 0;
            try { feedbackAt = stopIfDue(context.getApplicationContext(), id); }
            finally {
                // Keep a cold receiver alive through the already queued completion tick.
                if (feedbackAt > 0) MAIN.postAtTime(result::finish, feedbackAt);
                else result.finish();
            }
        });
    }

    /** Returns the uptime of the queued confirmation, or zero if this callback has none. */
    private static long stopIfDue(Context context, String id) {
        WatchSessionStore store = new WatchSessionStore(context);
        WatchNfcSession.Pending pending = store.load();
        if (pending == null || !pending.isDue(id, SystemClock.elapsedRealtime())) return 0;
        StringBuilder evidence = new StringBuilder();
        Exception problem = null;
        try {
            WatchNfcRuntime.requireNfc(context, android.nfc.NfcAdapter.getDefaultAdapter(context));
            new WatchNfcSession(store, new WatchHardwareBackend(context, evidence), Build.FINGERPRINT).restore();
        } catch (Exception failure) {
            problem = failure; // The recovery record remains available for a manual Stop.
        }
        WatchNfcRuntime.recordTileResult(context, "TILE AUTO-STOP", evidence, problem);
        int tileId = WatchNfcRuntime.tilePreferences(context).getInt("lastStartTile", -1);
        if (problem != null || !pending.active) {
            WatchCardTileService.refresh(context, tileId);
            return 0;
        }
        long presentationEnd = id.equals(displayedTimer) ? displayedUntilElapsed : pending.stopAtElapsed;
        long delay = WatchTileLayout.feedbackDelayMillis(pending.stopAtElapsed, presentationEnd,
                SystemClock.elapsedRealtime());
        long feedbackAt = SystemClock.uptimeMillis() + delay;
        long restoredRevision = store.revision();
        StopFeedback feedback = new StopFeedback(tileId, restoredRevision, () -> {
            if (store.revision() == restoredRevision) TouchFeedback.notifyTimerStopped(context);
        });
        waitingForTile.set(feedback);
        // Coalesce a real idle layout with confirmation when the renderer is already waiting.
        MAIN.postAtTime(() -> playOnce(feedback), feedbackAt);
        WatchCardTileService.refresh(context, tileId);
        return feedbackAt;
    }

    static void idleTileDelivered(int tileId, long revision) {
        StopFeedback feedback = waitingForTile.get();
        if (feedback != null && feedback.tileId == tileId && feedback.revision == revision) {
            playOnce(feedback);
        }
    }

    private static void playOnce(StopFeedback feedback) {
        if (waitingForTile.compareAndSet(feedback, null)) feedback.tick.run();
    }
}
