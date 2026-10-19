package dev.dunno.emula;

import android.content.SharedPreferences;

final class WatchSessionStore implements WatchNfcSession.Store {
    private final SharedPreferences preferences;

    WatchSessionStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    @Override
    public WatchNfcSession.Pending load() {
        if (!preferences.getBoolean("pending", false)) return null;
        return new WatchNfcSession.Pending(
                WatchNfcSession.Path.valueOf(preferences.getString("path", "")),
                preferences.getString("firmware", ""),
                preferences.getString("snapshot", ""),
                preferences.getString("cardId", ""),
                preferences.getBoolean("active", false));
    }

    @Override
    public void save(WatchNfcSession.Pending pending) {
        if (!preferences.edit().putBoolean("pending", true)
                .putString("path", pending.path.name()).putString("firmware", pending.firmware)
                .putString("snapshot", pending.snapshot).putString("cardId", pending.cardId)
                .putBoolean("active", pending.active).commit()) {
            throw new IllegalStateException("Could not save the NFC snapshot; operation stopped");
        }
    }

    @Override
    public void clear() {
        if (!preferences.edit().clear().commit()) {
            throw new IllegalStateException("Could not clear NFC recovery state");
        }
    }
}
