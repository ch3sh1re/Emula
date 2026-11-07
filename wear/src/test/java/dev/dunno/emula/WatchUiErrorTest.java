package dev.dunno.emula;

import org.junit.Test;
import static org.junit.Assert.*;

public class WatchUiErrorTest {
    @Test public void missingPermissionIsActionableEvenWhenRecoveryIsPending() {
        Throwable failure = new WatchUiError.Condition(WatchUiError.PERMISSION, "grant required");
        assertEquals(WatchUiError.PERMISSION, WatchUiError.from(failure, true, false));
    }

    @Test public void disabledNfcMustBeEnabledBeforeRestorationCanRun() {
        Throwable failure = new WatchUiError.Condition(WatchUiError.NFC_OFF, "NFC disabled");
        assertEquals(WatchUiError.NFC_OFF, WatchUiError.from(failure, true, true));
    }

    @Test public void reflectiveSecurityFailuresOfferPermissionSetup() {
        Throwable failure = new java.lang.reflect.InvocationTargetException(new SecurityException("denied"));
        assertEquals(WatchUiError.PERMISSION, WatchUiError.from(failure, false, false));
    }

    @Test public void securityFailureAfterGrantNeedsDiagnosticsRatherThanAnotherGrant() {
        Throwable failure = new java.lang.reflect.InvocationTargetException(new SecurityException("system denied"));
        assertEquals(WatchUiError.OTHER, WatchUiError.from(failure, false, true));
        assertEquals(WatchUiError.RECOVERY, WatchUiError.from(failure, true, true));
    }

    @Test public void rollbackFailureKeepsBothErrorsForDiagnostics() {
        Exception failure = new Exception("controller rejected UID");
        Exception rollback = new Exception("restore failed");
        failure.addSuppressed(rollback);
        assertEquals(WatchUiError.RECOVERY, WatchUiError.from(failure, true, true));
        assertEquals("controller rejected UID", failure.getMessage());
        assertSame(rollback, failure.getSuppressed()[0]);
    }

    @Test public void controllerFailureWithoutPendingRecoveryOffersDiagnostics() {
        assertEquals(WatchUiError.OTHER, WatchUiError.from(new Exception("controller error"), false, true));
    }
}
