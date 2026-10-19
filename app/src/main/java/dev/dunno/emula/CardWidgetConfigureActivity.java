package dev.dunno.emula;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

import java.util.List;

public final class CardWidgetConfigureActivity extends Activity {
    private AlertDialog dialog;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED);
        int id = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        AppWidgetProviderInfo info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id);
        if (info == null || !new ComponentName(this, CardWidgetProvider.class).equals(info.provider)) {
            finish();
            return;
        }
        List<SavedCard> cards = CardStore.getCards(this);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.quick_widget_configure)
                .setNegativeButton(R.string.action_cancel, (d, which) -> finish())
                .setOnCancelListener(d -> finish());
        if (cards.isEmpty()) {
            builder.setMessage(R.string.quick_add_card_first)
                    .setPositiveButton(R.string.quick_open_emula, (d, which) -> {
                        startActivity(new Intent(this, MainActivity.class));
                        finish();
                    });
        } else {
            builder.setItems(CardQuickActions.labels(cards), (d, which) -> {
                SavedCard selected = QuickCardPolicy.find(CardStore.getCards(this), cards.get(which).id);
                if (selected == null) { finish(); return; }
                CardWidgetProvider.configure(this, id, selected.id);
                setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id));
                finish();
            });
        }
        dialog = builder.show();
    }

    @Override protected void onDestroy() {
        if (dialog != null) dialog.dismiss();
        super.onDestroy();
    }
}
