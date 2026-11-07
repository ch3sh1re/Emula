package dev.dunno.emula;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import java.io.Closeable;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground, encrypted RFCOMM between bonded devices. Separate from the card service. */
@SuppressLint("MissingPermission") // Both UI entry points request CONNECT; revocation fails closed.
final class WatchReportLink implements AutoCloseable {
    private static final UUID SERVICE = UUID.fromString("9ca2fb44-bcb0-41b0-9576-19c6388e953b");
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile boolean timedOut;
    private BluetoothSocket socket;
    private BluetoothServerSocket server;

    interface Receiver { void save(String report) throws Exception; }
    interface Callback {
        void complete(String report);
        void failed(boolean timeout);
    }
    private interface Operation { String run() throws Exception; }

    static WatchReportLink offer(BluetoothAdapter adapter, byte[] report, Callback callback) {
        WatchReportLink link = new WatchReportLink();
        link.start(() -> {
            BluetoothServerSocket server = adapter.listenUsingRfcommWithServiceRecord("Emula diagnostics", SERVICE);
            link.server(server);
            BluetoothSocket socket = server.accept();
            link.socket(socket);
            server.close();
            if (socket.getRemoteDevice().getBondState() != BluetoothDevice.BOND_BONDED) {
                throw new IOException("Pair the devices before transferring diagnostics");
            }
            WatchReportPacket.hello(socket.getOutputStream());
            WatchReportPacket.checkRequest(socket.getInputStream());
            link.checkActive();
            WatchReportPacket.report(socket.getOutputStream(), report);
            WatchReportPacket.checkReceipt(socket.getInputStream());
            return null;
        }, callback);
        return link;
    }

    static WatchReportLink receive(BluetoothDevice device, Receiver receiver, Callback callback) {
        WatchReportLink link = new WatchReportLink();
        link.start(() -> {
            if (device.getBondState() != BluetoothDevice.BOND_BONDED) throw new IOException("Watch is not paired");
            BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SERVICE);
            link.socket(socket);
            socket.connect();
            WatchReportPacket.checkHello(socket.getInputStream());
            WatchReportPacket.request(socket.getOutputStream());
            String report = WatchReportPacket.readReport(socket.getInputStream());
            link.checkActive();
            receiver.save(report); // Acknowledge only after the phone has retained the report.
            WatchReportPacket.receipt(socket.getOutputStream());
            return report;
        }, callback);
        return link;
    }

    private void start(Operation operation, Callback callback) {
        IO.execute(() -> {
            ScheduledFuture<?> deadline = TIMER.schedule(() -> {
                timedOut = true;
                closeResources();
            }, 90, TimeUnit.SECONDS);
            try {
                String report = operation.run();
                MAIN.post(() -> { if (!cancelled.get()) callback.complete(report); });
            } catch (Exception failure) {
                MAIN.post(() -> { if (!cancelled.get()) callback.failed(timedOut); });
            } finally {
                deadline.cancel(false);
                closeResources();
            }
        });
    }

    private void checkActive() throws IOException {
        if (cancelled.get() || timedOut) throw new IOException("Diagnostics transfer cancelled");
    }

    private synchronized void socket(BluetoothSocket value) throws IOException {
        try { checkActive(); } catch (IOException failure) { value.close(); throw failure; }
        socket = value;
    }

    private synchronized void server(BluetoothServerSocket value) throws IOException {
        try { checkActive(); } catch (IOException failure) { value.close(); throw failure; }
        server = value;
    }

    private synchronized void closeResources() {
        close(socket);
        close(server);
        socket = null;
        server = null;
    }

    @Override public void close() { cancelled.set(true); closeResources(); }
    private static void close(Closeable resource) {
        if (resource != null) try { resource.close(); } catch (IOException ignored) { /* Best effort. */ }
    }
}
