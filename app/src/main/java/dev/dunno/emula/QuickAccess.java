package dev.dunno.emula;

import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Phone launcher surfaces carry record IDs; the shared runtime resolves current saved content. */
final class QuickAccess {
    static final String PREFS = "quick_access";
    private static final String LAST_CARD = "last_card";
    private static final String PREFIX = "card:";
    private static final String TOKEN = "launch_token";
    private static String publishedCatalog;

    static String action(Context context, String suffix) {
        return context.getPackageName() + ".action." + suffix;
    }

    static Intent cardIntent(Context context, String cardId) {
        return new Intent(context, MainActivity.class)
                .setAction(action(context, "TOGGLE_CARD"))
                .setData(new Uri.Builder().scheme("emula").authority(context.getPackageName())
                        .appendPath("card").appendPath(cardId).build())
                .putExtra(TOKEN, token(context))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    static Intent tileIntent(Context context, boolean choose) {
        return new Intent(context, MainActivity.class)
                .setAction(action(context, choose ? "CHOOSE_TILE_CARD" : "TOGGLE_LAST_CARD"))
                .putExtra(TOKEN, token(context))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    static Intent authorize(Context context, Intent intent) {
        return intent.putExtra(TOKEN, token(context));
    }

    static String cardId(Context context, Intent intent) {
        Uri uri = intent.getData();
        if (uri == null || !"emula".equals(uri.getScheme())
                || !context.getPackageName().equals(uri.getAuthority())
                || uri.getPathSegments().size() != 2 || !"card".equals(uri.getPathSegments().get(0))) return null;
        return uri.getLastPathSegment();
    }

    static Intent launcherIntent(Context context, String cardId) {
        return cardIntent(context, cardId).setClass(context, CardActionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    static PendingIntent pending(Context context, Intent intent, int requestCode) {
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static String token(Context context) {
        android.content.SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String token = preferences.getString(TOKEN, null);
        if (token == null) {
            token = java.util.UUID.randomUUID().toString();
            preferences.edit().putString(TOKEN, token).apply();
        }
        return token;
    }

    static boolean trusted(Context context, Intent intent) {
        return token(context).equals(intent.getStringExtra(TOKEN));
    }

    static String lastCard(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST_CARD, null);
    }

    static void remember(Context context, SavedCard card) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(LAST_CARD, card.id).apply();
    }

    static void refresh(Context context) {
        refresh(context, CardStore.getCards(context));
    }

    static void refresh(Context context, List<SavedCard> cards) {
        // A normal tile stays listening while QS is open and renders again on reopening.
        CardTileService.refreshListening();
        try {
            CardWidgetProvider.updateAll(context, cards);
        } catch (RuntimeException failure) {
            AppLog.warning("Could not refresh quick access surfaces", failure);
        }
        syncShortcuts(context, cards);
    }

    private static void syncShortcuts(Context context, List<SavedCard> cards) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null || !context.getSystemService(android.os.UserManager.class).isUserUnlocked()) return;
        StringBuilder signature = new StringBuilder();
        for (SavedCard card : cards) {
            signature.append(card.id.length()).append(':').append(card.id)
                    .append(card.name.length()).append(':').append(card.name);
        }
        String catalog = signature.toString();
        if (catalog.equals(publishedCatalog)) return;
        try {
            List<String> removed = new ArrayList<>();
            List<ShortcutInfo> pinned = new ArrayList<>();
            List<String> enabled = new ArrayList<>();
            for (ShortcutInfo old : manager.getPinnedShortcuts()) {
                if (!old.getId().startsWith(PREFIX)) continue;
                SavedCard card = QuickCardPolicy.find(cards, old.getId().substring(PREFIX.length()));
                if (card == null) removed.add(old.getId());
                else {
                    enabled.add(old.getId());
                    pinned.add(shortcut(context, card, 0));
                }
            }
            if (!removed.isEmpty()) manager.disableShortcuts(removed,
                    context.getString(R.string.quick_card_removed));
            if (!enabled.isEmpty()) manager.enableShortcuts(enabled);
            if (!pinned.isEmpty()) manager.updateShortcuts(pinned);
            List<ShortcutInfo> dynamic = new ArrayList<>();
            int count = Math.min(cards.size(), Math.min(4, manager.getMaxShortcutCountPerActivity()));
            for (int i = 0; i < count; i++) dynamic.add(shortcut(context, cards.get(i), i));
            if (manager.setDynamicShortcuts(dynamic)) publishedCatalog = catalog;
        } catch (RuntimeException failure) {
            AppLog.warning("Could not update card shortcuts", failure);
        }
    }

    private static ShortcutInfo shortcut(Context context, SavedCard card, int rank) {
        String label = card.name.replace('\r', ' ').replace('\n', ' ').trim();
        if (label.isEmpty()) label = context.getString(R.string.default_card_name);
        return new ShortcutInfo.Builder(context, PREFIX + card.id)
                .setShortLabel(label.length() > 40 ? label.substring(0, 40) : label)
                .setLongLabel(context.getString(R.string.quick_toggle_named, label))
                .setIcon(Icon.createWithResource(context, R.drawable.ic_card_shortcut))
                .setActivity(new ComponentName(context, MainActivity.class))
                .setIntent(launcherIntent(context, card.id)).setRank(rank).build();
    }

    static void pin(Context context, SavedCard card) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        try {
            if (manager != null && manager.isRequestPinShortcutSupported()
                    && manager.requestPinShortcut(shortcut(context, card, 0), null)) return;
        } catch (RuntimeException failure) {
            AppLog.warning("Could not pin card shortcut", failure);
        }
        Toast.makeText(context, R.string.quick_pin_unavailable, Toast.LENGTH_LONG).show();
    }

    static void used(Context context, SavedCard card) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null) return;
        try {
            manager.reportShortcutUsed(PREFIX + card.id);
        } catch (RuntimeException failure) {
            AppLog.warning("Could not report local shortcut usage", failure);
        }
    }

    static void addTile(android.app.Activity activity) {
        StatusBarManager manager = activity.getSystemService(StatusBarManager.class);
        if (manager == null) return;
        try {
            manager.requestAddTileService(new ComponentName(activity, CardTileService.class),
                    activity.getText(R.string.app_name),
                    Icon.createWithResource(activity, R.drawable.ic_card_24),
                    activity.getMainExecutor(), result -> {
                        if (activity.isDestroyed()) return;
                        if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                                || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                            QuickAccess.refresh(activity);
                            Toast.makeText(activity, R.string.quick_tile_added, Toast.LENGTH_SHORT).show();
                        } else if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED) {
                            Toast.makeText(activity, R.string.quick_tile_add_help, Toast.LENGTH_LONG).show();
                        }
                    });
        } catch (RuntimeException failure) {
            Toast.makeText(activity, R.string.quick_tile_add_help, Toast.LENGTH_LONG).show();
        }
    }
}
