package dev.dunno.emula;

import android.app.Activity;
import android.os.Bundle;

/** System UI may launch the resolved component without retaining the query action. */
public final class CardTilePreferencesActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(QuickAccess.tileIntent(this, true));
        finish();
    }
}
