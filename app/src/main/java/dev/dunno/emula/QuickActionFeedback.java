package dev.dunno.emula;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;
import android.os.VibrationEffect;
import android.widget.Toast;

/** Result feedback shared across short-lived foreground service instances. */
final class QuickActionFeedback {
    private static Toast toast;

    static void cancelToast() {
        if (toast != null) { toast.cancel(); toast = null; }
    }

    static void show(Context context, String message, boolean failure) {
        cancelToast();
        toast = Toast.makeText(context.getApplicationContext(), message,
                failure ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        toast.show();
        vibrate(context, failure);
    }

    private static void vibrate(Context context, boolean failure) {
        try {
            AudioManager audio = context.getSystemService(AudioManager.class);
            NotificationManager notifications = context.getSystemService(NotificationManager.class);
            if (audio == null || audio.getRingerMode() == AudioManager.RINGER_MODE_SILENT
                    || notifications == null || notifications.getCurrentInterruptionFilter()
                    != NotificationManager.INTERRUPTION_FILTER_ALL) return;
            VibrationEffect effect = failure
                    ? VibrationEffect.createWaveform(new long[] {0, 30, 70, 30}, -1)
                    : VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
            TouchFeedback.play(context, effect);
        } catch (RuntimeException error) {
            // Feedback must never turn a completed NFC operation into a failure.
            AppLog.warning("Quick action haptic unavailable", error);
        }
    }

    private QuickActionFeedback() { }
}
