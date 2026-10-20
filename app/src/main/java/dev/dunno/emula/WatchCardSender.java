package dev.dunno.emula;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.pm.PackageManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** User-initiated whole-catalog transfer; lifecycle cancellation never changes phone cards. */
final class WatchCardSender {
    private static final int PERMISSION_REQUEST = 0x5743;
    private final Activity activity;
    private Dialog dialog;
    private LocalWatchLink link;
    private boolean disposed;

    WatchCardSender(Activity activity) { this.activity = activity; }

    void sendAll() {
        if (disposed || link != null) return;
        if (CardStore.getCards(activity).isEmpty()) { error(R.string.watch_send_empty, false); return; }
        if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, PERMISSION_REQUEST);
            return;
        }
        choose(false);
    }

    boolean permissionResult(int requestCode, int[] results) {
        if (requestCode != PERMISSION_REQUEST) return false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) sendAll();
        else error(R.string.watch_send_permission, false);
        return true;
    }

    // sendAll checks CONNECT before reaching any device API; revocation is also caught below.
    @SuppressLint("MissingPermission")
    private void choose(boolean forceChoice) {
        try {
            BluetoothManager manager = activity.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) { error(R.string.watch_send_bluetooth, false); return; }
            List<BluetoothDevice> devices = new ArrayList<>(adapter.getBondedDevices());
            if (devices.isEmpty()) { error(R.string.watch_send_pair, false); return; }
            String last = activity.getSharedPreferences("watch_transfer", Activity.MODE_PRIVATE)
                    .getString("last_device", "");
            if (!forceChoice) {
                for (BluetoothDevice device : devices) {
                    if (device.getAddress().equals(last)) { send(device); return; }
                }
            }
            devices.sort(Comparator.comparing(device -> name(device), String.CASE_INSENSITIVE_ORDER));
            dismiss();
            AppDialogs.Builder builder = new AppDialogs.Builder(activity)
                    .setTitle(R.string.watch_send_title)
                    .setMessage(R.string.watch_send_choose)
                    .setNegativeButton(R.string.action_cancel, (d, w) -> {});
            for (BluetoothDevice device : devices) {
                builder.addChoice(name(device), activity.getString(R.string.watch_send_paired), false,
                        () -> send(device));
            }
            dialog = builder.show();
        } catch (SecurityException failure) { error(R.string.watch_send_permission, false); }
    }

    @SuppressLint("MissingPermission")
    private String name(BluetoothDevice device) {
        String name = device.getName();
        return name == null || name.isBlank() ? activity.getString(R.string.watch_send_device) : name;
    }

    @SuppressLint("MissingPermission")
    private void send(BluetoothDevice device) {
        if (disposed || link != null) return;
        List<SavedCard> cards = CardStore.getCards(activity);
        byte[] packet;
        try { packet = WatchCardPacket.encode(cards); }
        catch (IOException failure) { error(R.string.watch_send_limit, false); return; }
        dismiss();
        dialog = new AppDialogs.Builder(activity)
                .setTitle(R.string.watch_send_title)
                .setMessage(activity.getString(R.string.watch_send_wait, cards.size()))
                .setNegativeButton(R.string.action_cancel, (d, w) -> cancel())
                .setOnCancelListener(d -> cancel()).show();
        link = LocalWatchLink.send(device, packet, cards.size(), new LocalWatchLink.Callback() {
            @Override public void complete(WatchCardPacket.Result result) {
                if (disposed) return;
                cancel();
                activity.getSharedPreferences("watch_transfer", Activity.MODE_PRIVATE).edit()
                        .putString("last_device", device.getAddress()).apply();
                dialog = new AppDialogs.Builder(activity).setTitle(R.string.watch_send_title)
                        .setMessage(activity.getString(R.string.watch_send_done, result.added, result.skipped))
                        .setPositiveButton(R.string.action_close, (d, w) -> {}).show();
            }
            @Override public void failed(boolean timeout) {
                if (disposed) return;
                cancel();
                error(timeout ? R.string.watch_send_timeout : R.string.watch_send_failed, true);
            }
        });
    }

    private void error(int message, boolean chooseAgain) {
        if (disposed) return;
        dismiss();
        AppDialogs.Builder builder = new AppDialogs.Builder(activity).setTitle(R.string.watch_send_title)
                .setMessage(message).setNegativeButton(R.string.action_close, (d, w) -> {});
        if (chooseAgain) builder.setPositiveButton(R.string.watch_send_choose_action, (d, w) -> choose(true));
        dialog = builder.show();
    }

    private void dismiss() { if (dialog != null) { dialog.dismiss(); dialog = null; } }
    private void cancel() { if (link != null) { link.close(); link = null; } dismiss(); }
    void dispose() { disposed = true; cancel(); }
}
