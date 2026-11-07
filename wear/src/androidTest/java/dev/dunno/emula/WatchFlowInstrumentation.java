package dev.dunno.emula;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Dialog;
import android.content.Intent;
import android.content.Context;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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
                    + feedbackChecks + " passed. No NFC hardware requests.\n");
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
            feedbackChecks += 2;
        });
    }

    private void preflightError(String id, WatchUiError kind, int reason, int action) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Class<?> jobClass = Class.forName("dev.dunno.emula.WatchActivity$Job");
        Class<?> operationClass = Class.forName("dev.dunno.emula.WatchActivity$Operation");
        Object start = java.util.Arrays.stream(operationClass.getEnumConstants())
                .filter(v -> v.toString().equals("START")).findFirst().get();
        Object job = Proxy.newProxyInstance(jobClass.getClassLoader(), new Class<?>[]{jobClass}, (proxy, method, args) -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test operation timed out");
            throw new WatchUiError.Condition(kind, "simulated prerequisite: " + kind);
        });
        java.lang.reflect.Method run = WatchActivity.class.getDeclaredMethod("runTask", jobClass, Runnable.class, operationClass);
        run.setAccessible(true);
        runOnMainSync(() -> {
            try {
                Field target = WatchActivity.class.getDeclaredField("busyCardId");
                target.setAccessible(true);
                target.set(activity, id);
                run.invoke(activity, job, null, start);
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        check(entered.await(5, TimeUnit.SECONDS), "Operation started");
        try {
            runOnMainSync(() -> {
                Button card = findCard(activity.getWindow().getDecorView());
                check(card.getText().toString().contains("04 11 22") && !card.getText().toString().contains("Starting"),
                        "Progress keeps the card name and UID intact");
                check(findText(activity.getWindow().getDecorView(), getTargetContext().getString(R.string.starting)) != null,
                        "Progress is beside the card");
            });
        } finally { release.countDown(); }
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
        Button button = findButton(activity.getWindow().getDecorView(), label);
        check(button != null, "Button exists: " + label);
        button.performClick();
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
