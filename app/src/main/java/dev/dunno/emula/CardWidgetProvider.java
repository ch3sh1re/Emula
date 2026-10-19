package dev.dunno.emula;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.widget.RemoteViews;

import java.util.List;

public final class CardWidgetProvider extends AppWidgetProvider {
    static String key(int id) { return "widget_" + id; }

    static String cardId(Context context, int id) {
        return context.getSharedPreferences(QuickAccess.PREFS, Context.MODE_PRIVATE)
                .getString(key(id), null);
    }

    static void configure(Context context, int id, String cardId) {
        context.getSharedPreferences(QuickAccess.PREFS, Context.MODE_PRIVATE)
                .edit().putString(key(id), cardId).apply();
        update(context, id, CardStore.getCards(context));
    }

    static void updateAll(Context context, List<SavedCard> cards) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int id : manager.getAppWidgetIds(new ComponentName(context, CardWidgetProvider.class))) {
            update(context, id, cards);
        }
    }

    static RemoteViews views(Context context, int id, List<SavedCard> cards) {
        SavedCard card = QuickCardPolicy.find(cards, cardId(context, id));
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
        boolean enabled = adapter != null && adapter.isEnabled();
        boolean active = enabled && card != null && CardSession.isActive(card.id);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.card_widget);
        views.setTextViewText(R.id.widget_name, card == null
                ? context.getText(R.string.quick_choose_card) : card.name);
        views.setTextViewText(R.id.widget_state, context.getText(card == null
                ? R.string.quick_widget_choose : !enabled ? R.string.quick_nfc_off
                : active ? R.string.quick_widget_active : R.string.quick_widget_inactive));
        views.setInt(R.id.widget_root, "setBackgroundResource", active
                ? R.drawable.card_widget_active : R.drawable.card_widget_background);
        Intent click = card != null ? QuickAccess.launcherIntent(context, card.id)
                : new Intent(context, CardWidgetConfigureActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        views.setOnClickPendingIntent(R.id.widget_root, QuickAccess.pending(context, click, id));
        views.setContentDescription(R.id.widget_root, card == null
                ? context.getString(R.string.quick_choose_card)
                : context.getString(R.string.quick_toggle_named, card.name));
        return views;
    }

    static void update(Context context, int id, List<SavedCard> cards) {
        AppWidgetManager.getInstance(context).updateAppWidget(id, views(context, id, cards));
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        List<SavedCard> cards = CardStore.getCards(context);
        for (int id : ids) update(context, id, cards);
    }

    @Override public void onDeleted(Context context, int[] ids) {
        android.content.SharedPreferences.Editor editor = context
                .getSharedPreferences(QuickAccess.PREFS, Context.MODE_PRIVATE).edit();
        for (int id : ids) editor.remove(key(id));
        editor.apply();
    }

    @Override public void onRestored(Context context, int[] oldIds, int[] newIds) {
        android.content.SharedPreferences preferences = context
                .getSharedPreferences(QuickAccess.PREFS, Context.MODE_PRIVATE);
        android.content.SharedPreferences.Editor editor = preferences.edit();
        for (int i = 0; i < oldIds.length; i++) {
            editor.putString(key(newIds[i]), preferences.getString(key(oldIds[i]), null));
            editor.remove(key(oldIds[i]));
        }
        editor.apply();
        onUpdate(context, AppWidgetManager.getInstance(context), newIds);
    }
}
