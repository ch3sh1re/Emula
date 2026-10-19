package dev.dunno.emula;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class WatchNfcSessionTest {
    private static final byte[] UID = {1, 2, 3, 4};

    private static final class Fixture implements WatchNfcSession.Store, WatchNfcSession.Backend {
        final List<String> events = new ArrayList<>();
        WatchNfcSession.Pending pending;
        boolean failSave;
        boolean failActivate;
        boolean failRestore;
        String snapshot = "30=06;31=0C;32=20;33=;58=40;85=01";

        WatchNfcSession session(String firmware) {
            return new WatchNfcSession(this, this, firmware);
        }

        @Override public WatchNfcSession.Pending load() { return pending; }
        @Override public void save(WatchNfcSession.Pending value) throws Exception {
            events.add("save:" + value.active);
            if (failSave) throw new Exception("disk unavailable");
            pending = value;
        }
        @Override public void clear() { events.add("clear"); pending = null; }
        @Override public String snapshot() { events.add("snapshot"); return snapshot; }
        @Override public void activate(WatchNfcSession.Path path, byte[] uid, String original)
                throws Exception {
            events.add("activate:" + path);
            assertNotNull(pending);
            assertFalse(pending.active);
            if (path == WatchNfcSession.Path.RAW) assertEquals(snapshot, original);
            if (failActivate) throw new Exception("controller failure");
        }
        @Override public void restore(WatchNfcSession.Pending original) throws Exception {
            events.add("restore:" + original.path);
            if (failRestore) throw new Exception("rollback failure");
        }
    }

    @Test public void rawSnapshotIsDurableBeforeWriteAndRetainedUntilVerifiedRestore() throws Exception {
        Fixture f = new Fixture();
        WatchNfcSession session = f.session("fw1");
        session.activate("card1", UID, WatchNfcSession.Path.RAW, true);
        assertEquals(List.of("snapshot", "save:false", "activate:RAW", "save:true"), f.events);
        assertTrue(f.pending.active);
        assertEquals("fw1", f.pending.firmware);
        session.restore();
        assertEquals(List.of("snapshot", "save:false", "activate:RAW", "save:true", "restore:RAW", "clear"), f.events);
        assertNull(f.pending);
    }

    @Test public void rawRequiresConsentBeforeAnyProbeOrWrite() {
        Fixture f = new Fixture();
        assertThrows(IllegalStateException.class,
                () -> f.session("fw1").activate("c", UID, WatchNfcSession.Path.RAW, false));
        assertTrue(f.events.isEmpty());
    }

    @Test public void missingSnapshotAndFailedPersistenceNeverReachActivation() {
        Fixture f = new Fixture();
        f.snapshot = "";
        assertThrows(IllegalStateException.class,
                () -> f.session("fw1").activate("c", UID, WatchNfcSession.Path.RAW, true));
        assertEquals(List.of("snapshot"), f.events);
        f.events.clear();
        f.snapshot = "33=01";
        f.failSave = true;
        assertThrows(Exception.class,
                () -> f.session("fw1").activate("c", UID, WatchNfcSession.Path.RAW, true));
        assertEquals(List.of("snapshot", "save:false"), f.events);
    }

    @Test public void activationFailureRollsBackBeforeClearingSnapshot() {
        Fixture f = new Fixture();
        f.failActivate = true;
        Exception failure = assertThrows(Exception.class,
                () -> f.session("fw1").activate("c", UID, WatchNfcSession.Path.RAW, true));
        assertEquals("controller failure", failure.getMessage());
        assertEquals(List.of("snapshot", "save:false", "activate:RAW", "restore:RAW", "clear"), f.events);
        assertNull(f.pending);
    }

    @Test public void rollbackFailurePreservesBothFailuresAndRecoveryAcrossNewSession() throws Exception {
        Fixture f = new Fixture();
        f.failActivate = true;
        f.failRestore = true;
        Exception failure = assertThrows(Exception.class,
                () -> f.session("fw1").activate("c", UID, WatchNfcSession.Path.RAW, true));
        assertEquals("controller failure", failure.getMessage());
        assertEquals("rollback failure", failure.getSuppressed()[0].getMessage());
        assertNotNull(f.pending);
        assertFalse(f.pending.active);
        f.failRestore = false;
        f.session("fw1").restore();
        assertNull(f.pending);
    }

    @Test public void firmwareChangeBlocksRecoveryWriteAndKeepsOriginalSnapshot() throws Exception {
        Fixture f = new Fixture();
        f.session("fw1").activate("c", UID, WatchNfcSession.Path.RAW, true);
        f.events.clear();
        assertThrows(IllegalStateException.class, () -> f.session("fw2").restore());
        assertTrue(f.events.isEmpty());
        assertEquals("fw1", f.pending.firmware);
    }

    @Test public void newInstallDoesNotResetNfcAndOemDoesNotRequireRawApi() throws Exception {
        Fixture f = new Fixture();
        f.session("fw1").restore();
        assertTrue(f.events.isEmpty());
        f.session("fw1").activate("c", UID, WatchNfcSession.Path.OEM, false);
        assertEquals(List.of("save:false", "activate:OEM", "save:true"), f.events);
    }

    @Test public void pendingSessionMustBeRestoredBeforeSwitchingCards() throws Exception {
        Fixture f = new Fixture();
        f.session("fw1").activate("c1", UID, WatchNfcSession.Path.OEM, false);
        f.events.clear();
        assertThrows(IllegalStateException.class,
                () -> f.session("fw1").activate("c2", UID, WatchNfcSession.Path.RAW, true));
        assertTrue(f.events.isEmpty());
        assertEquals("c1", f.pending.cardId);
    }

    @Test public void watchDiscoveryOverrideRequiresFirmwareOptInAndLeavesPhonesUnchanged() {
        assertTrue(RawNfcDeviceCatalog.prefersWatchDiscoveryControl(37, true, true));
        assertFalse(RawNfcDeviceCatalog.prefersWatchDiscoveryControl(37, true, false));
        assertFalse(RawNfcDeviceCatalog.prefersWatchDiscoveryControl(37, false, true));
        assertFalse(RawNfcDeviceCatalog.prefersWatchDiscoveryControl(34, true, true));
        assertFalse(RawNfcDeviceCatalog.shouldUseDiscoveryTechnologyFallback(37, "samsung", "phone"));
    }
}
