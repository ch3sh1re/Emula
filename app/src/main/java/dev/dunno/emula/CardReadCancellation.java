package dev.dunno.emula;

import java.io.Closeable;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

/** Coordinates cancellation before, during and after a document has been opened. */
final class CardReadCancellation {
    private final Executor cleanup;
    private final Runnable cancelOpen;
    private boolean cancelled;
    private Future<?> task;
    private Closeable input;

    CardReadCancellation(Executor cleanup, Runnable cancelOpen) {
        this.cleanup = cleanup;
        this.cancelOpen = cancelOpen;
    }

    synchronized void setTask(Future<?> task) {
        if (cancelled) task.cancel(true);
        else this.task = task;
    }

    /** The caller owns the stream and must close it even if attachment is refused. */
    synchronized boolean attach(Closeable input) {
        if (cancelled) return false;
        this.input = input;
        return true;
    }

    synchronized void finished() {
        input = null;
        task = null;
    }

    void cancel() {
        Closeable toClose;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            if (task != null) task.cancel(true);
            task = null;
            toClose = input;
            input = null;
        }
        // A provider's close/cancel implementation may block; never run it on the UI thread.
        cleanup.execute(() -> {
            try {
                if (toClose != null) toClose.close();
            } catch (Exception ignored) {
                // Still notify a provider whose open operation has not returned yet.
            } finally {
                try {
                    cancelOpen.run();
                } catch (RuntimeException ignored) {
                    // Cancellation is best effort; stale results are also rejected by the UI.
                }
            }
        });
    }
}
