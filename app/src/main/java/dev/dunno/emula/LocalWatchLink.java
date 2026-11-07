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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Secure, bonded-device RFCOMM only. No discovery, Internet, relay or background service. */
@SuppressLint("MissingPermission") // Activity entry points request CONNECT; revocation fails the operation.
final class LocalWatchLink implements AutoCloseable {
    private static final UUID SERVICE = UUID.fromString("a5945010-4886-4ef4-92ac-6af49ef40174");
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile boolean timedOut;
    private BluetoothSocket socket;
    private BluetoothServerSocket server;

    interface Receiver {
        WatchCardPacket.Result save(List<WatchCardPacket.Entry> cards) throws Exception;
    }

    interface Callback {
        void complete(WatchCardPacket.Result result);
        void failed(boolean timeout);
    }

    private interface Operation { WatchCardPacket.Result run() throws Exception; }

    static LocalWatchLink send(BluetoothDevice device, byte[] packet, int count, Callback callback) {
        LocalWatchLink link = new LocalWatchLink();
        link.start(() -> {
            BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SERVICE);
            link.socket(socket);
            socket.connect();
            WatchCardPacket.checkHello(socket.getInputStream());
            socket.getOutputStream().write(packet);
            socket.getOutputStream().flush();
            return WatchCardPacket.readReply(socket.getInputStream(), count);
        }, callback);
        return link;
    }

    static LocalWatchLink receive(BluetoothAdapter adapter, Receiver receiver, Callback callback) {
        LocalWatchLink link = new LocalWatchLink();
        link.start(() -> {
            BluetoothServerSocket server = adapter.listenUsingRfcommWithServiceRecord("Emula cards", SERVICE);
            link.server(server);
            BluetoothSocket socket = server.accept();
            link.socket(socket);
            server.close();
            if (socket.getRemoteDevice().getBondState() != BluetoothDevice.BOND_BONDED) {
                throw new IOException("Pair the devices before transferring cards");
            }
            WatchCardPacket.hello(socket.getOutputStream());
            List<WatchCardPacket.Entry> cards = WatchCardPacket.read(socket.getInputStream());
            if (link.cancelled.get() || link.timedOut) throw new IOException("Transfer cancelled");
            WatchCardPacket.Result result = receiver.save(cards);
            WatchCardPacket.reply(socket.getOutputStream(), result);
            return result;
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
                WatchCardPacket.Result result = operation.run();
                MAIN.post(() -> { if (!cancelled.get()) callback.complete(result); });
            } catch (Exception failure) {
                MAIN.post(() -> { if (!cancelled.get()) callback.failed(timedOut); });
            } finally {
                deadline.cancel(false);
                closeResources();
            }
        });
    }

    private synchronized void socket(BluetoothSocket value) throws IOException {
        if (cancelled.get() || timedOut) { value.close(); throw new IOException("Transfer cancelled"); }
        socket = value;
    }

    private synchronized void server(BluetoothServerSocket value) throws IOException {
        if (cancelled.get() || timedOut) { value.close(); throw new IOException("Transfer cancelled"); }
        server = value;
    }

    private synchronized void closeResources() {
        close(socket);
        close(server);
        socket = null;
        server = null;
    }

    @Override public void close() { cancelled.set(true); closeResources(); }

    private static void close(Closeable value) {
        if (value != null) try { value.close(); } catch (IOException ignored) { /* Best effort. */ }
    }
}
