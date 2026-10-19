package dev.dunno.emula;

import android.app.Application;
import android.content.SharedPreferences;

/** Keeps phone launcher surfaces in step with catalog edits and process-local state. */
public final class EmulaApplication extends Application {
    private final SharedPreferences.OnSharedPreferenceChangeListener changed =
            (preferences, key) -> QuickAccess.refresh(this);

    @Override public void onCreate() {
        super.onCreate();
        QuickAccess.token(this);
        getSharedPreferences("office_cards", MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(changed);
        getSharedPreferences(QuickAccess.PREFS, MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(changed);
        // Activation is deliberately not persisted; clear launcher indicators on a new process.
        QuickAccess.refresh(this);
    }
}
