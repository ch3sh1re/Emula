package dev.dunno.emula;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small standalone watch probe. Its only controller requests are GET_CONFIG. */
public final class WatchDiagnosticsActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView reportView;
    private TextView statusView;
    private Button refreshButton;
    private Button readButton;
    private boolean busy;
    private String probeReport;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) probeReport = state.getString("probeReport");
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        inspect(false);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("probeReport", probeReport);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setFocusable(true);
        scroll.setVerticalScrollBarEnabled(false);
        boolean round = getResources().getConfiguration().isScreenRound();
        int side = dp(round ? 30 : 16);
        scroll.setPadding(side, dp(round ? 40 : 24), side, dp(round ? 48 : 24));
        scroll.setOnGenericMotionListener((view, event) -> {
            if (event.getAction() != MotionEvent.ACTION_SCROLL) return false;
            float amount = event.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (amount == 0) amount = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (amount == 0) return false;
            scroll.scrollBy(0, Math.round(-amount * dp(40)));
            return true;
        });

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));
        TextView title = text(getString(R.string.title), 20, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        column.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView note = text(getString(R.string.read_only_note), 13, Color.LTGRAY);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(10), 0, dp(12));
        column.addView(note, new LinearLayout.LayoutParams(-1, -2));

        refreshButton = button(getString(R.string.refresh));
        refreshButton.setOnClickListener(view -> inspect(false));
        column.addView(refreshButton, buttonLayout());
        readButton = button(getString(R.string.read_config));
        readButton.setOnClickListener(view -> inspect(true));
        column.addView(readButton, buttonLayout());

        statusView = text("", 13, Color.rgb(141, 185, 255));
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(10), 0, dp(12));
        statusView.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        column.addView(statusView, new LinearLayout.LayoutParams(-1, -2));
        reportView = text("", 12, Color.WHITE);
        reportView.setTypeface(Typeface.MONOSPACE);
        reportView.setTextIsSelectable(true);
        column.addView(reportView, new LinearLayout.LayoutParams(-1, -2));
        TextView grant = text(getString(R.string.grant_hint), 12, Color.LTGRAY);
        grant.setPadding(0, dp(18), 0, dp(12));
        column.addView(grant, new LinearLayout.LayoutParams(-1, -2));
        column.addView(text(getString(R.string.report_saved), 12, Color.LTGRAY));
        scroll.requestFocus();
        return scroll;
    }

    private void inspect(boolean readConfig) {
        if (busy) return;
        busy = true;
        setButtonEnabled(refreshButton, false);
        setButtonEnabled(readButton, false);
        statusView.setText(readConfig ? R.string.reading : R.string.checking);
        executor.execute(() -> {
            WatchNfcCapabilities capabilities = WatchNfcCapabilities.inspect(this);
            int status = R.string.checked;
            String nextProbe = probeReport;
            if (readConfig) {
                // Clear old evidence before a new attempt, including failed preconditions.
                nextProbe = null;
                if (!capabilities.adapterAvailable) {
                    status = R.string.no_nfc;
                } else if (!capabilities.nfcEnabled) {
                    status = R.string.nfc_disabled;
                } else if (!capabilities.permissionGranted) {
                    status = R.string.missing_permission;
                } else if (!capabilities.rawApiAvailable) {
                    status = R.string.missing_raw_api;
                } else {
                    try {
                        LegacyNfcDiagnostics.ProbeResult probe =
                                LegacyNfcDiagnostics.runReadOnlyProbe(this);
                        nextProbe = probe.detailedSummary;
                        status = probe.configuration.isEmpty()
                                ? R.string.probe_failed : R.string.probe_complete;
                    } catch (Throwable failure) {
                        nextProbe = "GET_CONFIG FAILED\n" + WatchNfcCapabilities.describe(failure);
                        status = R.string.probe_failed;
                    }
                }
                if (nextProbe == null) nextProbe = "GET_CONFIG NOT SENT\n" + getString(status);
            }
            String report = capabilities.report
                    + (nextProbe == null ? "\nGET_CONFIG: not requested\n"
                    : "\nLAST READ-ONLY PROBE\n" + nextProbe);
            try {
                Files.write(new File(getFilesDir(), "nfc-diagnostics.txt").toPath(),
                        report.getBytes(StandardCharsets.UTF_8));
            } catch (Exception failure) {
                AppLog.warning("Unable to save watch diagnostics", failure);
                status = R.string.save_failed;
            }
            AppLog.info(report);
            int finalStatus = status;
            String finalProbe = nextProbe;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                probeReport = finalProbe;
                reportView.setText(report);
                statusView.setText(finalStatus);
                setButtonEnabled(refreshButton, true);
                setButtonEnabled(readButton, capabilities.adapterAvailable && capabilities.nfcEnabled
                        && capabilities.permissionGranted && capabilities.rawApiAvailable);
                busy = false;
                if (!readConfig) {
                    if (!capabilities.adapterAvailable) statusView.setText(R.string.no_nfc);
                    else if (!capabilities.nfcEnabled) statusView.setText(R.string.nfc_disabled);
                    else if (!capabilities.permissionGranted) {
                        statusView.setText(R.string.missing_permission);
                    } else if (!capabilities.rawApiAvailable) {
                        statusView.setText(R.string.missing_raw_api);
                    }
                }
            });
        });
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private static void setButtonEnabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.45f);
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(38, 52, 73));
        background.setCornerRadius(dp(26));
        button.setBackground(background);
        return button;
    }

    private LinearLayout.LayoutParams buttonLayout() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.topMargin = dp(6);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
