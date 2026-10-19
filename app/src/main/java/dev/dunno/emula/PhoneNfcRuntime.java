package dev.dunno.emula;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** One process-wide queue for NFC, diagnostics, and card mutations; never owned by a screen. */
final class PhoneNfcRuntime {
    interface Work<T> { T run(PhoneNfcSession session) throws Exception; }
    interface Completion<T> { void complete(T value, Throwable failure); }

    private static PhoneNfcRuntime instance;
    final Context context;
    final PhoneNfcSession session;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayDeque<Job<?>> queue = new ArrayDeque<>();
    private final Set<Runnable> listeners = new LinkedHashSet<>();
    private BackgroundNfcService service;
    private boolean startingService;
    private boolean running;
    private boolean readerBusy;
    private Object uiOwner;
    private BooleanSupplier uiBlocked;

    static synchronized PhoneNfcRuntime get(Context context) {
        if (instance == null) instance = new PhoneNfcRuntime(context, new PhoneNfcBackend(context));
        return instance;
    }

    PhoneNfcRuntime(Context context, PhoneNfcSession.Backend backend) {
        this.context = context.getApplicationContext();
        session = new PhoneNfcSession(backend);
    }

    static synchronized PhoneNfcRuntime installForTest(Context context, PhoneNfcSession.Backend backend) {
        PhoneNfcRuntime previous = instance;
        if (previous != null && (previous.isBusy() || previous.activeOrRecovery())) {
            throw new IllegalStateException("A phone NFC session is running");
        }
        instance = new PhoneNfcRuntime(context, backend);
        return previous;
    }

    static synchronized void restoreAfterTest(PhoneNfcRuntime previous) {
        if (instance != null) instance.worker.shutdown();
        instance = previous;
    }

    boolean isBusy() { return running || !queue.isEmpty(); }
    static synchronized void adapterOff(boolean ownedRestart) {
        if (instance == null) return;
        PhoneNfcRuntime runtime = instance;
        runtime.enqueue(new Job<>(false, false, true, state -> {
            // The NXP setter itself toggles NFC. Evaluate only after its queued transaction.
            android.nfc.NfcAdapter adapter = android.nfc.NfcAdapter.getDefaultAdapter(runtime.context);
            state.adapterOff(ownedRestart, adapter != null && adapter.isEnabled());
            return null;
        }, null));
    }
    boolean activeOrRecovery() { return session.backend.activeId() != null || session.backend.recoveryPending(); }
    void addListener(Runnable listener) { listeners.add(listener); }
    void removeListener(Runnable listener) { listeners.remove(listener); }
    void setReaderBusy(boolean busy) { readerBusy = busy; }
    void attachUi(Object owner, BooleanSupplier blocked) { uiOwner = owner; uiBlocked = blocked; }
    void detachUi(Object owner) { if (uiOwner == owner) { uiOwner = null; uiBlocked = null; readerBusy = false; } }

    PhoneNfcSession.Reason quickProblem(String id) {
        if (readerBusy || (uiBlocked != null && uiBlocked.getAsBoolean())) return PhoneNfcSession.Reason.BUSY;
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (keyguard != null && keyguard.isKeyguardLocked()) return PhoneNfcSession.Reason.LOCKED;
        try {
            SavedCard card = session.selected(id);
            return session.backend.preflight(card.id.equals(session.backend.activeId()) ? null : card);
        } catch (PhoneNfcSession.Failure failure) {
            return failure.reason;
        }
    }

    <T> void submit(boolean hardware, Work<T> work, Completion<T> completion) {
        enqueue(new Job<>(hardware, false, work, completion));
    }

    void toggle(String id, Completion<Void> completion) {
        enqueue(new Job<>(true, true, state -> {
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            if (keyguard != null && keyguard.isKeyguardLocked()) {
                throw new PhoneNfcSession.Failure(PhoneNfcSession.Reason.LOCKED);
            }
            state.toggle(id);
            return null;
        }, completion));
    }

    private void enqueue(Job<?> job) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> enqueue(job));
            return;
        }
        queue.add(job);
        pump();
    }

    void serviceReady(BackgroundNfcService service) {
        this.service = service;
        startingService = false;
        pump();
    }

    void serviceGone(BackgroundNfcService service) {
        if (this.service == service) this.service = null;
    }

    void serviceFailed(Throwable failure) {
        startingService = false;
        while (!queue.isEmpty()) queue.remove().finish(null, failure);
        notifyState();
    }

    boolean serviceRunning() { return service != null; }

    void refresh() { notifyState(); }

    private void pump() {
        if (running) return;
        Job<?> job = queue.peek();
        if (job == null) { notifyState(); return; }
        if ((job.hardware && readerBusy) || (job.quick && uiBlocked != null && uiBlocked.getAsBoolean())) {
            queue.remove();
            job.finish(null, new PhoneNfcSession.Failure(PhoneNfcSession.Reason.BUSY));
            pump();
            return;
        }
        if (!job.passive && (job.hardware || activeOrRecovery()) && service == null) {
            if (!startingService) {
                startingService = true;
                try {
                    context.startForegroundService(BackgroundNfcService.wakeIntent(context));
                } catch (RuntimeException failure) {
                    startingService = false;
                    queue.remove();
                    job.finish(null, failure);
                    pump();
                }
            }
            notifyState();
            return;
        }
        queue.remove();
        running = true;
        notifyState();
        worker.execute(() -> {
            long started = android.os.SystemClock.elapsedRealtime();
            Object value = null;
            Throwable failure = null;
            try { value = job.work.run(session); }
            catch (Throwable error) { failure = error; }
            Object result = value;
            Throwable error = failure;
            long completed = android.os.SystemClock.elapsedRealtime();
            main.post(() -> {
                AppLog.info("NFC job quick=" + job.quick + " wait_ms=" + (started - job.queuedAt)
                        + " work_ms=" + (completed - started) + " delivery_ms="
                        + (android.os.SystemClock.elapsedRealtime() - completed)
                        + " success=" + (error == null));
                // Storage commits have completed on the worker even if their Activity is gone.
                // Run completion before the next job, so reader mode can acquire its UI lease.
                running = false;
                try { job.finish(result, error); }
                finally { pump(); }
            });
        });
    }

    private void notifyState() {
        QuickAccess.refresh(context);
        for (Runnable listener : new LinkedHashSet<>(listeners)) listener.run();
        if (service != null) service.renderState();
    }

    private static final class Job<T> {
        final boolean hardware;
        final boolean quick;
        final boolean passive;
        final Work<T> work;
        final Completion<T> completion;
        final long queuedAt = android.os.SystemClock.elapsedRealtime();
        Job(boolean hardware, boolean quick, Work<T> work, Completion<T> completion) {
            this(hardware, quick, false, work, completion);
        }
        Job(boolean hardware, boolean quick, boolean passive, Work<T> work, Completion<T> completion) {
            this.hardware = hardware; this.quick = quick; this.passive = passive;
            this.work = work; this.completion = completion;
        }
        @SuppressWarnings("unchecked")
        void finish(Object value, Throwable failure) { if (completion != null) completion.complete((T) value, failure); }
    }

    static String message(Context context, Throwable error) {
        if (error instanceof PhoneNfcSession.Failure) {
            PhoneNfcSession.Failure failure = (PhoneNfcSession.Failure) error;
            int resource;
            switch (failure.reason) {
                case NO_NFC: case UNSUPPORTED: resource = R.string.no_compatible_nfc_interface; break;
                case DISABLED: resource = R.string.enable_nfc_first; break;
                case PERMISSION: resource = R.string.permission_required; break;
                case CONSENT: resource = R.string.background_approve_in_app; break;
                case MISSING: resource = R.string.quick_card_removed; break;
                case CHOOSE: resource = R.string.quick_choose_card; break;
                case BUSY: resource = R.string.background_busy; break;
                case LOCKED: resource = R.string.background_unlock; break;
                default:
                    return context.getString(failure.raw ? R.string.raw_nfc_error : R.string.nfc_error,
                            failure.getMessage() == null ? failure.toString() : failure.getMessage());
            }
            return context.getString(resource);
        }
        return context.getString(R.string.nfc_error,
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
    }
}
