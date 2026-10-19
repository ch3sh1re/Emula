package dev.dunno.emula;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class CardReadCancellationTest {
    @Test
    public void cancellingBlockedReadClosesOffCallerThreadAndReleasesWorker() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService cleanup = Executors.newSingleThreadExecutor();
        SlowInput input = new SlowInput();
        CountDownLatch providerCancelled = new CountDownLatch(1);
        CardReadCancellation request = new CardReadCancellation(cleanup, providerCancelled::countDown);
        try {
            Future<?> task = worker.submit(() -> {
                try (InputStream stream = input) {
                    if (request.attach(stream)) CardCsv.read(stream);
                    fail("Cancelled read must not produce cards");
                } catch (IOException expected) {
                    // This input deliberately ignores interrupts and only unblocks on close.
                } finally {
                    request.finished();
                }
            });
            request.setTask(task);
            assertTrue(input.reading.await(5, TimeUnit.SECONDS));
            request.cancel();
            assertTrue(task.isCancelled());
            assertTrue(providerCancelled.await(5, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), input.firstCloseThread);
            assertEquals("Next import", worker.submit(() -> CardCsv.read(new ByteArrayInputStream(
                    "name,uid\nNext import,01020304\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .get(0).name).get(5, TimeUnit.SECONDS));
        } finally {
            input.close();
            worker.shutdownNow();
            cleanup.shutdownNow();
        }
    }

    @Test
    public void cancellingDuringOpenNotifiesProviderAndClosesLateStream() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService cleanup = Executors.newSingleThreadExecutor();
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch releaseOpen = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicBoolean attached = new AtomicBoolean(true);
        CardReadCancellation request = new CardReadCancellation(cleanup, releaseOpen::countDown);
        try {
            request.setTask(worker.submit(() -> {
                opening.countDown();
                awaitIgnoringInterrupts(releaseOpen);
                try (InputStream input = new ByteArrayInputStream(new byte[0]) {
                    @Override public void close() { closed.countDown(); }
                }) {
                    attached.set(request.attach(input));
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                } finally {
                    request.finished();
                }
            }));
            assertTrue(opening.await(5, TimeUnit.SECONDS));
            request.cancel();
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertFalse(attached.get());
        } finally {
            releaseOpen.countDown();
            worker.shutdownNow();
            cleanup.shutdownNow();
        }
    }

    @Test
    public void cancellationBeforeTaskRegistrationPreventsQueuedReadAndIsIdempotent() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger cancelled = new AtomicInteger();
        CardReadCancellation request = new CardReadCancellation(Runnable::run, cancelled::incrementAndGet);
        try {
            worker.submit(() -> awaitIgnoringInterrupts(release));
            Future<?> queued = worker.submit(() -> fail("Cancelled queued task must not open a file"));
            request.cancel();
            request.setTask(queued);
            request.cancel();
            assertTrue(queued.isCancelled());
            assertEquals(1, cancelled.get());
            release.countDown();
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void closingFailureStillNotifiesProvider() {
        AtomicInteger cancelled = new AtomicInteger();
        CardReadCancellation request = new CardReadCancellation(Runnable::run, cancelled::incrementAndGet);
        assertTrue(request.attach(() -> { throw new IOException("Provider closed unexpectedly"); }));
        request.cancel();
        assertEquals(1, cancelled.get());
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static final class SlowInput extends InputStream {
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        volatile Thread firstCloseThread;

        @Override public int read() throws IOException {
            reading.countDown();
            awaitIgnoringInterrupts(closed);
            throw new IOException("Closed");
        }

        @Override public synchronized void close() {
            if (firstCloseThread == null) firstCloseThread = Thread.currentThread();
            closed.countDown();
        }
    }
}
