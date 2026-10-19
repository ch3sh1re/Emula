package dev.dunno.emula;

import android.content.Context;
import android.graphics.Rect;
import android.text.Editable;
import android.text.Selection;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.util.function.BooleanSupplier;

/** One IME editor with a logical byte selection and equal-sized, centered byte cells. */
final class UidByteGrid extends ViewGroup {
    private final int gap;
    private int uidLength = UidCodec.DEFAULT_UID_LENGTH;
    private int cellWidth;
    private int cellHeight;
    private UidByteEditText[] editors = new UidByteEditText[0];
    private int activeByte;
    private boolean applyingInput;

    UidByteGrid(Context context) {
        super(context);
        gap = Math.round(8 * getResources().getDisplayMetrics().density);
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    }

    void setEditors(UidByteEditText[] editors) {
        this.editors = editors;
        for (UidByteEditText editor : editors) {
            editor.setFocusable(false);
            editor.setFocusableInTouchMode(false);
        }
    }

    int getActiveByte() {
        return activeByte;
    }

    boolean isEditingByte(int index) {
        return hasFocus() && activeByte == index;
    }

    void selectByte(int index, boolean selectAll) {
        if (index < 0 || index >= uidLength || index >= editors.length) return;
        if (index != activeByte) {
            BaseInputConnection.removeComposingSpans(editors[activeByte].getText());
        }
        activeByte = index;
        requestFocus();
        UidByteEditText editor = editors[index];
        editor.setSelection(selectAll && editor.length() == 2 ? 0 : editor.length(), editor.length());
        refreshEditingState();
    }

    void showKeyboardForByte(int index) {
        selectByte(index, true);
        ((InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
                .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
    }

    void onByteTextChanged(int index, boolean deleting) {
        if (applyingInput) return;
        updateBytePosition(index, deleting, true);
        refreshEditingState();
    }

    private void updateBytePosition(int index, boolean deleting, boolean advance) {
        if (!isEditingByte(index)) return;
        if (advance && editors[index].length() == 2 && index + 1 < uidLength) {
            selectByte(index + 1, true);
        } else if (deleting && editors[index].length() == 0 && index > 0) {
            int previous = index - 1;
            while (previous > 0 && editors[previous].length() == 0) previous--;
            selectByte(previous, false);
        }
    }

    private void refreshEditingState() {
        for (int i = 0; i < editors.length; i++) editors[i].setEditing(isEditingByte(i));
        if (hasFocus() && editors.length > activeByte) {
            Editable text = editors[activeByte].getText();
            ((InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
                    .updateSelection(this, Selection.getSelectionStart(text),
                            Selection.getSelectionEnd(text),
                            BaseInputConnection.getComposingSpanStart(text),
                            BaseInputConnection.getComposingSpanEnd(text));
        }
    }

    private boolean mutateInput(BooleanSupplier operation, boolean advance) {
        int index = activeByte;
        int oldLength = editors[index].length();
        applyingInput = true;
        boolean result;
        try {
            result = operation.getAsBoolean();
        } finally {
            applyingInput = false;
        }
        updateBytePosition(index, editors[index].length() < oldLength, advance);
        refreshEditingState();
        return result;
    }

    private void prepareInsertion(CharSequence text) {
        UidByteEditText editor = editors[activeByte];
        // After deleting a whole byte the cursor is at the end of its full predecessor.
        // New input belongs to the following byte rather than being lost to LengthFilter.
        if (text.length() > 0 && editor.length() == 2
                && editor.getSelectionStart() == 2 && editor.getSelectionEnd() == 2
                && BaseInputConnection.getComposingSpanStart(editor.getText()) < 0
                && activeByte + 1 < uidLength) {
            selectByte(activeByte + 1, true);
        }
    }

    private boolean deleteBeforeCursor(int count) {
        if (count <= 0 || editors.length == 0) return false;
        String[] bytes = new String[editors.length];
        for (int i = 0; i < bytes.length; i++) bytes[i] = editors[i].getText().toString();
        UidByteEditText editor = editors[activeByte];
        UidByteDeletion.Result result = UidByteDeletion.delete(bytes, uidLength, activeByte,
                editor.getSelectionStart(), editor.getSelectionEnd(), count);
        applyingInput = true;
        try {
            for (int i = 0; i < uidLength; i++) {
                if (!bytes[i].equals(result.bytes[i])) {
                    Editable text = editors[i].getText();
                    BaseInputConnection.removeComposingSpans(text);
                    text.replace(0, text.length(), result.bytes[i]);
                }
            }
            activeByte = result.activeByte;
            editors[activeByte].setSelection(result.cursor);
        } finally {
            applyingInput = false;
        }
        refreshEditingState();
        return true;
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previous) {
        super.onFocusChanged(gainFocus, direction, previous);
        refreshEditingState();
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        if (editors.length == 0) return null;
        UidByteEditText editor = editors[activeByte];
        outAttrs.inputType = editor.getInputType();
        outAttrs.imeOptions = editor.getImeOptions();
        outAttrs.hintLocales = editor.getImeHintLocales();
        outAttrs.initialSelStart = editor.getSelectionStart();
        outAttrs.initialSelEnd = editor.getSelectionEnd();
        return new BaseInputConnection(this, true) {
            @Override
            public Editable getEditable() {
                return editors[activeByte].getText();
            }

            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                prepareInsertion(text);
                return mutateInput(() -> super.commitText(text, newCursorPosition), true);
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                prepareInsertion(text);
                return mutateInput(() -> super.setComposingText(text, newCursorPosition), false);
            }

            @Override
            public boolean finishComposingText() {
                boolean composing = getComposingSpanStart(getEditable()) >= 0;
                return mutateInput(super::finishComposingText, composing);
            }

            @Override
            public boolean setSelection(int start, int end) {
                boolean result = super.setSelection(start, end);
                refreshEditingState();
                return result;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (afterLength == 0 && beforeLength > 0
                        && editors[activeByte].getSelectionStart()
                        == editors[activeByte].getSelectionEnd()) {
                    return deleteBeforeCursor(beforeLength);
                }
                return mutateInput(() -> super.deleteSurroundingText(beforeLength, afterLength), false);
            }

            @Override
            public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
                // Both APIs are identical for the filtered ASCII HEX input.
                return deleteSurroundingText(beforeLength, afterLength);
            }

            @Override
            public boolean performEditorAction(int actionCode) {
                editors[activeByte].onEditorAction(actionCode);
                return true;
            }
        };
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (editors.length == 0) return super.onKeyDown(keyCode, event);
        if (keyCode == KeyEvent.KEYCODE_DEL) return deleteBeforeCursor(1);
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_TAB) {
            editors[activeByte].onEditorAction(EditorInfo.IME_ACTION_NEXT);
            return true;
        }
        if (event.isPrintingKey()) prepareInsertion(String.valueOf((char) event.getUnicodeChar()));
        return mutateInput(() -> editors[activeByte].onKeyDown(keyCode, event), event.isPrintingKey())
                || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DEL) return true;
        return (editors.length > 0 && editors[activeByte].onKeyUp(keyCode, event))
                || super.onKeyUp(keyCode, event);
    }

    void setUidLength(int length) {
        if (!UidCodec.isSupportedLength(length)) {
            throw new IllegalArgumentException("Unsupported UID length");
        }
        uidLength = length;
        if (activeByte >= length && editors.length > 0) {
            if (hasFocus()) selectByte(length - 1, true);
            else {
                activeByte = length - 1;
                editors[activeByte].setSelection(editors[activeByte].length());
                refreshEditingState();
            }
        }
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).setVisibility(i < length ? VISIBLE : GONE);
        }
        requestLayout();
    }

    private int columns() {
        return uidLength == 10 ? 5 : 4;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int availableWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        cellWidth = Math.max(1, (availableWidth - (columns() - 1) * gap) / columns());
        cellHeight = 0;
        for (int i = 0; i < uidLength; i++) {
            View cell = getChildAt(i);
            cell.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            cellHeight = Math.max(cellHeight, cell.getMeasuredHeight());
        }
        // Reserve the same two-row area even for four bytes, so the read button and
        // the dialog do not move while visibility/column count changes.
        int height = getPaddingTop() + getPaddingBottom()
                + 2 * cellHeight + gap;
        setMeasuredDimension(resolveSize(width, widthMeasureSpec),
                resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int availableWidth = right - left - getPaddingLeft() - getPaddingRight();
        int rows = (uidLength + columns() - 1) / columns();
        int firstRowTop = getPaddingTop() + (2 - rows) * (cellHeight + gap) / 2;
        for (int first = 0, row = 0; first < uidLength; first += columns(), row++) {
            int count = Math.min(columns(), uidLength - first);
            int rowWidth = count * cellWidth + (count - 1) * gap;
            int rowLeft = getPaddingLeft() + (availableWidth - rowWidth) / 2;
            int rowTop = firstRowTop + row * (cellHeight + gap);
            for (int column = 0; column < count; column++) {
                View cell = getChildAt(first + column);
                int cellLeft = rowLeft + column * (cellWidth + gap);
                cell.layout(cellLeft, rowTop, cellLeft + cellWidth, rowTop + cellHeight);
            }
        }
    }
}
