package dev.dunno.emula;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Real Android service/router/notification lifecycle, with a fake controller that never touches NFC. */
final class BackgroundNfcChecks {
    private static final class Backend implements PhoneNfcSession.Backend {
        final List<SavedCard> cards;
        volatile String active, last;
        volatile boolean pending, fail;
        volatile int restores, writes;
        volatile CountDownLatch changeEntered, allowChange;
        Backend(List<SavedCard> cards) { this.cards = cards; }
        public List<SavedCard> cards() { return cards; }
        public String activeId() { return active; }
        public String lastCardId() { return last; }
        public boolean recoveryPending() { return pending; }
        public PhoneNfcSession.Reason preflight(SavedCard card) { return null; }
        public void remember(SavedCard card) { last = card.id; }
        public void forgetActive() { active = null; CardSession.clearActiveCard(); }
        public boolean initialize() { restores++; pending = false; forgetActive(); return true; }
        public void change(SavedCard card, boolean confirmed) throws Exception {
            if (changeEntered != null) {
                changeEntered.countDown();
                if (!allowChange.await(10, TimeUnit.SECONDS)) throw new AssertionError("Change gate timed out");
            }
            writes++;
            if (fail) { pending = true; throw new IllegalStateException("Simulated restoration failure"); }
            active = card == null ? null : card.id;
            pending = false;
            CardSession.setActiveCardId(active);
        }
    }

    static void lifecycle(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        Application app = (Application) context.getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences("office_cards", Context.MODE_PRIVATE);
        String originalCards = prefs.getString("cards", null);
        SavedCard first = new SavedCard("background-test-a", "Background A", new byte[] {1, 2, 3, 4});
        SavedCard second = new SavedCard("background-test-b", "Background B", new byte[] {4, 3, 2, 1});
        Backend backend = new Backend(List.of(first, second));
        AtomicReference<PhoneNfcRuntime> previous = new AtomicReference<>();
        AtomicReference<PhoneNfcRuntime> installed = new AtomicReference<>();
        AtomicInteger mainOpened = new AtomicInteger(), routerOpened = new AtomicInteger();
        Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity activity, Bundle state) {
                if (activity instanceof MainActivity) mainOpened.incrementAndGet();
                if (activity instanceof CardActionActivity) routerOpened.incrementAndGet();
            }
            public void onActivityStarted(Activity a) { }
            public void onActivityResumed(Activity a) { }
            public void onActivityPaused(Activity a) { }
            public void onActivityStopped(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle state) { }
            public void onActivityDestroyed(Activity a) { }
        };
        test.getUiAutomation().adoptShellPermissionIdentity();
        try {
            test.runOnMainSync(() -> {
                previous.set(PhoneNfcRuntime.installForTest(context, backend));
                installed.set(PhoneNfcRuntime.get(context));
                CardStore.replaceAll(context, backend.cards);
                app.registerActivityLifecycleCallbacks(callbacks);
            });
            PhoneNfcRuntime runtime = installed.get();
            context.startActivity(QuickAccess.launcherIntent(context, first.id));
            await(test, () -> first.id.equals(backend.active) && !runtime.isBusy(), "Router activation");
            check(routerOpened.get() == 1 && mainOpened.get() == 0, "Activation must not open MainActivity");
            check(notification(context, 201) != null, "Active session must have a foreground notification");
            job(test, runtime, false, state -> { state.initialize(); return null; }, false);
            check(backend.restores == 1 && first.id.equals(backend.active), "Warm UI startup must retain activation");
            Activity main = test.startActivitySync(new android.content.Intent(context, MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            await(test, () -> !runtime.isBusy(), "Opening MainActivity during a background session");
            check(backend.restores == 1 && first.id.equals(backend.active),
                    "Opening the real MainActivity must retain background activation");
            AtomicReference<Activity> pollingActivity = new AtomicReference<>();
            job(test, runtime, false, state -> {
                pollingActivity.set(runtime.pollingUi.acquire(2_000));
                return null;
            }, false);
            check(pollingActivity.get() == main,
                    "The application-owned worker must acquire the resumed Activity for discovery control");
            int beforeSearch = backend.writes;
            test.runOnMainSync(() -> {
                View root = main.getWindow().getDecorView();
                find(root, v -> context.getString(R.string.search_cards).equals(
                        String.valueOf(v.getContentDescription()))).performClick();
                EditText search = (EditText) find(root, v -> v instanceof EditText);
                search.setText("Background B");
                check(search.hasFocus(), "Filtering must retain the input connection focus");
                check(find(root, v -> v instanceof TextView && ((TextView) v).getText().toString()
                                .equals(context.getString(R.string.search_active_card, first.name, Hex.bytes(first.uid)))) != null,
                        "An active card excluded by the query must remain identifiable");
                View stop = find(root, v -> v instanceof TextView && ((TextView) v).getText().toString()
                        .equals(context.getString(R.string.background_stop)));
                check(stop != null && stop.isEnabled(), "A filtered-out active card must remain stoppable");
                check(first.id.equals(backend.active) && backend.writes == beforeSearch,
                        "Typing a query must not change controller state");
                stop.performClick();
            });
            await(test, () -> backend.active == null && !runtime.isBusy(), "Stop a filtered-out active card");
            check(backend.writes == beforeSearch + 1, "Stop must issue exactly one fake restoration");
            job(test, runtime, true, state -> { state.toggle(first.id); return null; }, false);
            await(test, () -> first.id.equals(backend.active) && !runtime.isBusy(), "Restore fake active session after search check");
            test.runOnMainSync(main::finish);
            await(test, () -> runtime.pollingUi.attached() == null,
                    "Destroyed Activity must detach from polling context");
            check(runtime.pollingUi.acquire(0) == null,
                    "Background work must not reuse a destroyed Activity");
            await(test, main::isDestroyed, "Closing MainActivity");

            StatusBarNotification activeNotification = notification(context, 201);
            check(activeNotification.getNotification().actions.length == 1, "Notification must offer stop");
            activeNotification.getNotification().actions[0].actionIntent.send();
            await(test, () -> backend.active == null && !runtime.isBusy() && !runtime.serviceRunning(),
                    "Notification stop and idle shutdown");

            CountDownLatch both = new CountDownLatch(2);
            AtomicReference<Throwable> rapidFailure = new AtomicReference<>();
            test.runOnMainSync(() -> {
                PhoneNfcRuntime.Completion<Void> done = (v, error) -> {
                    if (error != null) rapidFailure.set(error);
                    both.countDown();
                };
                runtime.toggle(first.id, done);
                runtime.toggle(first.id, done);
            });
            check(both.await(10, TimeUnit.SECONDS), "Queued toggles must complete");
            check(rapidFailure.get() == null && backend.active == null && backend.restores == 1,
                    "Rapid toggles must execute sequentially against current state");
            await(test, () -> !runtime.serviceRunning(), "Idle service shutdown");

            // Hold the first fake controller operation while all eight service intents arrive.
            // Every tap must retain its toggle, even when result messages are superseded.
            int beforeBurst = backend.writes;
            backend.changeEntered = new CountDownLatch(1);
            backend.allowChange = new CountDownLatch(1);
            for (int i = 0; i < 8; i++) {
                context.startForegroundService(BackgroundNfcService.command(context, first.id, false));
            }
            check(backend.changeEntered.await(5, TimeUnit.SECONDS), "Burst must reach the controller");
            check(notification(context, 201) != null, "Queued burst must stay foreground");
            backend.allowChange.countDown();
            await(test, () -> backend.writes == beforeBurst + 8 && !runtime.isBusy()
                    && !runtime.serviceRunning(), "Eight background toggles and idle shutdown");
            backend.changeEntered = null;
            check(backend.active == null && backend.restores == 1,
                    "An even burst must restore NFC without reinitializing the session");
            check(notification(context, 201) == null, "Final inactive state must remove foreground feedback");
            check(mainOpened.get() == 1, "Background burst must not open MainActivity");

            int beforeBusy = backend.writes;
            CountDownLatch busy = new CountDownLatch(2);
            AtomicReference<Throwable> rejected = new AtomicReference<>();
            test.runOnMainSync(() -> {
                runtime.setReaderBusy(true);
                runtime.toggle(first.id, (v, error) -> { rejected.set(error); busy.countDown(); });
                runtime.submit(true, state -> { state.change(null, false); return null; }, (v, error) -> {
                    if (!(error instanceof PhoneNfcSession.Failure)) {
                        throw new AssertionError("Notification stop must also respect reader mode");
                    }
                    busy.countDown();
                });
                runtime.setReaderBusy(false);
            });
            check(busy.await(5, TimeUnit.SECONDS), "Reader conflict must finish");
            check(rejected.get() instanceof PhoneNfcSession.Failure && backend.writes == beforeBusy,
                    "Reader mode must reject quick actions before controller writes");

            job(test, runtime, true, state -> { state.toggle(first.id); return null; }, false);
            backend.fail = true;
            job(test, runtime, true, state -> { state.beforeReplace(); CardStore.replaceAll(context, List.of());
                return null; }, true);
            check(CardStore.getCards(context).size() == 2 && first.id.equals(backend.active) && backend.pending,
                    "Failed restoration must preserve cards and recovery state");
            check(notification(context, 201) != null, "Failed restoration must keep foreground control available");

            int beforeOff = backend.writes;
            test.runOnMainSync(() -> PhoneNfcRuntime.adapterOff(false));
            await(test, () -> !runtime.isBusy() && backend.active == null, "Adapter invalidation");
            check(backend.pending && backend.writes == beforeOff, "Adapter-off broadcast must retain snapshots without writes");
            backend.fail = false;
            job(test, runtime, true, state -> { state.toggle(second.id); return null; }, false);
            check(backend.restores == 2 && second.id.equals(backend.active), "Next explicit action must restore before activation");
            job(test, runtime, true, state -> { state.change(null, false); return null; }, false);
            await(test, () -> !runtime.serviceRunning(), "Final service shutdown");
            check(mainOpened.get() == 1, "Only the explicit test launch may open MainActivity");
        } finally {
            PhoneNfcRuntime runtime = installed.get();
            if (runtime != null) {
                backend.fail = false;
                job(test, runtime, true, state -> { state.change(null, false); return null; }, false);
                await(test, () -> !runtime.serviceRunning(), "Cleanup service shutdown");
                test.runOnMainSync(() -> {
                    app.unregisterActivityLifecycleCallbacks(callbacks);
                    CardSession.clearActiveCard();
                    SharedPreferences.Editor edit = prefs.edit();
                    if (originalCards == null) edit.remove("cards"); else edit.putString("cards", originalCards);
                    edit.commit();
                    PhoneNfcRuntime.restoreAfterTest(previous.get());
                });
            }
            test.getUiAutomation().dropShellPermissionIdentity();
        }
    }

    private static View find(View root, Predicate<View> match) {
        if (root.getVisibility() != View.VISIBLE) return null;
        if (match.test(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), match);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void job(Instrumentation test, PhoneNfcRuntime runtime, boolean hardware,
                            PhoneNfcRuntime.Work<Void> work, boolean expectedFailure) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> result = new AtomicReference<>();
        test.runOnMainSync(() -> runtime.submit(hardware, work, (v, error) -> {
            result.set(error); completed.countDown();
        }));
        check(completed.await(10, TimeUnit.SECONDS), "Queued work must complete");
        check((result.get() != null) == expectedFailure, "Unexpected work result: " + result.get());
    }

    private static void await(Instrumentation test, BooleanSupplier condition, String name) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            test.runOnMainSync(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            Thread.sleep(40);
        }
        throw new AssertionError("Timeout: " + name);
    }

    private static StatusBarNotification notification(Context context, int id) {
        for (StatusBarNotification item : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (item.getId() == id) return item;
        }
        return null;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
