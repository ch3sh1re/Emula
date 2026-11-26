package dev.dunno.emula;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;

import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.wear.protolayout.ResourceBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.UUID;

public final class WatchCardTileService extends TileService {
    private final Handler main = new Handler(Looper.getMainLooper());
    static void refresh(Context context) {
        TileService.getUpdater(context).requestUpdate(WatchCardTileService.class);
    }

    static void refresh(Context context, int tileId) {
        if (tileId > 0) TileService.getUpdater(context).requestUpdate(WatchCardTileService.class, tileId);
        else refresh(context);
    }

    @Override protected ListenableFuture<TileBuilders.Tile> onTileRequest(RequestBuilders.TileRequest request) {
        return CallbackToFutureAdapter.getFuture(result -> {
            WatchNfcRuntime.WORK.execute(() -> {
                try { handle(request, result); }
                catch (RuntimeException failure) { result.setException(failure); }
            });
            return "Emula card tile";
        });
    }

    @Override protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(
            RequestBuilders.ResourcesRequest request) {
        return CallbackToFutureAdapter.getFuture(result -> {
            result.set(WatchTileLayout.resources());
            return "Emula tile resources";
        });
    }

    private void handle(RequestBuilders.TileRequest request,
            CallbackToFutureAdapter.Completer<TileBuilders.Tile> result) {
        int tileId = request.getTileId();
        SharedPreferences prefs = WatchNfcRuntime.tilePreferences(this);
        WatchSessionStore store = new WatchSessionStore(this);
        List<SavedCard> cards = CardStore.getCards(this);
        WatchNfcSession.Pending pending = store.load();
        String selectedId = prefs.getString("card." + tileId, "");
        int selected = 0;
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).id.equals(selectedId)) { selected = i; break; }
        }
        SavedCard card = cards.isEmpty() ? null : cards.get(selected);
        String cardId = card == null ? "" : card.id;
        int seconds = prefs.getInt("seconds." + tileId, 0);
        WatchTileLayout layout = new WatchTileLayout(this, request.getDeviceConfiguration());
        WatchTileAction tap = WatchTileAction.parse(request.getCurrentState().getLastClickableId());
        boolean operated = false;
        if (tap != null && tap.applies(store.revision(), cardId, pending) && store.consumeTap(tap.revision)) {
            switch (tap.kind) {
                case SELECT -> {
                    for (int i = 0; i < cards.size(); i++) {
                        if (cards.get(i).id.equals(tap.target)) {
                            selected = i;
                            card = cards.get(i);
                            prefs.edit().putString("card." + tileId, card.id).apply();
                            break;
                        }
                    }
                }
                case TIMER -> {
                    seconds = WatchTileAction.nextDuration(seconds);
                    prefs.edit().putInt("seconds." + tileId, seconds).apply();
                }
                case START, STOP -> {
                    operated = true;
                    // A fast NFC call can return the final layout in this request. A slow one
                    // must release the renderer before its ten-second response deadline.
                    Runnable progress = () -> result.set(layout.working(tap.kind == WatchTileAction.Kind.START));
                    main.postDelayed(progress, 1200);
                    try {
                        if (tap.kind == WatchTileAction.Kind.START) prefs.edit().putInt("lastStartTile", tileId).apply();
                        operate(tap, card, seconds, store, pending);
                        pending = store.load();
                    } finally {
                        main.removeCallbacks(progress);
                    }
                }
            }
        }
        String next = cards.size() <= 1 ? null : cards.get((selected + 1) % cards.size()).id;
        SavedCard activeCard = null;
        if (pending != null) {
            for (SavedCard saved : cards) if (saved.id.equals(pending.cardId)) { activeCard = saved; break; }
        }
        long revision = store.revision();
        boolean delivered = result.set(layout.cards(card, activeCard, next, pending, revision, seconds,
                prefs.getString("error", "")));
        if (delivered && operated && pending != null && tap.kind == WatchTileAction.Kind.START) {
            WatchStopAlarm.activeTileDelivered(pending, seconds);
        }
        if (delivered && pending == null) WatchStopAlarm.idleTileDelivered(tileId, revision);
        if (operated) refresh(this, tileId);
    }

    private void operate(WatchTileAction tap, SavedCard card, int seconds, WatchSessionStore store,
            WatchNfcSession.Pending pending) {
        Context context = getApplicationContext();
        StringBuilder evidence = new StringBuilder();
        Exception problem = null;
        WatchNfcSession session = new WatchNfcSession(store, new WatchHardwareBackend(context, evidence),
                Build.FINGERPRINT);
        try {
            WatchNfcRuntime.requireNfc(context, NfcAdapter.getDefaultAdapter(context));
            if (tap.kind == WatchTileAction.Kind.STOP) {
                if (pending.path == WatchNfcSession.Path.RAW
                        && "UNAVAILABLE".equals(LegacyNfcController.pollingMethod(context))) {
                    throw new IllegalStateException(getString(R.string.tile_foreground));
                }
                session.restore();
            } else {
                KeyguardManager keyguard = getSystemService(KeyguardManager.class);
                if (keyguard != null && keyguard.isDeviceLocked()) {
                    throw new IllegalStateException(getString(R.string.tile_unlock));
                }
                WatchNfcSession.Path path = WatchNfcRuntime.path(context);
                boolean consent = WatchNfcRuntime.hasRawConsent(context);
                if (path == null || (path == WatchNfcSession.Path.RAW && !consent)) {
                    throw new IllegalStateException(getString(R.string.tile_foreground));
                }
                String timerId = seconds == 0 ? "" : UUID.randomUUID().toString();
                long deadline = seconds == 0 ? 0 : SystemClock.elapsedRealtime() + seconds * 1000L;
                session.activate(card.id, card.uid, path, consent, timerId, deadline);
            }
        } catch (Exception failure) {
            problem = failure;
        }
        WatchNfcRuntime.recordTileResult(this, "TILE " + tap.kind, evidence, problem);
        if (problem == null) {
            int effect = tap.kind == WatchTileAction.Kind.START ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK;
            TouchFeedback.play(context, VibrationEffect.createPredefined(effect));
        }
    }
}
