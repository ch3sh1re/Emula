package dev.dunno.emula;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Dialog;
import android.content.Intent;
import android.content.Context;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Emulator-only CRUD and failure UX checks; never issues NFC hardware commands. */
public final class WatchFlowInstrumentation extends Instrumentation {
    private WatchActivity activity;
    private int checks;
    private int feedbackChecks;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int resultCode = Activity.RESULT_CANCELED;
        try {
            check(NfcAdapter.getDefaultAdapter(getTargetContext()) == null,
                    "This runner is only for an emulator without an NFC adapter");
            check(CardStore.getCards(getTargetContext()).isEmpty(),
                    "Use a clean, isolated emulator; existing cards must be preserved");
            launch();
            runOnMainSync(() -> click(getTargetContext().getString(R.string.add_card)));
            runOnMainSync(() -> {
                check(countEditors(activity.getWindow().getDecorView()) == 2, "Compact name and UID inputs");
                field("nameInput").setText("Office");
                field("uidInput").setText("01020304");
                click(getTargetContext().getString(R.string.save));
            });
            awaitIdle();
            List<SavedCard> cards = CardStore.getCards(getTargetContext());
            check(cards.size() == 1 && cards.get(0).uid.length == 4, "Manual card saved");
            String id = cards.get(0).id;
            checks++;
            runOnMainSync(() -> findCard(activity.getWindow().getDecorView()).performLongClick());
            runOnMainSync(() -> {
                field("nameInput").setText("Office updated");
                field("uidInput").setText("04112233445566");
                click(getTargetContext().getString(R.string.save));
            });
            awaitIdle();
            cards = CardStore.getCards(getTargetContext());
            check(cards.size() == 1 && cards.get(0).id.equals(id) && cards.get(0).uid.length == 7,
                    "Edit preserves ID and supports seven bytes");
            checks++;
            runOnMainSync(() -> findCard(activity.getWindow().getDecorView()).performLongClick());
            runOnMainSync(() -> {
                field("uidInput").setText("04112233445566778899");
                click(getTargetContext().getString(R.string.save));
            });
            awaitIdle();
            check(CardStore.getCards(getTargetContext()).get(0).uid.length == 10, "Ten-byte UID saved");
            checks++;
            runOnMainSync(() -> activity.finish());
            waitForIdleSync();
            launch();
            check(CardStore.getCards(getTargetContext()).get(0).id.equals(id), "Card survives restart");
            checks++;
            runOnMainSync(() -> checkActiveAppearance(CardStore.getCards(getTargetContext()).get(0)));
            runOnMainSync(() -> {
                checkThresholdHaptic(110, 43);
                checkThresholdHaptic(130, 51);
                checkThresholdHaptic(200, 51);
            });
            checkCardList();
            checkStableCardToggle(id);
            preflightError(id, WatchUiError.PERMISSION, R.string.error_permission, R.string.setup);
            preflightError(id, WatchUiError.NFC_OFF, R.string.error_nfc_off, R.string.open_nfc_settings);
            runOnMainSync(() -> findCard(activity.getWindow().getDecorView()).performClick());
            awaitIdle();
            capture("failure-ui.png");
            check(!getTargetContext().getSharedPreferences("watch_restore", Context.MODE_PRIVATE)
                    .getBoolean("pending", false), "Missing NFC never starts a write session");
            checks++;
            runOnMainSync(() -> {
                Dialog error = (Dialog) value("errorDialog");
                check(error != null && error.isShowing(), "Real failure opens a persistent dialog");
                View root = error.getWindow().getDecorView();
                check(findText(root, getTargetContext().getString(R.string.error_start)) != null,
                        "Error title is in the foreground");
                check(findButton(root, getTargetContext().getString(R.string.send_report)) != null,
                        "Failure offers report transfer");
                Button send = findButton(root, getTargetContext().getString(R.string.send_report));
                android.graphics.Rect visible = new android.graphics.Rect();
                check(send.getGlobalVisibleRect(visible) && visible.height() == send.getHeight(),
                        "Report action fits above the fold on the small screen");
                String report = (String) value("reportText");
                check(report.contains("OPERATION FAILURE") && report.contains("NFC adapter unavailable"),
                        "Cached report retains the actual failure");
                findButton(root, getTargetContext().getString(R.string.diagnostics)).performClick();
                check(report.equals(value("reportText")), "Opening diagnostics preserves failure evidence");
                check(!(Boolean) value("busy"), "Opening diagnostics does not start a probe");
                feedbackChecks += 3;
                click(getTargetContext().getString(R.string.prepare_report));
            });
            awaitIdle();
            runOnMainSync(() -> {
                String report = (String) value("reportText");
                check(report.contains("OPERATION FAILURE") && report.contains("NFC adapter unavailable"),
                        "Preparing a report retains the activation failure");
                check(report.contains("READ-ONLY PROBE UNAVAILABLE") && (Boolean) value("reportPrepared"),
                        "Unavailable NFC produces a shareable report with the diagnostic reason");
                check(((String) value("lastResult")).contains("NFC adapter unavailable"),
                        "Diagnostic preparation does not overwrite the operation result");
                check(value("errorDialog") == null, "A skipped diagnostic does not open another error dialog");
                check(!getTargetContext().getSharedPreferences("watch_restore", Context.MODE_PRIVATE)
                        .getBoolean("pending", false), "Preparing never creates a write transaction");
                feedbackChecks += 3;
                // A completed preparation must not start a Bluetooth offer after Home/ambient.
                Object before = value("screen");
                callActivityOnPause(activity);
                invoke("offerPreparedReport", new Class<?>[0]);
                check(value("screen") == before && value("reportLink") == null,
                        "Prepared report cannot start transfer while paused");
                callActivityOnResume(activity);
                feedbackChecks++;
                click(getTargetContext().getString(R.string.back));
                click(getTargetContext().getString(R.string.back));
            });
            runOnMainSync(() -> invoke("confirmExperimentalRaw", new Class<?>[]{SavedCard.class},
                    CardStore.getCards(getTargetContext()).get(0)));
            capture("experimental-consent.png");
            runOnMainSync(() -> {
                Dialog consent = (Dialog) value("rawConsentDialog");
                check(consent != null && consent.isShowing(), "Unknown firmware asks for explicit approval");
                findButton(consent.getWindow().getDecorView(), getTargetContext().getString(R.string.cancel)).performClick();
                check(!getTargetContext().getSharedPreferences("watch_experiments", Context.MODE_PRIVATE)
                        .contains("rawFirmware"), "Cancelling does not save approval");
                check(!getTargetContext().getSharedPreferences("watch_restore", Context.MODE_PRIVATE)
                        .getBoolean("pending", false), "Cancelling does not start a write transaction");
                feedbackChecks++;
            });
            runOnMainSync(() -> findCard(activity.getWindow().getDecorView()).performAccessibilityAction(
                    R.id.action_delete_card, null));
            runOnMainSync(() -> click(getTargetContext().getString(R.string.delete_card)));
            awaitIdle();
            check(CardStore.getCards(getTargetContext()).isEmpty(), "Confirmed deletion removes the card");
            checks++;
            result.putString("stream", "\nWatch manual CRUD checks: " + checks + "/6; failure UX checks: "
                    + feedbackChecks + " passed. Optical swipe limit, fixed trash position, icon dimming, threshold haptic, active outline, Setup navigation and card colors passed. No NFC hardware requests.\n");
            resultCode = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("stream", android.util.Log.getStackTraceString(failure));
        } finally {
            if (activity != null) runOnMainSync(() -> activity.finish());
        }
        finish(resultCode, result);
    }

    private void launch() throws Exception {
        Intent intent = new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext(),
                "dev.dunno.emula.WatchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (WatchActivity) startActivitySync(intent);
        awaitIdle();
        capture("setup-ui.png");
        runOnMainSync(() -> {
            Dialog setup = (Dialog) value("setupDialog");
            check(setup != null && setup.isShowing(), "First opening explains one-time setup");
            Button later = findButton(setup.getWindow().getDecorView(), getTargetContext().getString(R.string.later));
            android.graphics.Rect visible = new android.graphics.Rect();
            check(later != null && later.getGlobalVisibleRect(visible)
                    && visible.height() == later.getHeight(), "Later fits on the small screen");
            later.performClick();
            callActivityOnPause(activity);
            callActivityOnResume(activity);
            check(value("setupDialog") == null, "Setup prompt does not repeat on resume");
            click(getTargetContext().getString(R.string.setup));
            View page = activity.getWindow().getDecorView();
            check(findText(page, getTargetContext().getString(R.string.reveal_developer_options)) != null
                            && findButton(page, getTargetContext().getString(R.string.open_watch_settings)) != null,
                    "First-time setup offers a route to Build number");
            check(findText(page, getTargetContext().getString(R.string.grant_command,
                            getTargetContext().getPackageName())) != null,
                    "The ADB command targets the installed watch variant");
            click(getTargetContext().getString(R.string.back));
            check(value("screen").toString().equals("CARDS"), "Setup opened from cards returns to cards");
            click(getTargetContext().getString(R.string.settings));
            click(getTargetContext().getString(R.string.setup));
            Bundle saved = new Bundle();
            callActivityOnSaveInstanceState(activity, saved);
            check("SETTINGS".equals(saved.getString("setupParent")), "Setup remembers its parent on recreation");
            click(getTargetContext().getString(R.string.back));
            check(value("screen").toString().equals("SETTINGS"), "Setup Back returns to Settings");
            click(getTargetContext().getString(R.string.setup));
            ((android.window.OnBackInvokedCallback) value("backCallback")).onBackInvoked();
            check(value("screen").toString().equals("SETTINGS"), "System Back uses the same Setup parent");
            click(getTargetContext().getString(R.string.back));
            feedbackChecks += 8;
        });
    }

    private void preflightError(String id, WatchUiError kind, int reason, int action) throws Exception {
        runOnMainSync(() -> runCardJob(id, "START", () -> {
            throw new WatchUiError.Condition(kind, "simulated prerequisite: " + kind);
        }));
        awaitIdle();
        runOnMainSync(() -> {
            check(value("errorDialog") == null, "Prerequisites do not open a failure dialog");
            View status = (View) value("inlineStatus");
            check(status != null && findText(status, getTargetContext().getString(reason)) != null,
                    "Short reason appears beside the selected card");
            check(findButton(status, getTargetContext().getString(action)) != null, "Prerequisite offers its remedy");
            check(findButton(status, getTargetContext().getString(R.string.send_report)) == null,
                    "Prerequisite does not ask for diagnostics");
            feedbackChecks += 2;
        });
    }

    private void checkCardList() throws Exception {
        Context context = getTargetContext();
        android.content.SharedPreferences prefs = context.getSharedPreferences("office_cards", Context.MODE_PRIVATE);
        org.json.JSONArray legacy = new org.json.JSONArray(prefs.getString("cards", "[]"));
        legacy.getJSONObject(0).remove("colorHue");
        check(prefs.edit().putString("cards", legacy.toString()).commit(), "Legacy fixture saved");
        SavedCard first = CardStore.getCards(context).get(0);
        check(first.colorHue == CardPalette.defaultHue(0), "Legacy card keeps its original phone color");
        byte[] gymUid = {10, 11, 12, 13};
        List<WatchCardPacket.Entry> batch = List.of(new WatchCardPacket.Entry("Gym", gymUid),
                new WatchCardPacket.Entry("Duplicate", gymUid), new WatchCardPacket.Entry("Phone name", first.uid));
        CardStore.appendReceived(context, batch);
        CardStore.applyReceivedColors(context, batch, new float[]{160, 180, 285});
        CardStore.appendReceived(context, batch);
        List<SavedCard> saved = CardStore.getCards(context);
        check(saved.size() == 2 && saved.get(1).colorHue == 160f && saved.get(0).colorHue == 285f
                && saved.get(0).name.equals(first.name) && saved.get(0).id.equals(first.id),
                "Received and retried colors match phone UIDs, preserving watch identity and name");
        runOnMainSync(() -> invoke("render", new Class<?>[0]));
        waitForIdleSync();
        View root = activity.getWindow().getDecorView();
        Button card = findCard(root);
        WatchCardSwipe face = (WatchCardSwipe) card.getParent();
        View indicator = face.getChildAt(0);
        float density = context.getResources().getDisplayMetrics().density;
        runOnMainSync(() -> {
            check(!indicator.isClickable() && !indicator.isFocusable() && face.getClipToOutline(),
                    "Trash shares the clipped card surface and is decorative");
            drag(face, -18, 0, MotionEvent.ACTION_UP, () -> check(card.getTranslationX() < 0,
                    "The card follows the finger before reaching the threshold"));
        });
        awaitOffset(card, 0);
        runOnMainSync(() -> {
            check(value("screen").toString().equals("CARDS"), "A short release returns to the list");
            drag(face, -70, 0, MotionEvent.ACTION_CANCEL, null);
        });
        awaitOffset(card, 0);
        runOnMainSync(() -> {
            drag(face, 30, 0, MotionEvent.ACTION_UP, null);
            drag(face, -8, 22, MotionEvent.ACTION_UP, null);
            check(card.getTranslationX() == 0 && !(Boolean) value("busy")
                    && value("screen").toString().equals("CARDS"), "Rightward and vertical drags leave the card alone");
            long start = SystemClock.uptimeMillis();
            swipeEvent(face, start, MotionEvent.ACTION_DOWN, 0, 0);
            swipeEvent(face, start, MotionEvent.ACTION_MOVE, -68, 0);
            swipeEvent(face, start, MotionEvent.ACTION_MOVE, -12, 0);
            swipeEvent(face, start, MotionEvent.ACTION_UP, -12, 0);
        });
        awaitOffset(card, 0);
        long holdStart = SystemClock.uptimeMillis();
        runOnMainSync(() -> {
            check(value("screen").toString().equals("CARDS"), "Retreating below the threshold cancels the action");
            swipeEvent(face, holdStart, MotionEvent.ACTION_DOWN, 0, 0);
            swipeEvent(face, holdStart, MotionEvent.ACTION_MOVE, -96, 0);
            float cardEdge = card.getRight() + card.getTranslationX();
            check(value("screen").toString().equals("CARDS") && cardEdge == indicator.getLeft() + Math.round(density),
                    "A long swipe stops one dp short of the full delete area");
        });
        capture("card-swipe.png");
        runOnMainSync(() -> {
            swipeEvent(face, holdStart, MotionEvent.ACTION_UP, -96, 0);
            check(findText(root, context.getString(R.string.delete_title, first.name)) != null
                    && CardStore.getCards(context).size() == 2, "Release asks for confirmation without deleting");
        });
        capture("card-delete-confirm.png");
        runOnMainSync(() -> click(context.getString(R.string.cancel)));
        waitForIdleSync();
        runOnMainSync(() -> ((View) value("scroll")).scrollTo(0, 10000));
        runOnMainSync(() -> {
            int size = Math.round(48 * density);
            int[] prev = null;
            for (int label : new int[]{R.string.add_card, R.string.receive_cards, R.string.settings}) {
                View icon = findIcon(root, label);
                android.graphics.Rect bounds = new android.graphics.Rect();
                check(icon != null && icon.getGlobalVisibleRect(bounds) && bounds.width() >= size
                        && bounds.height() >= size, "Each labeled action has a visible 48dp target");
                int[] pos = {bounds.left, bounds.top};
                if (prev != null) check(pos[0] >= prev[0] + size && pos[1] == prev[1], "Actions share one row");
                prev = pos;
            }
        });
        capture("card-actions.png");
        runOnMainSync(() -> {
            findIcon(root, R.string.settings).performClick();
            check(value("screen").toString().equals("SETTINGS"), "Gear opens settings");
            click(context.getString(R.string.back));
        });
        CardStore.delete(context, saved.get(1).id);
        CardStore.save(context, first, first.name, first.uid);
        check(CardStore.getCards(context).get(0).colorHue == 285f, "Deleting and editing preserve the received color");
        runOnMainSync(() -> invoke("render", new Class<?>[0]));
        waitForIdleSync();
    }

    private void drag(View face, int dxDp, int dyDp, int end, Runnable during) {
        long start = SystemClock.uptimeMillis();
        swipeEvent(face, start, MotionEvent.ACTION_DOWN, 0, 0);
        swipeEvent(face, start, MotionEvent.ACTION_MOVE, dxDp, dyDp);
        if (during != null) during.run();
        swipeEvent(face, start, end, dxDp, dyDp);
    }

    private static void swipeEvent(View face, long start, int action, float dxDp, int dyDp) {
        float density = face.getResources().getDisplayMetrics().density;
        float x = face.getWidth() * .7f + dxDp * density;
        float y = face.getHeight() * .5f + dyDp * density;
        MotionEvent event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0);
        try { face.dispatchTouchEvent(event); } finally { event.recycle(); }
    }

    private void awaitOffset(View card, int target) throws Exception {
        long limit = SystemClock.uptimeMillis() + 2500;
        AtomicBoolean done = new AtomicBoolean();
        do {
            runOnMainSync(() -> done.set(Math.abs(card.getTranslationX() - target) < .01f));
            if (done.get()) return;
            Thread.sleep(20);
        } while (SystemClock.uptimeMillis() < limit);
        throw new AssertionError("Swipe did not settle at " + target);
    }

    private void checkStableCardToggle(String id) throws Exception {
        CountDownLatch finish = new CountDownLatch(1);
        WatchSessionStore store = (WatchSessionStore) value("store");
        View root = activity.getWindow().getDecorView();
        Button card = findCard(root);
        View add = findIcon(root, R.string.add_card);
        View scroll = (View) value("scroll");
        int[] initialY = new int[2];
        runOnMainSync(() -> {
            scroll.scrollTo(0, 12);
            initialY[0] = scroll.getScrollY();
            initialY[1] = ((View) add.getParent()).getTop();
            runCardJob(id, "START", () -> {
                if (!finish.await(12, TimeUnit.SECONDS)) throw new AssertionError("Toggle timed out");
                store.save(new WatchNfcSession.Pending(WatchNfcSession.Path.OEM,
                        android.os.Build.FINGERPRINT, "", id, true));
                return "Enabled";
            });
            check(findCard(root) == card && card.getStateDescription() == null, "A quick tap has no busy frame");
        });
        try {
            long deadline = android.os.SystemClock.uptimeMillis() + 3000;
            AtomicBoolean shown = new AtomicBoolean();
            while (!shown.get() && android.os.SystemClock.uptimeMillis() < deadline) {
                runOnMainSync(() -> shown.set(getTargetContext().getString(R.string.starting)
                        .equals(card.getStateDescription())));
                if (!shown.get()) Thread.sleep(20);
            }
            runOnMainSync(() -> {
                check(shown.get() && card.getStateDescription() != null, "TalkBack receives the slow operation state");
                boolean visibleBar = false;
                ViewGroup face = (ViewGroup) card.getParent();
                for (int i = 0; i < face.getChildCount(); i++) {
                    View child = face.getChildAt(i);
                    if (child instanceof ProgressBar && child.isShown()) visibleBar = true;
                }
                check(visibleBar && card.getText().toString().contains("04 11 22"), "Progress and UID share the card");
                check(!card.isEnabled() && !add.isEnabled() && add.getAlpha() == 1f && card.getAlpha() == 1f,
                        "Repeated actions are blocked without dimming the list");
                check(value("scroll") == scroll && scroll.getScrollY() == initialY[0]
                                && ((View) add.getParent()).getTop() == initialY[1],
                        "Progress keeps the viewport and the next button in place");
            });
            capture("card-toggle-progress.png");
        } finally { finish.countDown(); }
        awaitIdle();
        runOnMainSync(() -> {
            check(findCard(root) == card && card.isSelected() && card.isEnabled() && add.isEnabled(),
                    "Successful activation updates the existing card");
        });
        capture("card-active.png");
        runOnMainSync(() -> runCardJob(id, "STOP", () -> { store.clear(); return "Stopped"; }));
        awaitIdle();
        runOnMainSync(() -> {
            check(findCard(root) == card && !card.isSelected() && card.getStateDescription() == null
                            && value("scroll") == scroll && scroll.getScrollY() == initialY[0],
                    "A quick stop clears selection while keeping the viewport");
            scroll.scrollTo(0, 0);
            feedbackChecks += 6;
        });
    }

    private void runCardJob(String id, String operation, java.util.concurrent.Callable<String> work) {
        try {
            Class<?> jobClass = Class.forName("dev.dunno.emula.WatchActivity$Job");
            Class<?> operationClass = Class.forName("dev.dunno.emula.WatchActivity$Operation");
            Object kind = java.util.Arrays.stream(operationClass.getEnumConstants())
                    .filter(v -> v.toString().equals(operation)).findFirst().get();
            Object job = Proxy.newProxyInstance(jobClass.getClassLoader(), new Class<?>[]{jobClass},
                    (proxy, method, args) -> work.call());
            java.lang.reflect.Method run = WatchActivity.class.getDeclaredMethod(
                    "runTask", jobClass, Runnable.class, operationClass);
            run.setAccessible(true);
            Field target = WatchActivity.class.getDeclaredField("busyCardId");
            target.setAccessible(true);
            target.set(activity, id);
            run.invoke(activity, job, null, kind);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private void checkActiveAppearance(SavedCard card) {
        WatchSessionStore store = (WatchSessionStore) value("store");
        Button button = findCard(activity.getWindow().getDecorView());
        WatchCardSwipe face = (WatchCardSwipe) button.getParent();
        ViewGroup list = (ViewGroup) face.getParent();
        int originalRows = list.getChildCount(), originalChildren = face.getChildCount();
        android.graphics.drawable.Drawable fill = button.getBackground();
        String label = getTargetContext().getString(R.string.card_label, card.name, Hex.bytes(card.uid));
        OemOperationReport report = new OemOperationReport(OemOperationReport.Operation.ACTIVATE);
        report.path = OemOperationReport.Path.ANDROID_OEM_EXTENSION;
        report.androidStatus = 0;
        report.stage = "COMPLETE";
        report.uid = card.uid.clone();
        OemNfcController.recordOperation(report);
        try {
            store.save(new WatchNfcSession.Pending(WatchNfcSession.Path.OEM,
                    android.os.Build.FINGERPRINT, "", card.id, true));
            for (String observation : new String[]{"MATCH", "MISMATCH", "INCOMPLETE", "UNAVAILABLE"}) {
                report.androidReadbackStatus = observation;
                invoke("render", new Class<?>[]{boolean.class}, true);
                check(button.isSelected() && face.getForeground() != null && button.getBackground() == fill
                        && label.contentEquals(button.getText()), "Only the outline marks the selected card");
                check(list.getChildCount() == originalRows && face.getChildCount() == originalChildren
                        && getTargetContext().getString(R.string.card_active_accessibility, card.name,
                        Hex.bytes(card.uid)).contentEquals(button.getContentDescription()),
                        "Selection preserves layout and is announced to accessibility");
            }
        } finally {
            OemNfcController.recordOperation(null);
            store.clear();
            invoke("render", new Class<?>[]{boolean.class}, true);
        }
        check(face.getForeground() == null && !button.isSelected(), "Stop clears the outline");
    }

    private void checkThresholdHaptic(int widthDp, int thresholdDp) {
        int[] pulses = {0};
        Button button = new Button(activity) {
            @Override public boolean performHapticFeedback(int feedback) {
                check(feedback == HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE, "Threshold feedback type");
                pulses[0]++;
                return true;
            }
        };
        WatchCardSwipe face = new WatchCardSwipe(button, () -> { throw new AssertionError("Cancelled swipe committed"); });
        FrameLayout host = new FrameLayout(activity);
        host.addView(face, new FrameLayout.LayoutParams(-1, -1));
        float scale = activity.getResources().getDisplayMetrics().density;
        int width = Math.round(widthDp * scale), height = Math.round(64 * scale);
        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        host.layout(0, 0, width, height);
        View indicator = face.getChildAt(0);
        int indicatorWidth = Math.min(Math.round(52 * scale), Math.round(width * .4f));
        check(indicator.getRight() == width && indicator.getWidth() == indicatorWidth,
                "The trash stays in its original slot against the right edge");
        float travelPx = indicatorWidth - Math.round(scale);
        long start = SystemClock.uptimeMillis();
        swipeEvent(face, start, MotionEvent.ACTION_DOWN, 0, 0);
        swipeEvent(face, start, MotionEvent.ACTION_MOVE, -35, 0);
        check(pulses[0] == 0 && indicator.getAlpha() == .65f, "The trash stays dim before the threshold");
        swipeEvent(face, start, MotionEvent.ACTION_MOVE, -(travelPx - 1) / scale, 0);
        check(pulses[0] == 0, "No haptic one pixel before the visual stop");
        swipeEvent(face, start, MotionEvent.ACTION_MOVE, -(travelPx + .25f) / scale, 0);
        check(pulses[0] == 1 && indicator.getAlpha() == 1f, "Crossing the threshold brightens the trash and vibrates");
        swipeEvent(face, start, MotionEvent.ACTION_MOVE, -thresholdDp - 24, 0);
        check(Math.abs(button.getTranslationX() + thresholdDp * scale) < 1f && pulses[0] == 1,
                "Dragging farther holds the card at the haptic threshold with a single pulse");
        float exposedLeft = button.getRight() + button.getTranslationX();
        float iconCenter = (indicator.getLeft() + indicator.getRight()) / 2f;
        float rightSpace = face.getWidth() - iconCenter;
        float leftSpace = iconCenter - exposedLeft;
        check(rightSpace - leftSpace == Math.round(scale),
                "The straight card edge has one dp less space than the rounded outer edge");
        swipeEvent(face, start, MotionEvent.ACTION_MOVE, -thresholdDp + 1, 0);
        swipeEvent(face, start, MotionEvent.ACTION_MOVE, -thresholdDp - 3, 0);
        check(pulses[0] == 1, "Jitter around the threshold does not repeat the vibration");
        swipeEvent(face, start, MotionEvent.ACTION_MOVE, -20, 0);
        check(Math.abs(button.getTranslationX() + 20 * scale) < 1f && indicator.getAlpha() == .65f,
                "The card follows a retreat below the limit and the trash dims again");
        swipeEvent(face, start, MotionEvent.ACTION_MOVE, -thresholdDp - 3, 0);
        check(pulses[0] == 2, "A deliberate retreat rearms threshold feedback");
        swipeEvent(face, start, MotionEvent.ACTION_CANCEL, -thresholdDp - 3, 0);
    }

    private Object value(String name) {
        try {
            Field field = WatchActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private void invoke(String name, Class<?>[] types, Object... arguments) {
        try {
            java.lang.reflect.Method method = WatchActivity.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(activity, arguments);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private void capture(String name) throws Exception {
        waitForIdleSync();
        getUiAutomation().waitForIdle(200, 3000);
        android.graphics.Bitmap screenshot = getUiAutomation().takeScreenshot();
        check(screenshot != null, "Emulator screenshot available");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(
                new java.io.File(getTargetContext().getFilesDir(), name))) {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } finally { screenshot.recycle(); }
    }

    private void awaitIdle() throws Exception {
        Field field = WatchActivity.class.getDeclaredField("busy");
        field.setAccessible(true);
        long deadline = android.os.SystemClock.elapsedRealtime() + 10_000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            waitForIdleSync();
            AtomicBoolean busy = new AtomicBoolean();
            runOnMainSync(() -> {
                try { busy.set(field.getBoolean(activity)); }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
            if (!busy.get()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Watch operation did not finish");
    }

    private EditText field(String name) {
        try {
            Field value = WatchActivity.class.getDeclaredField(name);
            value.setAccessible(true);
            return (EditText) value.get(activity);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private void click(String label) {
        View root = activity.getWindow().getDecorView();
        View button = findButton(root, label);
        if (button == null) button = findIcon(root, label);
        check(button != null, "Button exists: " + label);
        button.performClick();
    }

    private View findIcon(View root, int label) {
        return findIcon(root, getTargetContext().getString(label));
    }

    private View findIcon(View root, String label) {
        java.util.ArrayList<View> matches = new java.util.ArrayList<>();
        root.findViewsWithText(matches, label, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        for (View view : matches) {
            if (view instanceof ImageButton && label.contentEquals(view.getContentDescription())) return view;
        }
        return null;
    }

    private static Button findButton(View view, String label) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(label)) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                Button found = findButton(group.getChildAt(index), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int countEditors(View view) {
        if (view instanceof EditText) return 1;
        int count = 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) count += countEditors(group.getChildAt(i));
        }
        return count;
    }

    private static TextView findText(View view, String label) {
        if (view instanceof TextView && ((TextView) view).getText().toString().equals(label)) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Button findCard(View view) {
        if (view instanceof Button && ((Button) view).getText().toString().contains("Office")) {
            return (Button) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findCard(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
