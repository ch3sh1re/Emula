package dev.dunno.emula;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** File and Sharesheet UI; catalog writes are possible only through explicit approval. */
final class CardTransfer {
    interface Host {
        boolean canImport();
        void importApproved(List<CardCsv.Entry> cards, boolean replace);
        void message(String text);
        Dialog chooseExport(Consumer<Boolean> selected);
    }

    private static final int OPEN_CSV = 701;
    private static final int SAVE_CSV = 702;
    private static final String IMPORT_URI = "csv.import_uri";
    private static final String EXPORT_URI = "csv.export_uri";
    private final Activity activity;
    private final Host host;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService cleanup = Executors.newSingleThreadExecutor();
    private CardReadCancellation importRead;
    private Uri importUri;
    private Uri exportUri;
    private List<CardCsv.Entry> incoming;
    private AlertDialog importDialog;
    private Dialog exportDialog;
    private boolean resumed;
    private boolean disposed;
    private boolean reading;
    private int generation;

    CardTransfer(Activity activity, Host host, Bundle state) {
        this.activity = activity;
        this.host = host;
        if (state != null) {
            String pendingImport = state.getString(IMPORT_URI);
            String pendingExport = state.getString(EXPORT_URI);
            if (pendingImport != null) importUri = Uri.parse(pendingImport);
            if (pendingExport != null) exportUri = Uri.parse(pendingExport);
        }
    }

    boolean hasPendingImport() { return importUri != null; }

    void saveState(Bundle state) {
        if (importUri != null) state.putString(IMPORT_URI, importUri.toString());
        if (exportUri != null) state.putString(EXPORT_URI, exportUri.toString());
    }

    void onResume() {
        resumed = true;
        onHostReady();
    }

    void onPause() { resumed = false; }

    void dispose() {
        disposed = true;
        clearImport();
        if (exportDialog != null) exportDialog.dismiss();
        io.shutdownNow();
        cleanup.shutdown();
    }

    void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_VIEW.equals(action)) return;
        try {
            Uri uri = Intent.ACTION_SEND.equals(action)
                    ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class) : intent.getData();
            if (uri == null && intent.getClipData() != null
                    && intent.getClipData().getItemCount() == 1) {
                uri = intent.getClipData().getItemAt(0).getUri();
            }
            accept(uri);
        } catch (RuntimeException failure) {
            error(activity.getString(R.string.csv_unreadable_file));
        }
    }

    void pickImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                        CardCsv.MIME_TYPE, "application/csv", "text/comma-separated-values",
                        "application/vnd.ms-excel", "text/plain", "application/octet-stream"});
        try {
            activity.startActivityForResult(intent, OPEN_CSV);
        } catch (ActivityNotFoundException failure) {
            error(activity.getString(R.string.csv_picker_unavailable));
        }
    }

    void chooseExport() {
        if (exportDialog != null) return;
        exportDialog = host.chooseExport(share -> { exportDialog = null; export(share); });
        exportDialog.setOnDismissListener(dialog -> exportDialog = null);
    }

    boolean onActivityResult(int request, int result, Intent data) {
        if (request != OPEN_CSV && request != SAVE_CSV) return false;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) {
            if (request == SAVE_CSV) exportUri = null;
            return true;
        }
        if (request == OPEN_CSV) {
            accept(data.getData());
        } else {
            Uri source = exportUri;
            exportUri = null;
            Uri destination = data.getData();
            if (source == null) {
                error(activity.getString(R.string.csv_export_expired));
                return true;
            }
            io.execute(() -> {
                try (InputStream input = activity.getContentResolver().openInputStream(source);
                     OutputStream output = activity.getContentResolver()
                             .openOutputStream(destination, "wt")) {
                    if (input == null || output == null) throw new IOException("Cannot open the file");
                    input.transferTo(output);
                    post(() -> host.message(activity.getString(R.string.csv_saved)));
                } catch (Exception failure) {
                    post(() -> error(activity.getString(R.string.csv_export_failed)));
                }
            });
        }
        return true;
    }

    private void accept(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) {
            error(activity.getString(R.string.csv_unreadable_file));
            return;
        }
        clearImport();
        importUri = uri;
        onHostReady();
    }

    void onHostReady() {
        if (disposed || !resumed || importUri == null || !host.canImport()) return;
        if (incoming != null) {
            if (importDialog == null) confirmImport();
        } else if (!reading) {
            read();
        }
    }

    private void read() {
        reading = true;
        int requestGeneration = generation;
        Uri source = importUri;
        CancellationSignal signal = new CancellationSignal();
        CardReadCancellation request = new CardReadCancellation(cleanup, signal::cancel);
        importRead = request;
        importDialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.csv_import_title)
                .setMessage(R.string.csv_reading)
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> clearImport())
                .setOnCancelListener(dialog -> clearImport())
                .show();
        request.setTask(io.submit(() -> {
            try (InputStream input = openImport(source, signal)) {
                if (!request.attach(input)) return;
                List<CardCsv.Entry> parsed = CardCsv.read(input);
                post(() -> {
                    if (requestGeneration != generation) return;
                    importRead = null;
                    reading = false;
                    incoming = parsed;
                    dismissImportDialog();
                    onHostReady();
                });
            } catch (Exception failure) {
                String reason = failure instanceof CardCsv.FormatException
                        ? failure.getMessage() : activity.getString(R.string.csv_unreadable_file);
                post(() -> {
                    if (requestGeneration != generation) return;
                    clearImport();
                    error(reason);
                });
            } finally {
                request.finished();
            }
        }));
    }

    private InputStream openImport(Uri source, CancellationSignal signal) throws IOException {
        AssetFileDescriptor descriptor = activity.getContentResolver()
                .openAssetFileDescriptor(source, "r", signal);
        if (descriptor == null) throw new IOException("Cannot open the file");
        try {
            // This stream owns the descriptor and respects any asset offset/length.
            return descriptor.createInputStream();
        } catch (IOException | RuntimeException failure) {
            descriptor.close();
            throw failure;
        }
    }

    private void confirmImport() {
        int existing = CardStore.getCards(activity).size();
        StringBuilder preview = new StringBuilder(activity.getString(
                existing == 0 ? R.string.csv_import_empty_message : R.string.csv_import_message,
                incoming.size(), existing));
        preview.append("\n\n");
        for (int i = 0; i < Math.min(incoming.size(), 3); i++) {
            String name = incoming.get(i).name.replace('\r', ' ').replace('\n', ' ');
            preview.append(name.length() > 60 ? name.substring(0, 60) + "…" : name).append('\n');
        }
        if (incoming.size() > 3) preview.append("…");
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.csv_import_title)
                .setMessage(preview.toString().trim())
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> clearImport())
                .setOnCancelListener(dialog -> clearImport());
        if (existing == 0) {
            builder.setPositiveButton(R.string.csv_import_action,
                    (dialog, which) -> approve(true));
        } else {
            builder.setPositiveButton(R.string.csv_add_action,
                    (dialog, which) -> approve(false));
            builder.setNeutralButton(R.string.csv_replace_action,
                    (dialog, which) -> approve(true));
        }
        importDialog = builder.show();
    }

    private void approve(boolean replace) {
        List<CardCsv.Entry> approved = incoming;
        clearImport();
        if (approved != null && host.canImport()) host.importApproved(approved, replace);
    }

    private void clearImport() {
        generation++;
        if (importRead != null) {
            importRead.cancel();
            importRead = null;
        }
        reading = false;
        incoming = null;
        importUri = null;
        dismissImportDialog();
    }

    private void dismissImportDialog() {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
        }
    }

    private void export(boolean share) {
        List<SavedCard> cards = CardStore.getCards(activity);
        if (cards.isEmpty()) return;
        io.execute(() -> {
            try {
                Uri snapshot = CardExportProvider.create(activity, CardCsv.encode(cards));
                post(() -> {
                    if (share) {
                        Intent send = new Intent(Intent.ACTION_SEND).setType(CardCsv.MIME_TYPE)
                                .putExtra(Intent.EXTRA_STREAM, snapshot)
                                .putExtra(Intent.EXTRA_TITLE, CardExportProvider.FILE_NAME)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        send.setClipData(ClipData.newUri(activity.getContentResolver(),
                                CardExportProvider.FILE_NAME, snapshot));
                        try {
                            activity.startActivity(Intent.createChooser(send,
                                    activity.getText(R.string.csv_share_file)));
                        } catch (ActivityNotFoundException failure) {
                            error(activity.getString(R.string.csv_share_unavailable));
                        }
                    } else {
                        exportUri = snapshot;
                        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType(CardCsv.MIME_TYPE)
                                .putExtra(Intent.EXTRA_TITLE, CardExportProvider.FILE_NAME);
                        try {
                            activity.startActivityForResult(save, SAVE_CSV);
                        } catch (ActivityNotFoundException failure) {
                            exportUri = null;
                            error(activity.getString(R.string.csv_picker_unavailable));
                        }
                    }
                });
            } catch (Exception failure) {
                post(() -> error(activity.getString(R.string.csv_export_failed)));
            }
        });
    }

    private void post(Runnable action) {
        activity.runOnUiThread(() -> {
            if (!disposed && !activity.isFinishing() && !activity.isDestroyed()) action.run();
        });
    }

    private void error(String message) {
        if (disposed || activity.isFinishing() || activity.isDestroyed()) return;
        new AlertDialog.Builder(activity)
                .setTitle(R.string.csv_error_title)
                .setMessage(message)
                .setPositiveButton(R.string.action_close, null)
                .show();
    }
}
