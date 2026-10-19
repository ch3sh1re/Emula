package dev.dunno.emula;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Launcher shortcuts require an Activity. This no-display router finishes before drawing. */
public final class CardActionActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        try {
            Intent request = getIntent();
            String id = QuickAccess.cardId(this, request);
            if (!QuickAccess.action(this, "TOGGLE_CARD").equals(request.getAction())
                    || !QuickAccess.trusted(this, request) || id == null) {
                Toast.makeText(this, R.string.quick_invalid_request, Toast.LENGTH_SHORT).show();
                return;
            }
            PhoneNfcRuntime runtime = PhoneNfcRuntime.get(this);
            PhoneNfcSession.Reason reason = runtime.quickProblem(id);
            if (reason == PhoneNfcSession.Reason.BUSY) {
                Toast.makeText(this, R.string.background_busy, Toast.LENGTH_SHORT).show();
            } else if (reason != null) {
                startActivity(QuickAccess.cardIntent(this, id));
            } else {
                try {
                    startForegroundService(BackgroundNfcService.command(this, id, false));
                } catch (RuntimeException failure) {
                    startActivity(QuickAccess.cardIntent(this, id));
                }
            }
        } finally {
            finish();
        }
    }
}
