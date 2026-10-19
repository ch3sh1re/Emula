package dev.dunno.emula;

/** Durable transaction boundary; no Android dependency, so failure ordering is unit tested. */
final class WatchNfcSession {
    enum Path { OEM, RAW }

    static final class Pending {
        final Path path;
        final String firmware;
        final String snapshot;
        final String cardId;
        final boolean active;

        Pending(Path path, String firmware, String snapshot, String cardId, boolean active) {
            this.path = path;
            this.firmware = firmware;
            this.snapshot = snapshot;
            this.cardId = cardId;
            this.active = active;
        }
    }

    interface Store {
        Pending load() throws Exception;
        void save(Pending pending) throws Exception;
        void clear() throws Exception;
    }

    interface Backend {
        String snapshot() throws Exception;
        void activate(Path path, byte[] uid, String snapshot) throws Exception;
        void restore(Pending pending) throws Exception;
    }

    private final Store store;
    private final Backend backend;
    private final String firmware;

    WatchNfcSession(Store store, Backend backend, String firmware) {
        this.store = store;
        this.backend = backend;
        this.firmware = firmware;
    }

    void activate(String cardId, byte[] uid, Path path, boolean rawConsent) throws Exception {
        if (uid == null || !UidCodec.isSupportedLength(uid.length)) {
            throw new IllegalArgumentException("UID must contain 4, 7, or 10 bytes");
        }
        if (store.load() != null) throw new IllegalStateException("Restore NFC first");
        if (path == Path.RAW && !rawConsent) {
            throw new IllegalStateException("Allow raw on the current firmware first");
        }
        String snapshot = path == Path.RAW ? backend.snapshot() : "";
        if (path == Path.RAW && (snapshot == null || snapshot.isEmpty())) {
            throw new IllegalStateException("Could not save the original NFC configuration");
        }
        Pending pending = new Pending(path, firmware, snapshot, cardId, false);
        store.save(pending); // Must reach durable storage before the first configuration write.
        try {
            backend.activate(path, uid.clone(), snapshot);
            store.save(new Pending(path, firmware, snapshot, cardId, true));
        } catch (Throwable failure) {
            boolean interrupted = Thread.interrupted();
            try {
                backend.restore(pending);
                store.clear();
            } catch (Throwable rollbackFailure) {
                failure.addSuppressed(rollbackFailure); // Keep the pending snapshot for retry.
            } finally {
                if (interrupted || failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
            throw failure instanceof Exception ? (Exception) failure
                    : new IllegalStateException(failure);
        }
    }

    void restore() throws Exception {
        Pending pending = store.load();
        if (pending == null) return; // Fresh installations never reset NFC on startup.
        if (!firmware.equals(pending.firmware)) {
            throw new IllegalStateException("Firmware changed; previous NFC snapshot retained, writes blocked");
        }
        backend.restore(pending);
        store.clear(); // A failed restore must remain retryable, including after process death.
    }
}
