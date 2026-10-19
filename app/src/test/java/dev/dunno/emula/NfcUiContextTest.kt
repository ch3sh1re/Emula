package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NfcUiContextTest {
    @Test fun backgroundWithoutAScreenHasNoActivity() {
        val host = NfcUiContext<Any>()
        assertNull(host.acquire(2_000))
        assertFalse(host.isResumed)
    }

    @Test fun startupWaitsForResumeAndFocusBeforePublishingTheScreen() {
        val host = NfcUiContext<Any>()
        val activity = Any()
        host.attach(activity)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val started = CountDownLatch(1)
            val result = worker.submit<Any?> { started.countDown(); host.acquire(5_000) }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            host.resume(activity)
            assertNull(host.acquire(0)) // Resume alone does not permit discovery control.
            host.focus(activity, true)
            assertSame(activity, result.get(1, TimeUnit.SECONDS))
        } finally { worker.shutdownNow() }
    }

    @Test fun pausedActivityIsNotBorrowedByABackgroundOperation() {
        val host = NfcUiContext<Any>()
        val activity = Any()
        host.attach(activity)
        host.resume(activity)
        host.focus(activity, true)
        assertSame(activity, host.acquire(0))
        host.pause(activity)
        host.focus(activity, true) // A stale focus event must not undo onPause.
        assertNull(host.acquire(2_000))
        assertFalse(host.isResumed)
    }

    @Test fun oldActivityCallbacksCannotHideItsReplacement() {
        val host = NfcUiContext<Any>()
        val old = Any()
        val replacement = Any()
        host.attach(old)
        host.resume(old)
        host.focus(old, true)
        host.attach(replacement)
        host.resume(replacement)
        host.focus(replacement, true)
        host.pause(old)
        host.focus(old, false)
        host.detach(old)
        assertSame(replacement, host.acquire(0))
    }

    @Test fun aBorrowedContextStaysBoundToItsOriginalResumeTarget() {
        val host = NfcUiContext<Any>()
        val original = Any()
        host.attach(original)
        host.resume(original)
        host.focus(original, true)
        val borrowed = host.acquire(0)
        val replacement = Any()
        host.attach(replacement)
        host.resume(replacement)
        host.focus(replacement, true)
        assertSame(original, borrowed)
        assertSame(replacement, host.acquire(0))
    }

    @Test fun detachingAnUnfocusedScreenReleasesTheWaitingWorker() {
        val host = NfcUiContext<Any>()
        val activity = Any()
        host.attach(activity)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val entered = CountDownLatch(1)
            val result = worker.submit<Any?> { entered.countDown(); host.acquire(5_000) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            host.detach(activity)
            assertNull(result.get(1, TimeUnit.SECONDS))
            assertNull(host.attached())
        } finally { worker.shutdownNow() }
    }

    @Test fun focusLossWaitsOnlyForTheGivenDeadline() {
        val host = NfcUiContext<Any>()
        val activity = Any()
        host.attach(activity)
        host.resume(activity)
        assertNull(host.acquire(10))
        assertSame(activity, host.attached()) // Metadata may still describe foreground capability.
        host.focus(activity, true)
        assertSame(activity, host.acquire(0))
    }

    @Test fun interruptionDoesNotDetachTheScreenOrConsumeANfcRequest() {
        val host = NfcUiContext<Any>()
        val activity = Any()
        host.attach(activity)
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException::class.java) { host.acquire(1_000) }
            assertSame(activity, host.attached())
        } finally { Thread.interrupted() }
    }
}
