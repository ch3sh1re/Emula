package dev.dunno.emula;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android storage/provider checks using isolated preferences and no Activity or NFC calls. */
final class CardTransferChecks {
    static void watchAppend(Context base) throws Exception {
        String testFile = "watch_transfer_test_" + UUID.randomUUID();
        SharedPreferences isolated = base.getSharedPreferences(testFile, Context.MODE_PRIVATE);
        Context context = new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return isolated; }
        };
        String oldActive = CardSession.getActiveCardId();
        try {
            SavedCard existing = CardStore.save(context, null, "Watch name", new byte[]{1,2,3,4});
            CardSession.setActiveCardId(existing.id);
            List<WatchCardPacket.Entry> incoming = WatchCardPacket.read(new java.io.ByteArrayInputStream(
                    WatchCardPacket.encode(List.of(
                            new SavedCard("phone-a", "Phone name", existing.uid),
                            new SavedCard("phone-b", "Long", new byte[]{0,1,2,3,4,5,6}),
                            new SavedCard("phone-c", "Repeated", new byte[]{0,1,2,3,4,5,6})))));
            WatchCardPacket.Result first = CardStore.appendReceived(context, incoming);
            check(first.added == 1 && first.skipped == 2, "Received batch must deduplicate by UID");
            List<SavedCard> cards = CardStore.getCards(context);
            check(cards.size() == 2 && cards.get(0).id.equals(existing.id)
                    && cards.get(0).name.equals("Watch name"), "Existing watch cards must remain unchanged");
            WatchCardPacket.Result retry = CardStore.appendReceived(context, incoming);
            check(retry.added == 0 && retry.skipped == 3, "Retry must not add cards");
            check(CardStore.getCards(context).get(1).id.equals(cards.get(1).id), "Retry must retain received IDs");
            check(existing.id.equals(CardSession.getActiveCardId()), "Receipt must not change active card state");
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                java.util.concurrent.Future<?> a = workers.submit(() -> {
                    try { start.await(); CardStore.appendReceived(context,
                            List.of(new WatchCardPacket.Entry("A", new byte[]{9,8,7,1}))); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                });
                java.util.concurrent.Future<?> b = workers.submit(() -> {
                    try { start.await(); CardStore.appendReceived(context,
                            List.of(new WatchCardPacket.Entry("B", new byte[]{9,8,7,2}))); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                });
                start.countDown(); a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS);
                check(CardStore.getCards(context).size() == 4, "Concurrent imports must not lose either card");
            } finally { workers.shutdownNow(); }
        } finally {
            if (oldActive == null) CardSession.clearActiveCard(); else CardSession.setActiveCardId(oldActive);
            base.deleteSharedPreferences(testFile);
        }
    }

    static void storage(Context base) {
        String testFile = "csv_test_" + UUID.randomUUID();
        SharedPreferences isolated = base.getSharedPreferences(testFile, Context.MODE_PRIVATE);
        Context context = new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                check(name.equals("office_cards"), "Unexpected card storage file");
                return isolated;
            }
        };
        try {
            SavedCard existing = CardStore.save(context, null, "Keep", new byte[] {1, 2, 3, 4});
            List<CardCsv.Entry> parsed = CardCsv.decode(
                    "name,uid\nChanged,01020304\nNew,00010203040506\nRepeated,00010203040506\n");
            CardImport.Result added = CardImport.apply(context, parsed, false);
            List<SavedCard> merged = CardStore.getCards(context);
            check(added.added == 1 && added.skipped == 2, "Merge counts must include duplicates");
            check(merged.get(0).id.equals(existing.id) && merged.get(0).name.equals("Keep"),
                    "Merge must preserve the existing identity and name");
            check(Arrays.equals(merged.get(1).uid, parsed.get(1).uid), "Long UID must persist");
            CardImport.Result replaced = CardImport.apply(context, parsed, true);
            List<SavedCard> loaded = CardStore.getCards(context);
            check(replaced.added == 3 && loaded.size() == 3, "Replace must preserve every file row");
            check(loaded.stream().noneMatch(card -> card.id.equals(existing.id)),
                    "Replace must generate fresh local identities");
            String beforeInvalid = isolated.getString("cards", null);
            try {
                CardImport.apply(context, CardCsv.decode(
                        "name,uid\nValid,01020304\nBroken,ZZ"), true);
                throw new AssertionError("Malformed file must be rejected");
            } catch (CardCsv.FormatException expected) {
                check(beforeInvalid.equals(isolated.getString("cards", null)),
                        "Malformed import must not modify persistent storage");
            }
        } finally {
            base.deleteSharedPreferences(testFile);
        }
    }

    static void provider(Context context) throws Exception {
        List<SavedCard> cards = List.of(new SavedCard("local-id", "Вход, \"A\"",
                new byte[] {0, 1, 2, (byte) 255}));
        String csv = CardCsv.encode(cards);
        Uri uri = CardExportProvider.create(context, csv);
        Uri second = CardExportProvider.create(context, "name,uid\nSecond export,01020304\n");
        File legacy = new File(new File(context.getCacheDir(), "card-exports"),
                "emula-cards-20260101-120000-" + UUID.randomUUID() + ".csv");
        java.nio.file.Files.write(legacy.toPath(), csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            check(!uri.equals(second), "Every export must retain its own immutable snapshot");
            check("emula.csv".equals(uri.getLastPathSegment())
                    && "emula.csv".equals(second.getLastPathSegment()),
                    "Clients using the URI filename must see the short export name");
            try (Cursor cursor = context.getContentResolver().query(
                    uri, null, null, null, null)) {
                check(cursor != null && cursor.moveToFirst(), "Export metadata must be available");
                check("emula.csv".equals(cursor.getString(
                        cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))),
                        "Shared document metadata must use the short export name");
                check(cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)) > 0,
                        "Shared document must report its byte size");
            }
            try (AssetFileDescriptor descriptor = context.getContentResolver()
                    .openAssetFileDescriptor(uri, "r", new CancellationSignal());
                 InputStream input = descriptor.createInputStream()) {
                List<CardCsv.Entry> decoded = CardCsv.read(input);
                check(decoded.get(0).name.equals(cards.get(0).name), "Shared CSV must preserve Unicode");
                check(Arrays.equals(decoded.get(0).uid, cards.get(0).uid), "Shared UID must round-trip");
            }
            try (InputStream input = context.getContentResolver().openInputStream(second)) {
                check(CardCsv.read(input).get(0).name.equals("Second export"),
                        "Equal visible filenames must not overwrite previous exports");
            }
            Uri oldUri = new Uri.Builder().scheme("content")
                    .authority(context.getPackageName() + ".card-exports")
                    .appendPath(legacy.getName()).build();
            try (InputStream input = context.getContentResolver().openInputStream(oldUri)) {
                check(CardCsv.read(input).get(0).name.equals(cards.get(0).name),
                        "Already-shared legacy snapshots must remain readable after updating");
            }
            try {
                context.getContentResolver().openFileDescriptor(uri, "w").close();
                throw new AssertionError("Shared export must reject writes");
            } catch (java.io.FileNotFoundException expected) {
                // Read-only provider rejects write grants even within the app.
            }
            try {
                Uri traversal = uri.buildUpon().appendPath("..").appendPath("office_cards.xml").build();
                context.getContentResolver().openInputStream(traversal).close();
                throw new AssertionError("Provider must reject paths outside one export snapshot");
            } catch (java.io.FileNotFoundException expected) {
                // Only generated snapshot filenames can be opened.
            }
            try {
                Uri wrongName = new Uri.Builder().scheme("content").authority(uri.getAuthority())
                        .appendPath(uri.getPathSegments().get(0)).appendPath("office_cards.xml").build();
                context.getContentResolver().openInputStream(wrongName).close();
                throw new AssertionError("Provider must reject other names within a snapshot");
            } catch (java.io.FileNotFoundException expected) {
                // The final URI segment is a fixed document name, not a filesystem path.
            }
        } finally {
            for (Uri own : List.of(uri, second)) {
                File ownFile = new File(new File(context.getCacheDir(), "card-exports"),
                        own.getPathSegments().get(0) + ".csv");
                java.nio.file.Files.deleteIfExists(ownFile.toPath());
            }
            java.nio.file.Files.deleteIfExists(legacy.toPath());
        }
    }

    static void intentFilters(Context context) {
        Intent share = new Intent(Intent.ACTION_SEND).setType(CardCsv.MIME_TYPE);
        boolean acceptsShare = context.getPackageManager().queryIntentActivities(
                share, PackageManager.MATCH_DEFAULT_ONLY).stream()
                .anyMatch(info -> info.activityInfo.packageName.equals(context.getPackageName())
                        && info.activityInfo.name.equals(MainActivity.class.getName()));
        check(acceptsShare, "Manifest must make Emula a CSV share target");
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(
                Uri.parse("content://example.test/cards.csv"), CardCsv.MIME_TYPE);
        boolean acceptsOpen = context.getPackageManager().queryIntentActivities(
                view, PackageManager.MATCH_DEFAULT_ONLY).stream()
                .anyMatch(info -> info.activityInfo.packageName.equals(context.getPackageName()));
        check(acceptsOpen, "Manifest must accept opening a CSV document");
    }

    static void cancelPipeRead() throws Exception {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService cleanup = Executors.newSingleThreadExecutor();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch notified = new CountDownLatch(1);
        AtomicBoolean returnedCards = new AtomicBoolean();
        CancellationSignal signal = new CancellationSignal();
        signal.setOnCancelListener(notified::countDown); // Acts as the source provider.
        CardReadCancellation request = new CardReadCancellation(cleanup, signal::cancel);
        try (InputStream input = new FilterInputStream(new AssetFileDescriptor(
                pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH).createInputStream()) {
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                reading.countDown();
                return super.read(bytes, offset, length);
            }
        }) {
            request.setTask(worker.submit(() -> {
                try (InputStream stream = input) {
                    if (request.attach(stream)) {
                        CardCsv.read(stream);
                        returnedCards.set(true);
                    }
                } catch (IOException | java.util.concurrent.CancellationException expected) {
                    // Both an interrupted parse and a closed Android pipe are valid cancellation.
                } finally {
                    request.finished();
                }
            }));
            check(reading.await(5, TimeUnit.SECONDS), "Pipe read must start");
            request.cancel();
            check(notified.await(5, TimeUnit.SECONDS), "Provider must receive cancellation");
            String nextName = worker.submit(() -> CardCsv.decode(
                    "name,uid\nNext import,01020304\n").get(0).name).get(5, TimeUnit.SECONDS);
            check(nextName.equals("Next import"), "Cancelled Android read must release worker");
            check(!returnedCards.get(), "Cancelled read must not return cards");
        } finally {
            pipe[0].close();
            pipe[1].close();
            worker.shutdownNow();
            cleanup.shutdownNow();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
