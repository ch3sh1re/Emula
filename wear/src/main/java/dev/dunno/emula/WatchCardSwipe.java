package dev.dunno.emula;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** The card slides over the delete indicator. Only releasing a deliberate left drag opens confirmation. */
@SuppressLint("ViewConstructor") // Built around a card, never inflated from XML.
final class WatchCardSwipe extends FrameLayout {
    private final View card;
    private final ImageView trash;
    private final Runnable confirmDelete;
    private final GradientDrawable outline;
    private final int slop;
    private float downX, downY;
    private boolean dragging, ignored, thresholdSignalled;

    @SuppressLint("RtlHardcoded") // Keep rightward gestures available to Wear's system navigation in all locales.
    WatchCardSwipe(View card, Runnable confirmDelete) {
        super(card.getContext());
        this.card = card;
        this.confirmDelete = confirmDelete;
        slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(dp(28));
        track.setColor(0xFF8C2733);
        setBackground(track);
        setClipToOutline(true);
        outline = new GradientDrawable();
        outline.setCornerRadius(dp(28));
        outline.setColor(Color.TRANSPARENT);
        outline.setStroke(dp(2), CardPalette.ACTIVE_OUTLINE);

        trash = new ImageView(getContext());
        trash.setImageResource(R.drawable.ic_delete_24);
        trash.setScaleType(ImageView.ScaleType.CENTER);
        trash.setAlpha(.65f);
        trash.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(trash, new LayoutParams(dp(52), LayoutParams.MATCH_PARENT, Gravity.RIGHT));
        addView(card, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        card.setOnTouchListener(this::onCardTouch);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int maxRevealWidth = Math.round(getMeasuredWidth() * .4f);
        if (trash.getMeasuredWidth() > maxRevealWidth) {
            trash.measure(MeasureSpec.makeMeasureSpec(maxRevealWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(trash.getMeasuredHeight(), MeasureSpec.EXACTLY));
        }
    }

    void showSelection(boolean active) {
        setForeground(active ? outline : null);
    }

    boolean isSliding() {
        return card.getTranslationX() != 0;
    }

    void setCardEnabled(boolean enabled) {
        if (!enabled && dragging) {
            ignored = true;
            finishDrag(false);
        }
        card.setEnabled(enabled);
    }

    private boolean onCardTouch(View view, MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                dragging = false;
                ignored = isSliding();
                thresholdSignalled = false;
                return ignored;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                ignored = true;
                if (!dragging) {
                    if (action == MotionEvent.ACTION_POINTER_DOWN) cancelButtonPress(event);
                    return false;
                }
                finishDrag(false);
                return true;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                if (ignored) return false;
                break;
            default:
                return dragging;
        }

        float dx = event.getRawX() - downX;
        if (!dragging) {
            float dy = event.getRawY() - downY;
            if (Math.max(Math.abs(dx), Math.abs(dy)) <= slop) return false;
            cancelButtonPress(event);
            if (-dx <= Math.abs(dy) * 1.3f) {
                ignored = true;
                return false;
            }
            dragging = true;
            getParent().requestDisallowInterceptTouchEvent(true);
        }

        // The rounded outer edge makes a full-width reveal look wider on the card side.
        int threshold = Math.max(1, trash.getWidth() - dp(1));
        float distance = Math.max(0, Math.min(-dx, threshold));
        card.setTranslationX(-distance);
        boolean pastThreshold = distance >= threshold;
        trash.setAlpha(pastThreshold ? 1f : .65f);
        if (pastThreshold && !thresholdSignalled) {
            card.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE);
            thresholdSignalled = true;
        } else if (distance < threshold - slop) {
            thresholdSignalled = false;
        }
        if (action == MotionEvent.ACTION_UP) finishDrag(pastThreshold);
        return true;
    }

    private void finishDrag(boolean confirm) {
        dragging = false;
        getParent().requestDisallowInterceptTouchEvent(false);
        if (confirm) {
            card.setTranslationX(0);
            confirmDelete.run();
        } else {
            trash.setAlpha(.65f);
            card.animate().translationX(0).setDuration(160).setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private void cancelButtonPress(MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        card.onTouchEvent(cancel);
        cancel.recycle();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
