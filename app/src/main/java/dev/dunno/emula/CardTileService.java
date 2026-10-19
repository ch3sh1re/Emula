package dev.dunno.emula;

import android.graphics.drawable.Icon;
import android.nfc.NfcAdapter;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import java.lang.ref.WeakReference;
import java.util.List;

public final class CardTileService extends TileService {
    private static volatile WeakReference<CardTileService> listening = new WeakReference<>(null);

    static CardTileService listeningService() { return listening.get(); }

    @Override public void onCreate() {
        super.onCreate();
        AppLog.info("QS event=create");
    }

    static void refreshListening() {
        CardTileService service = listeningService();
        if (service == null) return;
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            service.renderTile();
            return;
        }
        service.getMainExecutor().execute(() -> {
            if (listeningService() == service) service.renderTile();
        });
    }

    @Override public void onStartListening() {
        super.onStartListening();
        AppLog.info("QS event=listening_start");
        listening = new WeakReference<>(this);
        renderTile();
    }

    @Override public void onStopListening() {
        AppLog.info("QS event=listening_stop");
        releaseListening();
        super.onStopListening();
    }

    @Override public void onDestroy() {
        releaseListening();
        super.onDestroy();
    }

    private void releaseListening() {
        if (listeningService() == this) listening = new WeakReference<>(null);
    }

    private void renderTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        List<SavedCard> cards = CardStore.getCards(this);
        SavedCard card = QuickCardPolicy.tileCard(cards,
                CardSession.getActiveCardId(), QuickAccess.lastCard(this));
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
        boolean enabled = adapter != null && adapter.isEnabled();
        boolean active = enabled && card != null && CardSession.isActive(card.id);
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_card_24));
        tile.setLabel(getText(R.string.app_name));
        tile.setSubtitle(PhoneNfcRuntime.get(this).isBusy() ? getText(R.string.background_working)
                : card == null ? getText(R.string.quick_choose_card)
                : (enabled ? card.name : getText(R.string.quick_nfc_off)));
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setContentDescription(getString(R.string.quick_tile_description,
                card == null ? getString(R.string.quick_choose_card) : card.name,
                getString(active ? R.string.quick_active : R.string.quick_inactive)));
        tile.updateTile();
        AppLog.info("QS event=render state=" + tile.getState()
                + " busy=" + PhoneNfcRuntime.get(this).isBusy());
    }

    @Override public void onClick() {
        super.onClick();
        boolean locked = isLocked();
        AppLog.info("QS event=click locked=" + locked);
        Runnable launch = () -> {
            long started = android.os.SystemClock.elapsedRealtime();
            PhoneNfcRuntime runtime = PhoneNfcRuntime.get(this);
            PhoneNfcSession.Reason reason = runtime.quickProblem(null);
            AppLog.info("QS event=preflight reason=" + (reason == null ? "READY" : reason.name())
                    + " duration_ms=" + (android.os.SystemClock.elapsedRealtime() - started));
            if (reason == PhoneNfcSession.Reason.BUSY) {
                android.widget.Toast.makeText(this, R.string.background_busy, android.widget.Toast.LENGTH_SHORT).show();
            } else if (reason != null) {
                startActivityAndCollapse(QuickAccess.pending(this, QuickAccess.tileIntent(this, false), 0));
            } else {
                try {
                    startForegroundService(BackgroundNfcService.command(this, null, false));
                } catch (RuntimeException failure) {
                    startActivityAndCollapse(QuickAccess.pending(this, QuickAccess.tileIntent(this, false), 0));
                }
            }
        };
        if (locked) unlockAndRun(launch);
        else launch.run();
    }
}
