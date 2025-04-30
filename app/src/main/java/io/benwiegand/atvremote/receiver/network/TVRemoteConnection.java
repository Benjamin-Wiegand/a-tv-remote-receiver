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
import io.benwiegand.atvremote.receiver.control.ControlScheme;
import io.benwiegand.atvremote.receiver.control.ControlNotInitializedException;
import io.benwiegand.atvremote.receiver.protocol.PairingData;
import io.benwiegand.atvremote.receiver.protocol.PairingManager;

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
    private final ControlScheme controlScheme;

    public TVRemoteConnection(PairingManager pairingManager, SSLSocket socket, ControlScheme controlScheme) {
        this.pairingManager = pairingManager;
        this.socket = socket;
        this.controlScheme = controlScheme;

        thread.start();
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

            controlScheme.getOverlayOutputOptional().ifPresent(o ->
                    o.displayNotification(R.string.notification_remote_connected_title, socket.getRemoteSocketAddress().toString(), androidx.leanback.R.drawable.lb_ic_sad_cloud)); // todo

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
        } catch (ControlNotInitializedException e) {
            writer.sendLine(OP_UNREADY);
            throw new RuntimeException("cannot display pairing overlay", e);
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
                controlScheme.getOverlayOutputOptional().ifPresent(o ->
                        o.displayNotification(R.string.notification_pairing_failed_title, R.string.notification_pairing_failed_description_invalid_code, androidx.leanback.R.drawable.lb_ic_sad_cloud)); //todo
                throw new RuntimeException("pairing code wrong");
            }

            Log.i(TAG, "pairing complete");
            writer.sendLine(token);
            controlScheme.getOverlayOutputOptional().ifPresent(o ->
                    o.displayNotification(R.string.notification_pairing_complete_title, R.string.notification_pairing_complete_description, androidx.leanback.R.drawable.lb_ic_sad_cloud)); // todo
        } finally {
            pairingManager.cancelPairing(cancelCallback);
        }
    }

    private IOException generateKeepaliveTimeoutException() {
        return new IOException("didn't receive anything within KEEPALIVE_TIMEOUT (" + KEEPALIVE_TIMEOUT + ")");
    }

    private void connectionLoop(TCPWriter writer, TCPReader reader) throws IOException, InterruptedException {
        while (!dead) {

            // wait for and execute next operation
            writer.sendLine(OP_READY);
            String line = reader.nextLine(KEEPALIVE_TIMEOUT);

            // handle keepalive
            if (line == null) throw generateKeepaliveTimeoutException();
            String[] opLine = line.split(" ");

            try {
                switch (opLine[0]) {
                    case OP_DPAD_UP -> controlScheme.getDirectionalPadInput().dpadUp();
                    case OP_DPAD_DOWN -> controlScheme.getDirectionalPadInput().dpadDown();
                    case OP_DPAD_LEFT -> controlScheme.getDirectionalPadInput().dpadLeft();
                    case OP_DPAD_RIGHT -> controlScheme.getDirectionalPadInput().dpadRight();
                    case OP_DPAD_SELECT -> controlScheme.getDirectionalPadInput().dpadSelect();
                    case OP_DPAD_LONG_PRESS -> controlScheme.getDirectionalPadInput().dpadLongPress();

                    case OP_NAV_HOME -> controlScheme.getNavigationInput().navHome();
                    case OP_NAV_BACK -> controlScheme.getNavigationInput().navBack();
                    case OP_NAV_RECENT -> controlScheme.getNavigationInput().navRecent();
                    case OP_NAV_APPS -> controlScheme.getNavigationInput().navApps();
                    case OP_NAV_NOTIFICATIONS -> controlScheme.getNavigationInput().navNotifications();
                    case OP_NAV_QUICK_SETTINGS -> controlScheme.getNavigationInput().navQuickSettings();

                    case OP_VOLUME_UP -> controlScheme.getVolumeInput().volumeUp();
                    case OP_VOLUME_DOWN -> controlScheme.getVolumeInput().volumeDown();
                    case OP_MUTE -> controlScheme.getVolumeInput().toggleMute();

                    case OP_PAUSE -> controlScheme.getMediaInput().pause();
                    case OP_NEXT_TRACK -> controlScheme.getMediaInput().nextTrack();
                    case OP_PREV_TRACK -> controlScheme.getMediaInput().prevTrack();
                    case OP_SKIP_BACKWARD -> controlScheme.getMediaInput().skipBackward();
                    case OP_SKIP_FORWARD -> controlScheme.getMediaInput().skipForward();

                    case OP_CURSOR_SHOW -> controlScheme.getCursorInput().showCursor();
                    case OP_CURSOR_HIDE -> controlScheme.getCursorInput().hideCursor();
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
                        controlScheme.getCursorInput().cursorMove(x, y);
                    }
                    case OP_CURSOR_DOWN -> controlScheme.getCursorInput().cursorDown();
                    case OP_CURSOR_UP -> controlScheme.getCursorInput().cursorUp();

                    case OP_PING -> {
                    }
                    default -> {
                        writer.sendLine(OP_UNSUPPORTED);
                        continue;
                    }
                }

                // no exceptions happened, assume success
                writer.sendLine(OP_CONFIRM);

            } catch (ControlNotInitializedException e) {
                // todo: error messages still need implementation
                writer.sendLine(OP_ERR);
            }
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
