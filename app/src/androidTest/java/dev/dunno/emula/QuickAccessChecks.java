package dev.dunno.emula;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.TypedArray;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.TextView;

import java.util.List;
import java.util.UUID;

final class QuickAccessChecks {
    static void intentsAndWidget(Context base) {
        String file = "quick_test_" + UUID.randomUUID();
        SharedPreferences isolated = base.getSharedPreferences(file, Context.MODE_PRIVATE);
        Context context = new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return QuickAccess.PREFS.equals(name) ? isolated : super.getSharedPreferences(name, mode);
            }
        };
        SavedCard card = new SavedCard(UUID.randomUUID().toString(), "Office", new byte[] {1, 2, 3, 4});
        int widget = -1000;
        PendingIntent pending = null;
        try {
            Intent intent = QuickAccess.cardIntent(context, card.id);
            check(intent.getComponent().equals(new ComponentName(context, MainActivity.class)),
                    "Interactive fallback must use the existing NFC activity");
            check(QuickAccess.launcherIntent(context, card.id).getComponent().equals(
                    new ComponentName(context, CardActionActivity.class)),
                    "Launcher actions must use the no-display background router");
            check((QuickAccess.launcherIntent(context, card.id).getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0,
                    "Launcher route must suppress task launch animation");
            check(intent.getData().getLastPathSegment().equals(card.id), "Link must contain the record ID");
            check(QuickAccess.trusted(context, intent), "Own launcher request must be authenticated");
            Intent forged = new Intent(context, MainActivity.class).setAction(intent.getAction())
                    .setData(intent.getData());
            check(!QuickAccess.trusted(context, forged), "Arbitrary explicit intents must not toggle NFC");
            Intent other = QuickAccess.cardIntent(context, UUID.randomUUID().toString());
            check(!intent.filterEquals(other), "Different cards need distinct PendingIntent identities");
            pending = QuickAccess.pending(context, intent, widget);
            check(pending.isImmutable(), "Launcher PendingIntents must be immutable");
            isolated.edit().putString(CardWidgetProvider.key(widget), card.id).commit();
            View rendered = CardWidgetProvider.views(context, widget, List.of(card)).apply(context, null);
            check(((TextView) rendered.findViewById(R.id.widget_name)).getText().toString().equals("Office"),
                    "Configured widget must show its saved card");
            SavedCard edited = new SavedCard(card.id, "Renamed", new byte[] {5, 6, 7, 8});
            View renamed = CardWidgetProvider.views(context, widget, List.of(edited)).apply(context, null);
            check(((TextView) renamed.findViewById(R.id.widget_name)).getText().toString().equals("Renamed"),
                    "Widget must resolve current saved content");
            View missing = CardWidgetProvider.views(context, widget, List.of()).apply(context, null);
            check(((TextView) missing.findViewById(R.id.widget_name)).getText().toString()
                            .equals(context.getString(R.string.quick_choose_card)),
                    "Deleted card must offer reconfiguration");
        } finally {
            if (pending != null) pending.cancel();
            base.deleteSharedPreferences(file);
        }
    }

    static void components(Context context) throws Exception {
        int theme = context.getPackageManager().getActivityInfo(
                new ComponentName(context, CardActionActivity.class), 0).theme;
        TypedArray window = new ContextThemeWrapper(context, theme).obtainStyledAttributes(new int[] {
                android.R.attr.windowNoDisplay, android.R.attr.windowIsTranslucent,
                android.R.attr.windowDisablePreview, android.R.attr.windowBackground,
                android.R.attr.windowAnimationStyle});
        try {
            check(window.getBoolean(0, false) && window.getBoolean(1, false)
                            && window.getBoolean(2, false),
                    "Router must have a translucent, no-display window without a launch preview");
            check(window.getDrawable(3) == null && window.getResourceId(4, 0) == 0,
                    "Router must not inherit an opaque background or window animations");
        } finally { window.recycle(); }
        ServiceInfo background = context.getPackageManager().getServiceInfo(
                new ComponentName(context, BackgroundNfcService.class), 0);
        check(!background.exported && background.getForegroundServiceType()
                        == ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                "Background control must be private and use the connected-device service type");
        ServiceInfo tile = context.getPackageManager().getServiceInfo(
                new ComponentName(context, CardTileService.class), PackageManager.GET_META_DATA);
        check(tile.exported && "android.permission.BIND_QUICK_SETTINGS_TILE".equals(tile.permission),
                "Only System UI may bind the tile service");
        check(!tile.metaData.getBoolean("android.service.quicksettings.ACTIVE_TILE")
                && tile.metaData.getBoolean("android.service.quicksettings.TOGGLEABLE_TILE"),
                "Tile must listen while QS is open and expose toggle semantics");
        AppWidgetProviderInfo widget = AppWidgetManager.getInstance(context).getInstalledProviders()
                .stream().filter(info -> info.provider.equals(
                        new ComponentName(context, CardWidgetProvider.class))).findFirst().orElseThrow();
        check(new ComponentName(context, CardWidgetConfigureActivity.class).equals(widget.configure),
                "Widget configuration must resolve in both debug and release packages");
        check((widget.widgetFeatures & AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE) != 0,
                "Placed widgets must support selecting another card");
        Intent preferences = new Intent(android.service.quicksettings.TileService.ACTION_QS_TILE_PREFERENCES)
                .setPackage(context.getPackageName());
        check(new ComponentName(context, CardTilePreferencesActivity.class).equals(
                        preferences.resolveActivity(context.getPackageManager())),
                "System tile preferences must always open card selection");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
