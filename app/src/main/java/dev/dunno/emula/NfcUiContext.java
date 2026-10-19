package dev.dunno.emula;

import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;

/** Publishes a screen's lifecycle without making the worker depend on its lifetime. */
final class NfcUiContext<T> {
    private enum State { CREATED, RESUMED, PAUSED }
    private WeakReference<T> owner = new WeakReference<>(null);
    private State state = State.PAUSED;
    private boolean focused;

    synchronized void attach(T value) {
        owner = new WeakReference<>(value);
        state = State.CREATED;
        focused = false;
        notifyAll();
    }

    synchronized void resume(T value) {
        if (owner.get() != value) return;
        state = State.RESUMED;
        notifyAll();
    }

    synchronized void focus(T value, boolean hasFocus) {
        if (owner.get() != value) return;
        focused = hasFocus;
        notifyAll();
    }

    synchronized void pause(T value) {
        if (owner.get() != value) return;
        state = State.PAUSED;
        focused = false;
        notifyAll();
    }

    synchronized void detach(T value) {
        if (owner.get() != value) return;
        owner.clear();
        state = State.PAUSED;
        focused = false;
        notifyAll();
    }

    synchronized T attached() { return owner.get(); }
    synchronized boolean isResumed() { return owner.get() != null && state == State.RESUMED; }

    /** Wait for initial window focus/recreation, but never wait for a background app. */
    synchronized T acquire(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            T value = owner.get();
            if (value == null || state == State.PAUSED) return null;
            if (state == State.RESUMED && focused) return value;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return null;
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
    }
}
