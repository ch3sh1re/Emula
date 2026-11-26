package dev.dunno.emula;

import android.content.Context;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;

final class TouchFeedback {
    private TouchFeedback() {}

    static void play(Context context, VibrationEffect effect) {
        try {
            if (Settings.System.getInt(context.getContentResolver(),
                    Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return;
            VibratorManager manager = context.getSystemService(VibratorManager.class);
            Vibrator vibrator = manager == null ? null : manager.getDefaultVibrator();
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(effect, new VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_TOUCH).build());
            }
        } catch (RuntimeException failure) {
            AppLog.warning("Touch feedback unavailable", failure);
        }
    }
}
