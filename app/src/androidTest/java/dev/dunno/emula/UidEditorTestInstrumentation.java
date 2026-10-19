package dev.dunno.emula;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Android input/storage/surface checks and a service lifecycle using a fake NFC controller. */
public final class UidEditorTestInstrumentation extends Instrumentation {
    private int failures;
    private int current;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        runTest("heldDeleteKeepsOneConnection", this::heldDeleteKeepsOneConnection);
        runTest("typingAndTapsUseActiveByte", this::typingAndTapsUseActiveByte);
        runTest("typingAfterDeletionUsesClearedByte", this::typingAfterDeletionUsesClearedByte);
        runTest("acceleratedDeleteAndLengthChanges", this::acceleratedDeleteAndLengthChanges);
        runTest("hardwareDeleteRepeats", this::hardwareDeleteRepeats);
        runTest("cursorRendersWithinScrolledByte", this::cursorRendersWithinScrolledByte);
        runTest("oldFourByteCardsSurviveNewSaves", this::oldFourByteCardsSurviveNewSaves);
        runTest("csvStorageIsAtomicAndPreservesExistingCards",
                () -> CardTransferChecks.storage(getTargetContext()));
        runTest("csvExportProviderIsReadableAndReadOnly", () -> {
            try {
                CardTransferChecks.provider(getTargetContext());
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
        runTest("csvShareAndOpenIntentsResolve",
                () -> CardTransferChecks.intentFilters(getTargetContext()));
        runTest("csvCancellationClosesAndroidPipeAndReleasesWorker", () -> {
            try {
                CardTransferChecks.cancelPipeRead();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
        runTest("quickActionsAuthenticateIntentsAndRenderCurrentWidget", () ->
                QuickAccessChecks.intentsAndWidget(getTargetContext()));
        runTest("quickAccessComponentsResolve", () -> {
            try {
                QuickAccessChecks.components(getTargetContext());
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
        runTest("backgroundControlLifecycleWithoutNfcWrites", () -> {
            try { BackgroundNfcChecks.lifecycle(this); }
            catch (Exception failure) { throw new AssertionError(failure); }
        }, false);
        runTest("tileRefreshesWithoutAnotherStartListeningCallback", () -> {
            try { TileListeningChecks.refreshWhileListening(this); }
            catch (Exception failure) { throw new AssertionError(failure); }
        }, false);
        Bundle result = new Bundle();
        result.putString("stream", "\nUID editor, CSV, quick access and background control: "
                + (current - failures) + "/" + current + " passed without NFC hardware calls.\n");
        finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void runTest(String name, Runnable test) {
        runTest(name, test, true);
    }

    private void runTest(String name, Runnable test, boolean onMain) {
        Bundle status = new Bundle();
        status.putString("id", "InstrumentationTestRunner");
        status.putString("class", getClass().getName());
        status.putString("test", name);
        status.putInt("numtests", 15);
        status.putInt("current", ++current);
        sendStatus(1, status);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable execute = () -> {
            try {
                test.run();
            } catch (Throwable error) {
                failure.set(error);
            }
        };
        if (onMain) runOnMainSync(execute); else execute.run();
        if (failure.get() == null) {
            status.putString("stream", "\n" + name + ": passed\n");
            sendStatus(0, status);
        } else {
            failures++;
            status.putString("stack", android.util.Log.getStackTraceString(failure.get()));
            status.putString("stream", "\n" + name + ": " + failure.get() + "\n");
            sendStatus(-2, status);
        }
    }

    private static final class Editor {
        final UidByteGrid grid;
        final UidByteEditText[] bytes = new UidByteEditText[10];
        final InputConnection connection;

        Editor(Context context, int length, boolean filled) {
            grid = new UidByteGrid(context);
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = new UidByteEditText(context);
                bytes[i].setInputType(InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                bytes[i].setFilters(new InputFilter[] {
                        new InputFilter.AllCaps(), new InputFilter.LengthFilter(2)});
                if (filled) bytes[i].setText("AB");
            }
            grid.setEditors(bytes);
            grid.setUidLength(length);
            grid.selectByte(filled ? length - 1 : 0, false);
            check(grid.hasFocus(), "Grid must own focus");
            connection = grid.onCreateInputConnection(new EditorInfo());
            check(connection != null, "Connection must exist");
        }
    }

    private Editor editor(int length, boolean filled) {
        Context context = new ContextThemeWrapper(getTargetContext(), android.R.style.Theme_Material);
        return new Editor(context, length, filled);
    }

    private void heldDeleteKeepsOneConnection() {
        for (int length : new int[] {4, 7, 10}) {
            Editor editor = editor(length, true);
            for (int i = 0; i < length * 2 + 5; i++) {
                check(editor.connection.deleteSurroundingText(1, 0), "Delete must be accepted");
                check(editor.grid.hasFocus(), "IME view must retain focus");
                check(editor.bytes[editor.grid.getActiveByte()].isActivated(), "Active outline must follow deletion");
            }
            for (int i = 0; i < 10; i++) {
                check(editor.bytes[i].getText().toString().equals(i < length ? "" : "AB"),
                        "Visible bytes clear; hidden draft survives");
            }
            check(editor.grid.getActiveByte() == 0, "Deletion stops at byte zero");
            check(editor.connection.commitText("c", 1), "Same connection can type after deletion");
            check(editor.bytes[0].getText().toString().equals("C"), "Typing resumes in byte zero");
        }
    }

    private void typingAndTapsUseActiveByte() {
        Editor editor = editor(4, false);
        editor.connection.commitText("a", 1);
        editor.connection.commitText("b", 1);
        check(editor.bytes[0].getText().toString().equals("AB"), "First byte must fill");
        check(editor.grid.getActiveByte() == 1, "Typing advances to next byte");
        editor.connection.commitText("cd", 1);
        check(editor.grid.getActiveByte() == 2, "Same connection advances again");
        editor.grid.selectByte(0, true);
        editor.connection.finishComposingText();
        check(editor.grid.getActiveByte() == 0, "Finishing absent composition must preserve tapped byte");
        check(editor.bytes[0].getSelectionStart() == 0 && editor.bytes[0].getSelectionEnd() == 2,
                "Tapping a complete byte selects both characters");
        editor.connection.commitText("ef", 1);
        check(editor.bytes[0].getText().toString().equals("EF"), "Input replaces selected byte");
        check(editor.bytes[1].getText().toString().equals("CD"), "Next byte survives replacement");
        int[] action = {-1};
        editor.bytes[1].setOnEditorActionListener((view, actionId, event) -> {
            action[0] = actionId;
            return true;
        });
        editor.connection.performEditorAction(EditorInfo.IME_ACTION_NEXT);
        check(action[0] == EditorInfo.IME_ACTION_NEXT, "IME Next reaches the active byte listener");
    }

    private void typingAfterDeletionUsesClearedByte() {
        Editor editor = editor(4, true);
        editor.connection.deleteSurroundingText(1, 0);
        editor.connection.deleteSurroundingText(1, 0);
        check(editor.grid.getActiveByte() == 2, "Clearing last byte highlights its predecessor");
        editor.connection.commitText("c", 1);
        check(editor.bytes[3].getText().toString().equals("C"), "First new character must not be lost");
        check(editor.bytes[2].getText().toString().equals("AB"), "Preceding byte survives resumed typing");
    }

    private void acceleratedDeleteAndLengthChanges() {
        Editor editor = editor(10, true);
        editor.grid.setUidLength(4);
        check(editor.grid.getActiveByte() == 3, "Length change clamps active byte");
        // Surrounding-text deletion excludes a selection; hardware Backspace and
        // commitText("") clear selections instead. Collapse it for accelerated repeat.
        editor.connection.deleteSurroundingTextInCodePoints(5, 0);
        check(editor.bytes[3].getText().toString().equals("AB"), "Surrounding deletion preserves selected byte");
        editor.grid.selectByte(3, false);
        editor.connection.deleteSurroundingTextInCodePoints(5, 0);
        check(editor.bytes[1].getText().toString().equals("A"), "Accelerated deletion crosses boundaries");
        check(editor.grid.getActiveByte() == 1, "Active byte follows accelerated delete");
        check(editor.bytes[4].getText().toString().equals("AB"), "Hidden draft survives");
        editor.grid.setUidLength(10);
        editor.grid.selectByte(9, false);
        editor.connection.deleteSurroundingText(1, 0);
        check(editor.bytes[9].getText().toString().equals("A"), "Same connection edits newly visible byte");
    }

    private void hardwareDeleteRepeats() {
        Editor editor = editor(4, true);
        editor.grid.selectByte(3, true);
        for (int i = 0; i < 12; i++) {
            check(editor.grid.onKeyDown(KeyEvent.KEYCODE_DEL,
                    new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, i)),
                    "Repeated hardware delete must be accepted");
        }
        for (int i = 0; i < 4; i++) check(editor.bytes[i].length() == 0, "Hardware repeats clear UID");
        check(editor.grid.hasFocus(), "Hardware repeats retain grid focus");
    }

    private void cursorRendersWithinScrolledByte() {
        Context context = new ContextThemeWrapper(getTargetContext(), android.R.style.Theme_Material);
        // Only fake the window-focus flag; measure/layout/scroll and drawing are real Android.
        UidByteEditText field = new UidByteEditText(context) {
            @Override public boolean hasWindowFocus() { return true; }
        };
        field.setSingleLine(true);
        field.setGravity(Gravity.CENTER);
        field.setTextSize(20);
        field.setTextColor(Color.WHITE);
        field.setPadding(0, 0, 0, 0);
        field.setBackgroundColor(Color.TRANSPARENT);
        field.setFocusable(false);
        FrameLayout parent = new FrameLayout(context);
        parent.addView(field, new FrameLayout.LayoutParams(160, 96));
        for (String text : new String[] {"", "A", "AB"}) {
            field.setText(text);
            field.setSelection(text.length());
            parent.measure(View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(96, View.MeasureSpec.EXACTLY));
            parent.layout(0, 0, 160, 96);
            field.bringPointIntoView(text.length());
            Bitmap idle = Bitmap.createBitmap(160, 96, Bitmap.Config.ARGB_8888);
            Bitmap active = Bitmap.createBitmap(160, 96, Bitmap.Config.ARGB_8888);
            try {
                field.setEditing(false);
                parent.draw(new Canvas(idle));
                field.setEditing(true);
                parent.draw(new Canvas(active));
                int changedPixels = 0;
                for (int y = 0; y < 96; y++) {
                    for (int x = 0; x < 160; x++) {
                        if (idle.getPixel(x, y) != active.getPixel(x, y)) changedPixels++;
                    }
                }
                check(changedPixels >= 10, "Cursor must draw inside byte '" + text
                        + "' (scroll=" + field.getScrollX() + ")");
            } finally {
                idle.recycle();
                active.recycle();
            }
        }
    }

    private void oldFourByteCardsSurviveNewSaves() {
        String testFile = "uid_editor_legacy_test_" + UUID.randomUUID();
        SharedPreferences isolated = getTargetContext().getSharedPreferences(testFile, Context.MODE_PRIVATE);
        Context context = new ContextWrapper(getTargetContext()) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                check(name.equals("office_cards"), "Storage must keep its original preference file");
                return isolated;
            }
        };
        String oldJson = "[{\"id\":\"legacy-1\",\"name\":\"Office\",\"uid\":\"AQIDBA==\"},"
                + "{\"id\":\"legacy-2\",\"name\":\"Parking\",\"uid\":\"AAEC/w==\"}]";
        try {
            check(isolated.edit().putString("cards", oldJson).commit(), "Old fixture must persist");
            List<SavedCard> old = CardStore.getCards(context);
            check(old.size() == 2, "Both old four-byte cards must load");
            check(old.get(0).id.equals("legacy-1") && old.get(0).name.equals("Office"),
                    "Old ID and name must survive");
            check(Arrays.equals(old.get(0).uid, new byte[] {1, 2, 3, 4}), "Old UID must decode exactly");
            check(Arrays.equals(old.get(1).uid, new byte[] {0, 1, 2, (byte) 255}),
                    "Zero/high UID bytes must survive");
            SavedCard added = CardStore.save(context, null, "Seven", new byte[] {1, 2, 3, 4, 5, 6, 7});
            CardStore.save(context, old.get(0), "Renamed", old.get(0).uid);
            List<SavedCard> reloaded = CardStore.getCards(context);
            check(reloaded.size() == 3, "New long card must coexist with old cards");
            check(reloaded.get(0).id.equals("legacy-1") && reloaded.get(0).name.equals("Renamed"),
                    "Editing an old card must retain its ID");
            check(Arrays.equals(reloaded.get(0).uid, old.get(0).uid)
                    && Arrays.equals(reloaded.get(1).uid, old.get(1).uid),
                    "Re-saving the catalog must preserve old UIDs");
            CardStore.delete(context, added.id);
            check(CardStore.getCards(context).size() == 2, "Deleting a new card must retain old cards");
        } finally {
            getTargetContext().deleteSharedPreferences(testFile);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
