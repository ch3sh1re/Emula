package dev.dunno.emula;

import java.util.List;

/** Worker-owned phone session. Resolve IDs and toggle decisions only inside the shared queue. */
final class PhoneNfcSession {
    enum Reason { NO_NFC, DISABLED, PERMISSION, UNSUPPORTED, CONSENT, MISSING, CHOOSE, BUSY, LOCKED, FAILED }

    static final class Failure extends Exception {
        final Reason reason;
        final boolean raw;
        Failure(Reason reason) { this(reason, false, null); }
        Failure(Reason reason, boolean raw, Throwable cause) {
            super(cause == null ? reason.name() : cause.getMessage(), cause);
            this.reason = reason;
            this.raw = raw;
        }
    }

    interface Backend {
        List<SavedCard> cards();
        String activeId();
        String lastCardId();
        void remember(SavedCard card);
        void forgetActive();
        boolean initialize() throws Exception;
        void change(SavedCard card, boolean confirmed) throws Exception;
        Reason preflight(SavedCard card);
        boolean recoveryPending();
    }

    final Backend backend;
    private boolean initialized;

    PhoneNfcSession(Backend backend) { this.backend = backend; }

    void initialize() throws Exception {
        if (!needsInitialization()) return;
        initialized = false;
        initialized = backend.initialize();
    }

    private boolean needsInitialization() {
        return !initialized || (backend.activeId() == null && backend.recoveryPending());
    }

    void adapterOff() {
        initialized = false;
        backend.forgetActive(); // Recovery snapshots remain intact until an explicit operation.
    }

    void adapterOff(boolean ownedRestart, boolean nfcEnabled) {
        if (ownedRestart && nfcEnabled) return;
        adapterOff();
    }

    SavedCard selected(String id) throws Failure {
        List<SavedCard> cards = backend.cards();
        SavedCard card = id == null ? QuickCardPolicy.tileCard(cards, backend.activeId(), backend.lastCardId())
                : QuickCardPolicy.find(cards, id);
        if (card == null) throw new Failure(id == null ? Reason.CHOOSE : Reason.MISSING);
        return card;
    }

    void toggle(String id) throws Exception {
        selected(id); // A stale launcher item must not trigger even startup controller writes.
        initialize();
        SavedCard card = selected(id);
        backend.remember(card);
        backend.change(QuickCardPolicy.deactivate(card, backend.activeId()) ? null : card, false);
    }

    void change(String id, boolean confirmed) throws Exception {
        SavedCard card = id == null ? null : selected(id);
        boolean restoring = needsInitialization();
        initialize();
        if (id == null && restoring && initialized
                && backend.activeId() == null && !backend.recoveryPending()) return;
        if (card != null) {
            card = selected(id);
            backend.remember(card);
        }
        backend.change(card, confirmed);
    }

    void beforeDelete(String id) throws Exception {
        if (id.equals(backend.activeId())) backend.change(null, false);
    }

    void beforeSave(String id, byte[] uid) throws Exception {
        if (id == null) return;
        SavedCard current = selected(id);
        if (id.equals(backend.activeId()) && !current.hasSameUid(uid)) backend.change(null, false);
    }

    void beforeReplace() throws Exception {
        if (backend.activeId() != null) backend.change(null, false);
    }
}
