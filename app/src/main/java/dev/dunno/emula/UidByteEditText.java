package dev.dunno.emula;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.text.Layout;
import android.widget.EditText;

/** Displays the grid's logical selection/cursor without becoming a separate IME editor. */
class UidByteEditText extends EditText {
    private final Paint selectionPaint = new Paint();
    private final Paint cursorPaint = new Paint();
    private boolean editing;
    private long cursorShownAt;

    UidByteEditText(Context context) {
        super(context);
        selectionPaint.setColor(Color.argb(110, 113, 164, 255));
        setCursorVisible(false);
        setHighlightColor(Color.TRANSPARENT);
    }

    void setEditing(boolean editing) {
        this.editing = editing;
        if (editing) cursorShownAt = SystemClock.uptimeMillis();
        setActivated(editing);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Layout layout = getLayout();
        int start = getSelectionStart();
        int end = getSelectionEnd();
        // ViewGroup already translates the drawing canvas by the child's scroll.
        // TextView's single-line layout can be very wide: subtracting scroll again
        // pushes the selection/cursor outside the cell while the text remains visible.
        float left = getCompoundPaddingLeft();
        float top = getExtendedPaddingTop();
        if (layout != null) {
            int contentHeight = getHeight() - getExtendedPaddingTop() - getExtendedPaddingBottom();
            top += Math.max(0, (contentHeight - layout.getHeight()) / 2f);
            if (editing && start >= 0 && end >= 0 && start != end) {
                float startX = left + layout.getPrimaryHorizontal(Math.min(start, end));
                float endX = left + layout.getPrimaryHorizontal(Math.max(start, end));
                canvas.drawRect(startX, top, endX, top + layout.getHeight(), selectionPaint);
            }
        }
        super.onDraw(canvas);
        if (editing && hasWindowFocus() && layout != null && start >= 0 && start == end) {
            long blinkPhase = (SystemClock.uptimeMillis() - cursorShownAt) % 1_000;
            if (blinkPhase < 500) {
                float cursorX = left + layout.getPrimaryHorizontal(end);
                cursorPaint.setColor(getCurrentTextColor());
                cursorPaint.setStrokeWidth(Math.max(1, getResources().getDisplayMetrics().density));
                canvas.drawLine(cursorX, top, cursorX, top + layout.getHeight(), cursorPaint);
            }
            postInvalidateDelayed(500 - blinkPhase % 500);
        }
    }
}
