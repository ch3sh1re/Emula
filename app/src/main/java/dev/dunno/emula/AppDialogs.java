package dev.dunno.emula;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Phone dialog surfaces, actions and bounded layouts shared by confirmations and choices. */
final class AppDialogs {
    static final int SURFACE = Color.rgb(53, 57, 65);
    static final int SECONDARY = Color.rgb(174, 181, 194);
    static final int ACCENT = Color.rgb(113, 164, 255);
    static final int ERROR = Color.rgb(255, 138, 128);

    private AppDialogs() {}

    static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable rounded(Activity activity, int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(activity, radius));
        return shape;
    }

    static LinearLayout panel(Activity activity, int title) {
        return panel(activity, activity.getText(title));
    }

    private static LinearLayout panel(Activity activity, CharSequence title) {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(activity, 24), dp(activity, 22), dp(activity, 24), dp(activity, 12));
        panel.setBackground(rounded(activity, SURFACE, 22));
        TextView heading = new TextView(activity);
        heading.setText(title);
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(24);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setAccessibilityHeading(true);
        heading.setPadding(0, 0, 0, dp(activity, 18));
        panel.addView(heading);
        return panel;
    }

    static Button action(Activity activity, int label) {
        Button button = new Button(activity);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(ACCENT);
        button.setTextSize(15);
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(32, 113, 164, 255)),
                null, rounded(activity, Color.WHITE, 12)));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(activity, 48));
        button.setMinimumHeight(dp(activity, 48));
        button.setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8));
        return button;
    }

    static void show(Activity activity, Dialog dialog, LinearLayout panel) {
        dialog.setContentView(panel);
        dialog.show();
        fit(activity, dialog, panel);
    }

    private static void fit(Activity activity, Dialog dialog, LinearLayout panel) {
        Window window = dialog.getWindow();
        if (window == null) return;
        WindowMetrics metrics = activity.getWindowManager().getCurrentWindowMetrics();
        Insets safe = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        int width = Math.max(1, Math.min(metrics.getBounds().width() - safe.left - safe.right
                - dp(activity, 36), dp(activity, 520)));
        int height = Math.max(1, Math.min(metrics.getBounds().height() - safe.top - safe.bottom
                - dp(activity, 36), dp(activity, 720)));
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = width;
        params.height = panel.getMeasuredHeight();
        params.dimAmount = 0.68f;
        window.setAttributes(params);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    }

    static final class Builder {
        private final Activity activity;
        private CharSequence title;
        private CharSequence message;
        private int positive;
        private int negative;
        private boolean destructive;
        private DialogInterface.OnClickListener onPositive;
        private DialogInterface.OnClickListener onNegative;
        private DialogInterface.OnCancelListener onCancel;
        private final java.util.List<Choice> choices = new java.util.ArrayList<>();

        Builder(Activity activity) { this.activity = activity; }
        Builder setTitle(int title) { this.title = activity.getText(title); return this; }
        Builder setMessage(int message) { return setMessage(activity.getText(message)); }
        Builder setMessage(CharSequence message) { this.message = message; return this; }
        Builder setPositiveButton(int label, DialogInterface.OnClickListener clicked) {
            positive = label; onPositive = clicked; return this;
        }
        Builder setNegativeButton(int label, DialogInterface.OnClickListener clicked) {
            negative = label; onNegative = clicked; return this;
        }
        Builder setDestructivePositiveButton() { destructive = true; return this; }
        Builder setOnCancelListener(DialogInterface.OnCancelListener cancelled) {
            onCancel = cancelled; return this;
        }
        Builder addChoice(int label, int description, boolean destructive, Runnable selected) {
            choices.add(new Choice(label, description, destructive, selected));
            return this;
        }

        Dialog create() {
            LinearLayout panel = panel(activity, title);
            Dialog dialog = new Dialog(activity) {
                @Override public void show() {
                    super.show();
                    fit(activity, this, panel);
                }
            };
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setTitle(title);
            dialog.setCanceledOnTouchOutside(true);
            if (onCancel != null) dialog.setOnCancelListener(onCancel);

            ScrollView scroll = new ScrollView(activity);
            scroll.setClipToPadding(false);
            LinearLayout body = new LinearLayout(activity);
            body.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(body, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (message != null && message.length() > 0) {
                TextView text = new TextView(activity);
                text.setText(message);
                text.setTextColor(Color.WHITE);
                text.setTextSize(16);
                text.setPadding(0, 0, 0, dp(activity, choices.isEmpty() ? 8 : 12));
                body.addView(text);
            }
            for (Choice choice : choices) body.addView(choiceView(dialog, choice));
            panel.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

            ActionRow actions = new ActionRow(activity);
            if (negative != 0) actions.addView(button(dialog, negative,
                    DialogInterface.BUTTON_NEGATIVE, onNegative, false));
            if (positive != 0) actions.addView(button(dialog, positive,
                    DialogInterface.BUTTON_POSITIVE, onPositive, destructive));
            panel.addView(actions, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dialog.setContentView(panel);
            return dialog;
        }

        Dialog show() { Dialog dialog = create(); dialog.show(); return dialog; }

        private Button button(Dialog dialog, int label, int which,
                DialogInterface.OnClickListener listener, boolean destructive) {
            Button button = action(activity, label);
            if (destructive) button.setTextColor(ERROR);
            button.setOnClickListener(v -> {
                try { if (listener != null) listener.onClick(dialog, which); }
                finally { dialog.dismiss(); }
            });
            return button;
        }

        private View choiceView(Dialog dialog, Choice choice) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12));
            row.setMinimumHeight(dp(activity, 72));
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(40, 255, 255, 255)),
                    null, rounded(activity, Color.WHITE, 12)));
            row.setClickable(true);
            row.setFocusable(true);
            row.setContentDescription(activity.getText(choice.label) + ". "
                    + activity.getText(choice.description));
            row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Button.class.getName());
                }
            });
            TextView label = new TextView(activity);
            label.setText(choice.label);
            label.setTextColor(choice.destructive ? ERROR : Color.WHITE);
            label.setTextSize(17);
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(label);
            TextView description = new TextView(activity);
            description.setText(choice.description);
            description.setTextColor(SECONDARY);
            description.setTextSize(14);
            description.setPadding(0, dp(activity, 4), 0, 0);
            description.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(description);
            row.setOnClickListener(v -> {
                try { choice.selected.run(); }
                finally { dialog.dismiss(); }
            });
            return row;
        }
    }

    private static final class Choice {
        final int label, description;
        final boolean destructive;
        final Runnable selected;
        Choice(int label, int description, boolean destructive, Runnable selected) {
            this.label = label; this.description = description;
            this.destructive = destructive; this.selected = selected;
        }
    }

    /** Keep complete action labels: stack only when the available width requires it. */
    private static final class ActionRow extends LinearLayout {
        ActionRow(Activity activity) {
            super(activity);
            setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            setPadding(0, dp(activity, 8), 0, 0);
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int needed = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
                needed += child.getMeasuredWidth();
            }
            boolean stack = MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED
                    && needed > MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
            setOrientation(stack ? VERTICAL : HORIZONTAL);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                LayoutParams params = (LayoutParams) child.getLayoutParams();
                int width = stack ? LayoutParams.MATCH_PARENT : LayoutParams.WRAP_CONTENT;
                if (params.width != width || params.height != LayoutParams.WRAP_CONTENT) {
                    params.width = width;
                    params.height = LayoutParams.WRAP_CONTENT;
                    child.setLayoutParams(params);
                }
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }
}
