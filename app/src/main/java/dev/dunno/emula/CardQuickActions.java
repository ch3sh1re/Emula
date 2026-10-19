package dev.dunno.emula;

import android.app.Activity;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.service.quicksettings.TileService;

import java.util.List;
import java.util.function.Consumer;

/** Consumes each launcher request once, after startup NFC cleanup and other UI work. */
final class CardQuickActions {
    interface Host {
        boolean canRun();
        void toggle(SavedCard card);
        void message(int resource);
        Dialog chooseCard(List<SavedCard> cards, String selectedId,
                String query, Consumer<String> queryChanged,
                Consumer<SavedCard> selected, Runnable cancelled);
    }

    private static final String PENDING = "quick.pending";
    private static final String CARD = "quick.card";
    private static final String CHOOSE = "quick.choose";
    private static final String SEARCH = "quick.search";
    private final Activity activity;
    private final Host host;
    private boolean pending;
    private boolean choose;
    private String cardId;
    private boolean resumed;
    private Dialog chooser;
    private String search = "";

    CardQuickActions(Activity activity, Host host, Bundle state) {
        this.activity = activity;
        this.host = host;
        if (state != null) {
            pending = state.getBoolean(PENDING);
            choose = state.getBoolean(CHOOSE);
            cardId = state.getString(CARD);
            search = state.getString(SEARCH, "");
        }
    }

    boolean handle(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        boolean explicit = QuickAccess.action(activity, "TOGGLE_CARD").equals(action);
        boolean preferences = TileService.ACTION_QS_TILE_PREFERENCES.equals(action)
                || QuickAccess.action(activity, "CHOOSE_TILE_CARD").equals(action);
        if (!explicit && !preferences && !QuickAccess.action(activity, "TOGGLE_LAST_CARD").equals(action)) {
            return false;
        }
        if (!TileService.ACTION_QS_TILE_PREFERENCES.equals(action) && !QuickAccess.trusted(activity, intent)) {
            host.message(R.string.quick_invalid_request);
            return true;
        }
        if (chooser != null) { chooser.dismiss(); chooser = null; }
        pending = false;
        cardId = null;
        search = "";
        if (explicit) {
            Uri uri = intent.getData();
            if (uri == null || !"emula".equals(uri.getScheme())
                    || !activity.getPackageName().equals(uri.getAuthority())
                    || uri.getPathSegments().size() != 2 || !"card".equals(uri.getPathSegments().get(0))) {
                host.message(R.string.quick_card_removed);
                return true;
            }
            cardId = uri.getLastPathSegment();
        }
        choose = preferences;
        pending = true;
        onReady();
        return true;
    }

    void saveState(Bundle state) {
        state.putBoolean(PENDING, pending);
        state.putBoolean(CHOOSE, choose);
        if (cardId != null) state.putString(CARD, cardId);
        state.putString(SEARCH, search);
    }

    void resume() { resumed = true; onReady(); }
    void pause() { resumed = false; }
    void dispose() { if (chooser != null) chooser.dismiss(); }

    void clear() {
        pending = false;
        cardId = null;
        search = "";
        if (chooser != null) { chooser.dismiss(); chooser = null; }
    }

    boolean matches(Intent intent) {
        String action = intent.getAction();
        return QuickAccess.action(activity, "TOGGLE_CARD").equals(action)
                || QuickAccess.action(activity, "TOGGLE_LAST_CARD").equals(action)
                || QuickAccess.action(activity, "CHOOSE_TILE_CARD").equals(action)
                || TileService.ACTION_QS_TILE_PREFERENCES.equals(action);
    }

    void onReady() {
        if (!pending || !resumed || chooser != null || !host.canRun()
                || activity.isFinishing() || activity.isDestroyed()) return;
        KeyguardManager keyguard = activity.getSystemService(KeyguardManager.class);
        if (keyguard != null && keyguard.isKeyguardLocked()) return;
        List<SavedCard> cards = CardStore.getCards(activity);
        SavedCard card = cardId != null ? QuickCardPolicy.find(cards, cardId)
                : QuickCardPolicy.tileCard(cards, CardSession.getActiveCardId(), QuickAccess.lastCard(activity));
        if (cardId != null && card == null) {
            pending = false;
            host.message(R.string.quick_card_removed);
        } else if (cards.isEmpty()) {
            pending = false;
            host.message(R.string.quick_add_card_first);
        } else if (choose || card == null) {
            chooser = host.chooseCard(cards, card == null ? null : card.id,
                    search, value -> search = value,
                    chosen -> {
                        chooser = null;
                        SavedCard selected = QuickCardPolicy.find(CardStore.getCards(activity), chosen.id);
                        if (selected == null) {
                            pending = false;
                            host.message(R.string.quick_card_removed);
                            return;
                        }
                        QuickAccess.remember(activity, selected);
                        if (choose) {
                            pending = false;
                            host.message(R.string.quick_tile_card_saved);
                        } else {
                            cardId = selected.id;
                            onReady();
                        }
                    }, () -> pending = false);
            chooser.setOnDismissListener(dialog -> chooser = null);
        } else {
            pending = false;
            QuickAccess.used(activity, card);
            host.toggle(card);
        }
    }

    static String[] labels(List<SavedCard> cards) {
        String[] labels = new String[cards.size()];
        for (int i = 0; i < cards.size(); i++) {
            SavedCard card = cards.get(i);
            labels[i] = card.name.replace('\r', ' ').replace('\n', ' ') + "\n" + Hex.bytes(card.uid);
        }
        return labels;
    }
}
