package dev.dunno.emula;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

/** Exposes only immutable CSV export snapshots, with temporary read grants. */
public final class CardExportProvider extends ContentProvider {
    static final String FILE_NAME = "emula.csv";
    private static final String SNAPSHOT_ID = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    private static final long RETENTION_MILLIS = 24 * 60 * 60 * 1000L;

    static Uri create(Context context, String csv) throws IOException {
        File directory = directory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create the export directory");
        }
        File[] oldFiles = directory.listFiles();
        if (oldFiles != null) {
            long cutoff = System.currentTimeMillis() - RETENTION_MILLIS;
            for (File old : oldFiles) {
                if (old.isFile() && old.lastModified() < cutoff) {
                    Files.deleteIfExists(old.toPath());
                }
            }
        }
        String id = UUID.randomUUID().toString();
        File file = new File(directory, id + ".csv");
        Files.write(file.toPath(), csv.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        return new Uri.Builder().scheme("content")
                .authority(context.getPackageName() + ".card-exports")
                .appendPath(id).appendPath(FILE_NAME).build();
    }

    private static File directory(Context context) {
        return new File(context.getCacheDir(), "card-exports");
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        List<String> segments = uri.getPathSegments();
        if (!"content".equals(uri.getScheme())
                || !(getContext().getPackageName() + ".card-exports").equals(uri.getAuthority())) {
            throw new FileNotFoundException("Unknown export");
        }
        String name;
        if (segments.size() == 2 && segments.get(0).matches(SNAPSHOT_ID)
                && FILE_NAME.equals(segments.get(1))) {
            name = segments.get(0) + ".csv";
        } else if (segments.size() == 1
                && segments.get(0).matches("emula-cards-[0-9]{8}-[0-9]{6}-[a-f0-9-]{36}\\.csv")) {
            // Keep already-granted snapshot URIs readable across an app update.
            name = segments.get(0);
        } else {
            throw new FileNotFoundException("Unknown export");
        }
        File file = new File(directory(getContext()), name);
        if (!file.isFile()) throw new FileNotFoundException("Export has expired");
        return file;
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return CardCsv.MIME_TYPE; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        try {
            File file = resolve(uri);
            String[] columns = projection == null
                    ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                    : projection;
            MatrixCursor cursor = new MatrixCursor(columns, 1);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = FILE_NAME;
                if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
            }
            cursor.addRow(values);
            return cursor;
        } catch (FileNotFoundException failure) {
            throw new IllegalArgumentException(failure.getMessage(), failure);
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Exports are read-only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Exports are read-only");
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("Exports are read-only");
    }
    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("Exports are read-only");
    }
}
