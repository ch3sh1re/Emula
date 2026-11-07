package dev.dunno.emula;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.AtomicFile;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Phone presentation and private retention of a report explicitly offered on the watch. */
final class WatchReportReceiver {
    private static final int PERMISSION_REQUEST = 0x5746;
    private static final ExecutorService FILES = Executors.newSingleThreadExecutor();
    private static final Object REPORT_FILE = new Object();
    private final Activity activity;
    private final AtomicFile lastReport;
    private Dialog dialog;
    private WatchReportLink link;
    private boolean disposed;
    private boolean interrupted;
    private int fileRequest;

    WatchReportReceiver(Activity activity) {
        this.activity = activity;
        lastReport = new AtomicFile(new File(activity.getFilesDir(), "watch-nfc-diagnostics.txt"));
    }

    void open() {
        if (disposed || link != null) return;
        fileRequest++;
        if (!lastReport.getBaseFile().exists()) { receive(); return; }
        dismiss();
        dialog = new AppDialogs.Builder(activity).setTitle(R.string.watch_report_title)
                .addChoice(R.string.watch_report_receive, R.string.watch_report_receive_hint, false, this::receive)
                .addChoice(R.string.watch_report_last, R.string.watch_report_last_hint, false, this::readLast)
                .setNegativeButton(R.string.action_close, null).show();
    }

    private void receive() {
        if (disposed || link != null) return;
        fileRequest++;
        if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, PERMISSION_REQUEST);
        } else choose();
    }

    boolean permissionResult(int requestCode, int[] results) {
        if (requestCode != PERMISSION_REQUEST) return false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) receive();
        else error(R.string.watch_report_permission);
        return true;
    }

    @SuppressLint("MissingPermission") // CONNECT checked above; runtime revocation is caught.
    private void choose() {
        try {
            BluetoothManager manager = activity.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) { error(R.string.watch_send_bluetooth); return; }
            List<BluetoothDevice> devices = new ArrayList<>(adapter.getBondedDevices());
            if (devices.isEmpty()) { error(R.string.watch_send_pair); return; }
            String last = activity.getSharedPreferences("watch_transfer", Activity.MODE_PRIVATE)
                    .getString("last_device", "");
            devices.sort(Comparator.comparing(device -> device.getAddress().equals(last) ? 0 : 1));
            dismiss();
            AppDialogs.Builder builder = new AppDialogs.Builder(activity).setTitle(R.string.watch_report_title)
                    .setMessage(R.string.watch_report_choose)
                    .setNegativeButton(R.string.action_cancel, null);
            for (BluetoothDevice device : devices) {
                String name = device.getName();
                builder.addChoice(name == null || name.isBlank() ? activity.getString(R.string.watch_send_device) : name,
                        activity.getString(R.string.watch_send_paired), false, () -> connect(device));
            }
            dialog = builder.show();
        } catch (SecurityException failure) { error(R.string.watch_report_permission); }
    }

    private void connect(BluetoothDevice device) {
        if (disposed || link != null) return;
        dismiss();
        dialog = new AppDialogs.Builder(activity).setTitle(R.string.watch_report_title)
                .setMessage(R.string.watch_report_wait)
                .setNegativeButton(R.string.action_cancel, (d, w) -> cancel())
                .setOnCancelListener(d -> cancel()).show();
        link = WatchReportLink.receive(device, this::save, new WatchReportLink.Callback() {
            @Override public void complete(String report) {
                if (disposed) return;
                cancel();
                activity.getSharedPreferences("watch_transfer", Activity.MODE_PRIVATE).edit()
                        .putString("last_device", device.getAddress()).apply();
                preview(report);
            }
            @Override public void failed(boolean timeout) {
                if (disposed) return;
                cancel();
                error(timeout ? R.string.watch_report_timeout : R.string.watch_report_failed);
            }
        });
    }

    private void save(String report) throws IOException {
        byte[] bytes = WatchReportPacket.encode(report);
        synchronized (REPORT_FILE) { saveBytes(bytes); }
    }

    private void saveBytes(byte[] bytes) throws IOException {
        FileOutputStream output = null;
        try {
            output = lastReport.startWrite();
            output.write(bytes);
            lastReport.finishWrite(output);
        } catch (IOException failure) {
            lastReport.failWrite(output);
            throw failure;
        }
    }

    private void readLast() {
        int request = ++fileRequest;
        FILES.execute(() -> {
            try {
                String report;
                synchronized (REPORT_FILE) {
                    try (FileInputStream input = lastReport.openRead()) {
                        byte[] bytes = input.readNBytes(WatchReportPacket.MAX_BYTES + 1);
                        if (bytes.length == 0 || bytes.length > WatchReportPacket.MAX_BYTES) throw new IOException("Invalid report");
                        report = new String(bytes, StandardCharsets.UTF_8);
                    }
                }
                activity.runOnUiThread(() -> { if (!disposed && request == fileRequest) preview(report); });
            } catch (IOException failure) {
                activity.runOnUiThread(() -> {
                    if (!disposed && request == fileRequest) error(R.string.watch_report_read_failed);
                });
            }
        });
    }

    private void preview(String report) {
        if (disposed) return;
        dismiss();
        Dialog preview = new Dialog(activity);
        preview.requestWindowFeature(Window.FEATURE_NO_TITLE);
        preview.setTitle(R.string.watch_report_preview);
        LinearLayout panel = AppDialogs.panel(activity, R.string.watch_report_preview);
        TextView privacy = new TextView(activity);
        privacy.setText(R.string.watch_report_privacy);
        privacy.setTextColor(AppDialogs.SECONDARY);
        privacy.setTextSize(14);
        panel.addView(privacy);
        ScrollView scroll = new ScrollView(activity);
        TextView text = new TextView(activity);
        text.setText(report);
        text.setTextColor(Color.WHITE);
        text.setTextSize(12);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextIsSelectable(true);
        int padding = AppDialogs.dp(activity, 12);
        text.setPadding(0, padding, 0, padding);
        scroll.addView(text);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button copy = AppDialogs.action(activity, R.string.diagnostics_copy);
        copy.setOnClickListener(v -> activity.getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.watch_report_preview), report)));
        panel.addView(copy);
        Button share = AppDialogs.action(activity, R.string.watch_report_share);
        share.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.watch_report_subject))
                    .putExtra(Intent.EXTRA_TEXT, report);
            try { activity.startActivity(Intent.createChooser(intent, activity.getText(R.string.watch_report_share))); }
            catch (android.content.ActivityNotFoundException failure) {
                Toast.makeText(activity, R.string.watch_report_share_unavailable, Toast.LENGTH_LONG).show();
            }
        });
        panel.addView(share);
        Button close = AppDialogs.action(activity, R.string.action_close);
        close.setOnClickListener(v -> preview.dismiss());
        panel.addView(close);
        dialog = preview;
        AppDialogs.show(activity, preview, panel);
    }

    private void error(int message) {
        if (disposed) return;
        dismiss();
        dialog = new AppDialogs.Builder(activity).setTitle(R.string.watch_report_title).setMessage(message)
                .setPositiveButton(R.string.watch_send_choose_action, (d, w) -> receive())
                .setNegativeButton(R.string.action_close, null).show();
    }

    void onPause() {
        fileRequest++;
        if (link != null) { cancel(); interrupted = true; }
    }
    void onResume() {
        if (interrupted) { interrupted = false; error(R.string.watch_report_stopped); }
    }
    void dispose() { disposed = true; cancel(); }
    private void dismiss() { if (dialog != null) { dialog.dismiss(); dialog = null; } }
    private void cancel() { if (link != null) { link.close(); link = null; } dismiss(); }
}
