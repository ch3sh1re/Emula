package dev.dunno.emula;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.service.quicksettings.Tile;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises an actual System UI bound tile, without invoking onClick or any NFC command. */
final class TileListeningChecks {
    static void refreshWhileListening(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        String component = new ComponentName(context, CardTileService.class).flattenToString();
        AtomicReference<CardTileService> visible = new AtomicReference<>();
        test.getUiAutomation().adoptShellPermissionIdentity();
        try {
            shell(test, "cmd statusbar add-tile " + component);
            shell(test, "cmd statusbar expand-settings");
            await(test, () -> {
                CardTileService service = CardTileService.listeningService();
                if (service == null || service.getQsTile() == null) return false;
                Tile tile = service.getQsTile();
                check(CardSession.getActiveCardId() == null, "Test requires no active emulation");
                // Reproduce a cached active state after successful restoration, while the
                // service is already listening. No new onStartListening call should be needed.
                tile.setState(Tile.STATE_ACTIVE);
                tile.setLabel("Stale tile state");
                QuickAccess.refresh(context);
                check(tile.getState() == Tile.STATE_INACTIVE
                                && context.getString(R.string.app_name).contentEquals(tile.getLabel()),
                        "A listening tile must refresh immediately without another onStartListening callback");
                visible.set(service);
                return true;
            });
            // Allow System UI to process updateTile. An active-mode tile would end its
            // listening window here even though QS is still visible.
            Thread.sleep(250);
            test.runOnMainSync(() -> check(CardTileService.listeningService() == visible.get(),
                    "Updating a visible tile must retain listening until QS closes"));
            shell(test, "cmd statusbar collapse");
            await(test, () -> CardTileService.listeningService() == null);
            shell(test, "cmd statusbar expand-settings");
            await(test, () -> CardTileService.listeningService() != null
                    && CardTileService.listeningService().getQsTile() != null);
        } finally {
            shell(test, "cmd statusbar collapse");
            shell(test, "cmd statusbar remove-tile " + component);
            test.getUiAutomation().dropShellPermissionIdentity();
        }
    }

    private static void shell(Instrumentation test, String command) throws Exception {
        try (ParcelFileDescriptor fd = test.getUiAutomation().executeShellCommand(command);
             java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            byte[] buffer = new byte[1024];
            while (input.read(buffer) != -1) { }
        }
    }

    private interface Condition { boolean ready(); }
    private static void await(Instrumentation test, Condition condition) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            AtomicBoolean ready = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            test.runOnMainSync(() -> {
                try { ready.set(condition.ready()); }
                catch (Throwable error) { failure.set(error); }
            });
            if (failure.get() != null) throw new AssertionError(failure.get());
            if (ready.get()) return;
            Thread.sleep(40);
        }
        throw new AssertionError("Tile state did not update while listening");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
