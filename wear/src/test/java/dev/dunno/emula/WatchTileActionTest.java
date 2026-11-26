package dev.dunno.emula;

import org.junit.Test;
import static org.junit.Assert.*;

public class WatchTileActionTest {
    @Test public void passiveRefreshAndInvalidIdsHaveNoAction() {
        for (String id : new String[]{"", "open", "START:invalid:a", "START:0:", "OTHER:2:a"}) {
            assertNull(WatchTileAction.parse(id));
        }
    }

    @Test public void replayAndStaleCardSelectionCannotStartOrStopAnotherSession() {
        WatchTileAction start = new WatchTileAction(WatchTileAction.Kind.START, 3, "card:a");
        WatchTileAction fromHost = WatchTileAction.parse(start.id());
        assertTrue(fromHost.applies(3, "card:a", null));
        assertFalse(fromHost.applies(3, "card:b", null));
        assertFalse(fromHost.applies(4, "card:a", null));
        WatchNfcSession.Pending active = new WatchNfcSession.Pending(WatchNfcSession.Path.OEM,
                "fw", "", "card:a", true);
        assertFalse(fromHost.applies(3, "card:a", active));
        WatchTileAction stop = new WatchTileAction(WatchTileAction.Kind.STOP, 7, "card:a");
        assertTrue(stop.applies(7, "", active)); // An active, deleted card can still be stopped.
        assertFalse(stop.applies(7, "card:a", null));
        assertFalse(stop.applies(8, "card:a", active));
    }

    @Test public void timerIsOptionalAndOnlyChangesWhileIdle() {
        int seconds = 0;
        for (int expected : new int[]{3, 5, 10, 30, 0}) {
            seconds = WatchTileAction.nextDuration(seconds);
            assertEquals(expected, seconds);
        }
        WatchTileAction timer = new WatchTileAction(WatchTileAction.Kind.TIMER, 1, "a");
        assertFalse(timer.applies(1, "a", new WatchNfcSession.Pending(
                WatchNfcSession.Path.RAW, "fw", "original", "a", false)));
    }

    @Test public void cachedStartAfterDeadlineRequiresTheOriginalSessionToHaveEnded() {
        long activeRevision = 10;
        WatchTileAction cached = new WatchTileAction(WatchTileAction.Kind.START, activeRevision + 1, "a");
        WatchNfcSession.Pending active = new WatchNfcSession.Pending(WatchNfcSession.Path.OEM,
                "fw", "", "a", true, "alarm", 1000);
        assertFalse(cached.applies(activeRevision, "a", active));
        assertFalse(cached.applies(activeRevision + 1, "a", active));
        assertTrue(cached.applies(activeRevision + 1, "a", null));
        assertFalse(cached.applies(activeRevision + 1, "b", null));
        assertFalse(cached.applies(activeRevision + 2, "a", null));
    }
}
