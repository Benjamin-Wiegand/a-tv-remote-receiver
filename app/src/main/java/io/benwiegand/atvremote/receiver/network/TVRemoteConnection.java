package io.benwiegand.atvremote.receiver.network;

import static io.benwiegand.atvremote.receiver.network.SocketUtil.tryClose;
import static io.benwiegand.atvremote.receiver.protocol.ProtocolConstants.*;

import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;

import javax.net.ssl.SSLSocket;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.control.InputHandler;
import io.benwiegand.atvremote.receiver.protocol.AccessibilityContextNeeded;
import io.benwiegand.atvremote.receiver.protocol.PairingData;
import io.benwiegand.atvremote.receiver.protocol.PairingManager;
import io.benwiegand.atvremote.receiver.ui.NotificationOverlay;

public class TVRemoteConnection implements Closeable {
    private static final String TAG = TVRemoteConnection.class.getSimpleName();

    private static final int SOCKET_AUTH_TIMEOUT = 3000;
    private static final int PAIRING_TIME_LIMIT = 360000; // 5 mins //todo
    private static final long KEEPALIVE_INTERVAL = 5000;
    private static final long KEEPALIVE_TIMEOUT = KEEPALIVE_INTERVAL * 2;

    private final Thread thread = new Thread(this::run);
    private PairingData pairingData = null; //todo: update pairing data with pairing manager
    private boolean dead = false;

    private final PairingManager pairingManager;
    private final SSLSocket socket;
    private InputHandler inputHandler;
    private NotificationOverlay notificationOverlay;

    public TVRemoteConnection(PairingManager pairingManager, SSLSocket socket, InputHandler inputHandler, NotificationOverlay notificationOverlay) {
        this.pairingManager = pairingManager;
        this.socket = socket;
        this.inputHandler = inputHandler;
        this.notificationOverlay = notificationOverlay;

        thread.start();
    }

    public void setInputHandler(InputHandler inputHandler) {
        this.inputHandler = inputHandler;
    }

    public void setNotificationOverlay(NotificationOverlay notificationOverlay) {
        this.notificationOverlay = notificationOverlay;
    }

    public InetAddress getRemoteAddress() {
        return socket.getInetAddress();
    }

    public boolean isDead() {
        return dead;
    }

    private void run() {
        TCPReader reader = null;
        TCPWriter writer = null;
        try {
            Log.d(TAG, "Connection from " + socket.getRemoteSocketAddress());

            // init socket
            socket.setTcpNoDelay(true);
            socket.setTrafficClass(0x10 /* lowdelay */);
            socket.startHandshake();

            writer = TCPWriter.createFromStream(socket.getOutputStream(), CHARSET);
            reader = TCPReader.createFromStream(socket.getInputStream(), CHARSET);

            String version = reader.nextLine(SOCKET_AUTH_TIMEOUT);

            // check verison
            if (!VERSION_1.equals(version)) {
                writer.sendLine(OP_UNSUPPORTED);
                return;
            }
            writer.sendLine(OP_CONFIRM);

            String op = reader.nextLine(SOCKET_AUTH_TIMEOUT);

            if (op.equals(INIT_OP_PAIR)) {
                doPairing(writer, reader);
                return; // force a reconnection
            } else if (!op.equals(INIT_OP_CONNECT)) {
                throw new RuntimeException("Bad initial operation");
            }

            String auth = reader.nextLine(SOCKET_AUTH_TIMEOUT);
            pairingData = pairingManager.fetchPairingData(auth);
            if (pairingData == null) {
                Log.w(TAG, "client sent invalid authorization token");
                writer.sendLine(OP_UNAUTHORIZED);
                return;
            }
            assert auth.equals(pairingData.token());    // the token should match

            writer.sendLine(OP_CONFIRM);

            // connection is trusted at this point
            Log.i(TAG, "remote connected: " + socket.getRemoteSocketAddress());

            if (notificationOverlay != null)
                notificationOverlay.displayNotification(R.string.notification_remote_connected_title, socket.getRemoteSocketAddress().toString(), androidx.leanback.R.drawable.lb_ic_sad_cloud); //todo
            connectionLoop(writer, reader);

        } catch (SocketException e) {
            Log.e(TAG, "socket died", e);
        } catch (IOException e) {
            Log.e(TAG, "IOException in connection", e);
        } catch (RuntimeException e) {
            Log.e(TAG, "unexpected error in connection", e);
        } catch (InterruptedException e) {
            Log.d(TAG, "interrupted", e);
        } finally {
            // todo: cleanup callback
            tryClose(this);
            if (reader != null) tryClose(reader);
            if (writer != null) tryClose(writer);
        }
    }

    private void doPairing(TCPWriter writer, TCPReader reader) throws IOException, InterruptedException {
        Log.v(TAG, "starting pairing");
        Runnable cancelCallback = () -> tryClose(socket);

        // try to start pairing
        try {
            pairingManager.startPairing(cancelCallback);
        } catch (AccessibilityContextNeeded e) {
            writer.sendLine(OP_UNREADY);
            throw new RuntimeException("no accessibility context yet", e);
        }

        try {
            String line;
            do {
                writer.sendLine(OP_CONFIRM);
                writer.sendLine(OP_READY);
                line = reader.nextLine(KEEPALIVE_TIMEOUT);
                if (line == null) throw generateKeepaliveTimeoutException();
            } while (line.equals(OP_PING));

            int code;
            try {
                code = Integer.parseInt(line);
            } catch (NumberFormatException e) {
                throw new RuntimeException("received pairing code was not a number", e);
            }

            String token = pairingManager.pair(code, cancelCallback);

            if (token == null) {
                Log.w(TAG, "pairing code was wrong");
                writer.sendLine(OP_UNAUTHORIZED);
                if (notificationOverlay != null)
                    notificationOverlay.displayNotification(R.string.notification_pairing_failed_title, R.string.notification_pairing_failed_description_invalid_code, androidx.leanback.R.drawable.lb_ic_sad_cloud); //todo
                throw new RuntimeException("pairing code wrong");
            }

            Log.i(TAG, "pairing complete");
            writer.sendLine(token);
            if (notificationOverlay != null)
                notificationOverlay.displayNotification(R.string.notification_pairing_complete_title, R.string.notification_pairing_complete_description, androidx.leanback.R.drawable.lb_ic_sad_cloud); // todo
        } finally {
            pairingManager.cancelPairing(cancelCallback);
        }
    }

    private IOException generateKeepaliveTimeoutException() {
        return new IOException("didn't receive anything within KEEPALIVE_TIMEOUT (" + KEEPALIVE_TIMEOUT + ")");
    }

    private void connectionLoop(TCPWriter writer, TCPReader reader) throws IOException, InterruptedException {
        while (!dead) {

            // enter unready state until an input handler exists
            while (inputHandler == null) {
                writer.sendLine(OP_UNREADY);
                String line = reader.nextLine(KEEPALIVE_TIMEOUT);  // polling on an interval

                // handle keepalive
                if (line == null) throw generateKeepaliveTimeoutException();
                else if (line.equals(OP_PING)) writer.sendLine(OP_CONFIRM);
                else writer.sendLine(OP_ERR);
            }

            // wait for and execute next operation
            writer.sendLine(OP_READY);
            String line = reader.nextLine(KEEPALIVE_TIMEOUT);

            // handle keepalive
            if (line == null) throw generateKeepaliveTimeoutException();
            String[] opLine = line.split(" ");

            switch (opLine[0]) {
                case OP_DPAD_UP -> inputHandler.dpadUp();
                case OP_DPAD_DOWN -> inputHandler.dpadDown();
                case OP_DPAD_LEFT -> inputHandler.dpadLeft();
                case OP_DPAD_RIGHT -> inputHandler.dpadRight();
                case OP_DPAD_SELECT -> inputHandler.dpadSelect();
                case OP_DPAD_LONG_PRESS -> inputHandler.dpadLongPress();

                case OP_NAV_HOME -> inputHandler.navHome();
                case OP_NAV_BACK -> inputHandler.navBack();
                case OP_NAV_RECENT -> inputHandler.navRecent();
                case OP_NAV_APPS -> inputHandler.navApps();
                case OP_NAV_NOTIFICATIONS -> inputHandler.navNotifications();
                case OP_NAV_QUICK_SETTINGS -> inputHandler.navQuickSettings();

                case OP_VOLUME_UP -> inputHandler.volumeUp();
                case OP_VOLUME_DOWN -> inputHandler.volumeDown();
                case OP_MUTE -> inputHandler.mute();

                case OP_PAUSE -> inputHandler.pause();
                case OP_NEXT_TRACK -> inputHandler.nextTrack();
                case OP_PREV_TRACK -> inputHandler.prevTrack();
                case OP_SKIP_BACKWARD -> inputHandler.skipBackward();
                case OP_SKIP_FORWARD -> inputHandler.skipForward();

                case OP_CURSOR_SHOW -> inputHandler.showCursor();
                case OP_CURSOR_HIDE -> inputHandler.hideCursor();
                case OP_CURSOR_MOVE -> {
                    if (opLine.length != 3) {
                        writer.sendLine(OP_ERR);
                        continue;
                    }
                    int x, y;
                    try {
                        x = Integer.parseInt(opLine[1]);
                        y = Integer.parseInt(opLine[2]);
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "malformed mouse coordinate", e);
                        writer.sendLine(OP_ERR);
                        continue;
                    }
                    inputHandler.cursorMove(x, y);
                }
                case OP_CURSOR_DOWN -> inputHandler.cursorDown();
                case OP_CURSOR_UP -> inputHandler.cursorUp();

                case OP_PING -> {}
                default -> {
                    writer.sendLine(OP_UNSUPPORTED);
                    continue;
                }
            }

            writer.sendLine(OP_CONFIRM);

        }

    }

    @Override
    public void close() {
        Log.d(TAG, "close()");
        dead = true;
        tryClose(socket);
        thread.interrupt();
    }

}
