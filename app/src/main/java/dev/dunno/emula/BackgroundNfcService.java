package dev.dunno.emula;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/** User-started NFC interaction. Kept foreground only while work/activation/recovery remains. */
public final class BackgroundNfcService extends Service {
    private static final String CHANNEL = "nfc_control";
    private static final int ONGOING = 201;
    private static final int FAILURE = 202;
    private PhoneNfcRuntime runtime;
    private boolean ready;
    private boolean promoted;

    static Intent wakeIntent(Context context) {
        return QuickAccess.authorize(context, new Intent(context, BackgroundNfcService.class)
                .setAction(QuickAccess.action(context, "NFC_WAKE")));
    }

    static Intent command(Context context, String cardId, boolean stop) {
        Intent intent = cardId == null ? QuickAccess.tileIntent(context, false)
                : QuickAccess.cardIntent(context, cardId);
        intent.setClass(context, BackgroundNfcService.class);
        if (stop) intent.setAction(QuickAccess.action(context, "STOP_CARD")).setData(null);
        return intent;
    }

    @Override public void onCreate() {
        super.onCreate();
        runtime = PhoneNfcRuntime.get(this);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.background_channel), NotificationManager.IMPORTANCE_LOW));
        promote();
    }

    private boolean promote() {
        try {
            startForeground(ONGOING, notification(getString(R.string.background_working), false),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            promoted = true;
            return true;
        } catch (RuntimeException failure) {
            runtime.serviceFailed(failure);
            QuickActionFeedback.show(this, PhoneNfcRuntime.message(this, failure), true);
            stopSelf();
            return false;
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // A new user command may arrive between idle stopSelf() and onDestroy().
        if (!promoted && !promote()) return START_NOT_STICKY;
        if (intent == null || !QuickAccess.trusted(this, intent)) {
            if (!runtime.activeOrRecovery() && !runtime.isBusy()) stopSelf();
            return START_NOT_STICKY;
        }
        ready = false;
        QuickActionFeedback.cancelToast();
        runtime.serviceReady(this);
        String action = intent.getAction();
        if (QuickAccess.action(this, "STOP_CARD").equals(action)) {
            runtime.submit(true, state -> { state.change(null, false); return null; }, this::finished);
        } else if (QuickAccess.action(this, "TOGGLE_CARD").equals(action)) {
            String id = QuickAccess.cardId(this, intent);
            if (id == null) finished(null, new PhoneNfcSession.Failure(PhoneNfcSession.Reason.MISSING));
            else runtime.toggle(id, this::finished);
        } else if (QuickAccess.action(this, "TOGGLE_LAST_CARD").equals(action)) {
            runtime.toggle(null, this::finished);
        }
        ready = true;
        runtime.refresh();
        return START_NOT_STICKY;
    }

    private void finished(Void ignored, Throwable failure) {
        if (failure != null) {
            String message = PhoneNfcRuntime.message(this, failure);
            QuickActionFeedback.show(this, message, true);
            notifyIfAllowed(FAILURE,
                    Notification.Builder.recoverBuilder(this, notification(message, false))
                            .setOngoing(false).build());
        } else {
            getSystemService(NotificationManager.class).cancel(FAILURE);
            // A burst is still serialized; report the final state instead of queuing
            // obsolete success messages for each intermediate operation.
            if (runtime.isBusy()) return;
            SavedCard active = QuickCardPolicy.find(CardStore.getCards(this), CardSession.getActiveCardId());
            QuickActionFeedback.show(this, active == null ? getString(R.string.standard_nfc_restored)
                    : getString(R.string.card_active, active.name), false);
        }
    }

    void renderState() {
        if (!ready) return;
        if (!runtime.isBusy() && !runtime.activeOrRecovery()) {
            ready = false;
            promoted = false;
            runtime.serviceGone(this);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        SavedCard card = QuickCardPolicy.find(CardStore.getCards(this), CardSession.getActiveCardId());
        String message = runtime.isBusy() ? getString(R.string.background_working)
                : card != null ? getString(R.string.card_active, card.name)
                : getString(R.string.background_recovery);
        notifyIfAllowed(ONGOING, notification(message, true));
    }

    private void notifyIfAllowed(int id, Notification notification) {
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            getSystemService(NotificationManager.class).notify(id, notification);
        }
    }

    private Notification notification(String message, boolean stopAction) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_card_24).setContentTitle(getString(R.string.app_name))
                .setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(QuickAccess.pending(this, open, 201))
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE);
        if (stopAction) {
            PendingIntent stop = PendingIntent.getForegroundService(this, 202, command(this, null, true),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, getString(R.string.background_stop), stop).build());
        }
        return builder.build();
    }

    @Override public void onDestroy() { runtime.serviceGone(this); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
