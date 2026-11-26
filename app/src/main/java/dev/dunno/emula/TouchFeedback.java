package dev.dunno.emula;

import android.app.NotificationManager;
import android.content.Context;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;

final class TouchFeedback {
    private TouchFeedback() {}

    static void play(Context context, VibrationEffect effect) {
        play(context, effect, VibrationAttributes.USAGE_TOUCH);
    }

    static void notifyTimerStopped(Context context) {
        play(context, VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK),
                VibrationAttributes.USAGE_ALARM);
    }

    private static void play(Context context, VibrationEffect effect, int usage) {
        try {
            if (Settings.System.getInt(context.getContentResolver(),
                    Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return;
            if (usage == VibrationAttributes.USAGE_ALARM) {
                NotificationManager notifications = context.getSystemService(NotificationManager.class);
                if (notifications == null || notifications.getCurrentInterruptionFilter()
                        != NotificationManager.INTERRUPTION_FILTER_ALL) return;
            }
            VibratorManager manager = context.getSystemService(VibratorManager.class);
            Vibrator vibrator = manager == null ? null : manager.getDefaultVibrator();
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(effect, new VibrationAttributes.Builder().setUsage(usage).build());
            }
        } catch (RuntimeException failure) {
            AppLog.warning("Haptic feedback unavailable", failure);
        }
    }
}
