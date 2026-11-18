package dev.dunno.emula;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Bounded hashes of readable system files; partial reads never become fingerprints. */
final class NfcFileEvidence {
    private final long perFileLimit;
    private long remainingBytes;
    private final long deadline;
    private final LongSupplier clock;
    private final Set<String> visited = new HashSet<>();

    NfcFileEvidence() {
        this(64L * 1024 * 1024, 128L * 1024 * 1024,
                TimeUnit.SECONDS.toNanos(5), System::nanoTime);
    }

    NfcFileEvidence(long perFileLimit, long totalLimit, long duration, LongSupplier clock) {
        this.perFileLimit = perFileLimit;
        this.remainingBytes = totalLimit;
        this.clock = clock;
        this.deadline = clock.getAsLong() + duration;
    }

    String describe(File file) { return inspect(file).description; }

    Result inspect(File file) {
        try {
            if (visited.size() >= 12) return new Result("not hashed (file-count limit)");
            String path = file.getCanonicalPath();
            if (!visited.add(path)) return new Result("already listed (same file)");
            if (!file.isFile() || !file.canRead()) return new Result("unreadable or absent");
            long size = file.length();
            long modified = file.lastModified();
            if (size > perFileLimit || size > remainingBytes) return new Result("not hashed (byte limit; " + size + " bytes)");
            if (expired()) return new Result("not hashed (time limit)");
            String hash;
            try (InputStream input = new FileInputStream(file)) {
                hash = digest(input, size);
            }
            if (file.length() != size || file.lastModified() != modified) {
                return new Result("not hashed (file changed during collection)");
            }
            return new Result(hash, size);
        } catch (IOException | SecurityException error) {
            return new Result("unavailable (" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")");
        }
    }

    String digest(InputStream input, long expectedSize) throws IOException {
        if (expectedSize < 0 || expectedSize > perFileLimit || expectedSize > remainingBytes) {
            throw new IOException("byte limit");
        }
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        byte[] buffer = new byte[8192];
        long read = 0;
        while (read < expectedSize) {
            if (expired() || Thread.currentThread().isInterrupted()) throw new IOException("collection interrupted or time limit");
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, expectedSize - read));
            if (count <= 0) throw new IOException("file ended or changed during collection");
            read += count;
            remainingBytes -= count;
            digest.update(buffer, 0, count);
        }
        if (expired() || Thread.currentThread().isInterrupted()) throw new IOException("collection interrupted or time limit");
        if (input.read() != -1) throw new IOException("file grew during collection");
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b & 0xff) >>> 4, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        return hex.toString();
    }

    private boolean expired() { return clock.getAsLong() - deadline >= 0; }

    static final class Result {
        final String sha256;
        final String description;
        Result(String failure) { sha256 = null; description = failure; }
        Result(String hash, long size) {
            sha256 = hash;
            description = "SHA-256 " + hash + " (" + size + " bytes)";
        }
    }
}
