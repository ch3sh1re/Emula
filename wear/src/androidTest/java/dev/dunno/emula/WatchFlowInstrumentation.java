package dev.dunno.emula;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.Context;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Emulator-only manual CRUD checks; never claims an RF hardware test. */
public final class WatchFlowInstrumentation extends Instrumentation {
    private WatchActivity activity;
    private int checks;

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
            runOnMainSync(() -> findCard(activity.getWindow().getDecorView()).performClick());
            awaitIdle();
            check(!getTargetContext().getSharedPreferences("watch_restore", Context.MODE_PRIVATE)
                    .getBoolean("pending", false), "Missing NFC never starts a write session");
            checks++;
            runOnMainSync(() -> findCard(activity.getWindow().getDecorView()).performAccessibilityAction(
                    R.id.action_delete_card, null));
            runOnMainSync(() -> click(getTargetContext().getString(R.string.delete_card)));
            awaitIdle();
            check(CardStore.getCards(getTargetContext()).isEmpty(), "Confirmed deletion removes the card");
            checks++;
            result.putString("stream", "\nWatch manual CRUD checks: " + checks + "/6 passed. No NFC hardware requests.\n");
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
