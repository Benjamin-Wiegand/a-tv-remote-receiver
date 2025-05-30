package io.benwiegand.atvremote.receiver.network;

import static io.benwiegand.atvremote.receiver.control.IntentConstants.LINEAGE_SYSTEM_OPTIONS_ACTIVITY;
import static io.benwiegand.atvremote.receiver.network.SocketUtil.tryClose;
import static io.benwiegand.atvremote.receiver.protocol.ProtocolConstants.*;
import static io.benwiegand.atvremote.receiver.protocol.json.ReceiverCapabilities.EXTRA_BUTTON_GTV_DASHBOARD;
import static io.benwiegand.atvremote.receiver.protocol.json.ReceiverCapabilities.EXTRA_BUTTON_LINEAGE_SYSTEM_OPTIONS;

import android.content.Context;
import android.util.Log;

import androidx.annotation.StringRes;

import com.google.gson.Gson;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.text.MessageFormat;
import java.time.Instant;

import javax.net.ssl.SSLSocket;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.control.ControlScheme;
import io.benwiegand.atvremote.receiver.control.ControlNotInitializedException;
import io.benwiegand.atvremote.receiver.protocol.MalformedEventException;
import io.benwiegand.atvremote.receiver.protocol.OperationDefinition;
import io.benwiegand.atvremote.receiver.protocol.PairingData;
import io.benwiegand.atvremote.receiver.protocol.PairingManager;
import io.benwiegand.atvremote.receiver.protocol.RemoteProtocolException;
import io.benwiegand.atvremote.receiver.protocol.json.ErrorDetails;
import io.benwiegand.atvremote.receiver.protocol.json.ReceiverDeviceMeta;
import io.benwiegand.atvremote.receiver.protocol.json.RemoteDeviceMeta;

public class TVRemoteConnection implements Closeable {
    private static final String TAG = TVRemoteConnection.class.getSimpleName();
    private static final Gson gson = new Gson();

    private static final int SOCKET_AUTH_TIMEOUT = 3000;
    private static final int PAIRING_TIME_LIMIT = 360000; // 5 mins //todo
    private static final long KEEPALIVE_INTERVAL = 5000;
    private static final long KEEPALIVE_TIMEOUT = KEEPALIVE_INTERVAL * 2;

    private final Context context;

    private final SSLSocket socket;
    private TCPReader reader = null;
    private TCPWriter writer = null;
    private EventJuggler eventJuggler = null;

    private final PairingManager pairingManager;
    private Runnable cancelPairingCallback = null;
    private PairingData pairingData = null;
    private final ControlScheme controlScheme;

    private boolean dead = false;

    public TVRemoteConnection(Context context, PairingManager pairingManager, SSLSocket socket, ControlScheme controlScheme) {
        this.context = context;
        this.pairingManager = pairingManager;
        this.socket = socket;
        this.controlScheme = controlScheme;
        init();
    }

    public InetAddress getRemoteAddress() {
        return socket.getInetAddress();
    }

    public boolean isDead() {
        return dead;
    }

    private void init() {
        try {
            Log.d(TAG, "Connection from " + socket.getRemoteSocketAddress());

            // init socket
            socket.setTrafficClass(0x10 /* lowdelay */);
            socket.startHandshake();

            writer = TCPWriter.createFromStream(socket.getOutputStream(), CHARSET);
            reader = TCPReader.createFromStream(socket.getInputStream(), CHARSET);
            eventJuggler = new EventJuggler(context, socket, reader, writer, this::onSocketDeath, KEEPALIVE_INTERVAL, KEEPALIVE_TIMEOUT);

            String version = reader.nextLine(SOCKET_AUTH_TIMEOUT);

            // check verison
            if (!VERSION_1.equals(version)) {
                writer.sendLine(OP_UNSUPPORTED);
                return;
            }
            writer.sendLine(OP_CONFIRM);

            String op = reader.nextLine(SOCKET_AUTH_TIMEOUT);
            switch (op) {
                case INIT_OP_PAIR -> initPairing();
                case INIT_OP_CONNECT -> initRemote();
                default -> throw new RuntimeException("Bad initial operation");
            }

        } catch (InterruptedException e) {
            Log.e(TAG, "interrupted");
            tryClose(this);
        } catch (Throwable t) {
            Log.e(TAG, "error during connection init", t);
            tryClose(this);
        }
    }

    private void onSocketDeath(Throwable throwable) {
        tryClose(this);

        controlScheme.getOverlayOutputOptional().ifPresent(o ->
                o.displayNotification(
                        MessageFormat.format(
                                context.getString(R.string.notification_remote_disconnected_title_format),
                                getRemoteFriendlyName()),
                        R.string.notification_remote_disconnected_description,
                        R.drawable.denied));
    }

    private void initPairing() throws IOException, InterruptedException {
        Log.v(TAG, "starting pairing");

        cancelPairingCallback = () -> {
            if (socket.isClosed()) return;
            controlScheme.getOverlayOutputOptional().ifPresent(o ->
                    o.displayNotification(R.string.notification_pairing_failed_title, R.string.notification_pairing_failed_description_cancelled, R.drawable.denied));
            tryClose(socket);
        };

        try {
            pairingManager.startPairing(cancelPairingCallback);
        } catch (ControlNotInitializedException e) {
            sendError(writer, ErrorDetails.fromException(context, e));
            throw new RuntimeException("cannot display pairing overlay", e);
        }

        writer.sendLine(OP_CONFIRM);

        eventJuggler.start(getPairingOperations());

    }

    private void initRemote() throws IOException, InterruptedException {
        String auth = reader.nextLine(SOCKET_AUTH_TIMEOUT);
        pairingData = pairingManager.fetchPairingData(auth);
        if (pairingData == null) {
            Log.w(TAG, "client sent invalid authorization token");
            writer.sendLine(OP_UNAUTHORIZED);
            return;
        }

        // the token should already match, but it doesn't hurt to check, even in release builds
        if (!auth.equals(pairingData.token()))
            throw new AssertionError("PairingData doesn't match provided auth token!");

        writer.sendLine(OP_CONFIRM);

        // connection is trusted at this point
        Log.i(TAG, "remote connected: " + socket.getRemoteSocketAddress());

        exchangeMeta();
        pairingData.updateLastConnection(socket.getInetAddress().getHostAddress(), Instant.now().getEpochSecond());
        commitPairingMetaDiscardResult();

        controlScheme.getOverlayOutputOptional().ifPresent(o ->
                o.displayNotification(
                        MessageFormat.format(
                                context.getString(R.string.notification_remote_connected_title_format),
                                getRemoteFriendlyName()),
                        R.string.notification_remote_connected_description,
                        pairingData.deviceTypeEnum().toDrawable()));

        eventJuggler.start(getRemoteOperations());
    }

    private String getRemoteFriendlyName() {
        if (pairingData == null) return socket.getRemoteSocketAddress().toString();
        String friendlyName = pairingData.friendlyName();
        if (friendlyName == null) return socket.getRemoteSocketAddress().toString();
        return friendlyName;
    }

    private void exchangeMeta() throws IOException, InterruptedException {
        writer.sendLine(OP_META + " " + gson.toJson(ReceiverDeviceMeta.getDeviceMeta(context, controlScheme)));

        String line = reader.nextLine(SOCKET_AUTH_TIMEOUT);
        if (line == null) {
            Log.w(TAG, "metadata fetch timed out");
            return;
        }

        String[] opLine = line.split(" ", 2);

        if (opLine[0].equals(OP_META) && opLine.length == 2) {
            RemoteDeviceMeta meta = gson.fromJson(opLine[1], RemoteDeviceMeta.class);

            Log.v(TAG, "got metadata: " + meta);
            pairingData = pairingData.updateDeviceMeta(meta);
            pairingManager.updatePairingData(pairingData);
        } else if (opLine[0].equals("!" + OP_META)) {
            Log.v(TAG, "no meta");
        } else {
            Log.w(TAG, "invalid metadata response: " + line);
        }
    }

    private void commitPairingMetaDiscardResult() {
        new Thread(() -> {
            if (!pairingManager.updatePairingData(pairingData)) {
                Log.w(TAG, "failed to update pairing data");
            }
        }).start();
    }

    private void protocolAssert(boolean condition, @StringRes int stringRes, String message) throws RemoteProtocolException {
        if (condition) return;
        throw new RemoteProtocolException(stringRes, message);
    }

    private void sendError(TCPWriter writer, ErrorDetails e) throws IOException {
        writer.sendLine(OP_ERR + " " + gson.toJson(e));
    }

    @Override
    public void close() {
        Log.d(TAG, "close()");
        dead = true;

        // todo: cleanup callback
        tryClose(socket);

        if (eventJuggler != null && !eventJuggler.isDead()) {
            eventJuggler.close();
        } else if (eventJuggler == null) {
            if (reader != null) tryClose(reader);
            if (writer != null) tryClose(writer);
        }
        if (cancelPairingCallback != null) pairingManager.cancelPairing(cancelPairingCallback);
    }

    private OperationDefinition[] getPairingOperations() {
        return new OperationDefinition[] {
                new OperationDefinition(OP_TRY_PAIRING_CODE, extra -> {
                    int code;
                    try {
                        code = Integer.parseInt(extra);
                    } catch (NumberFormatException e) {
                        throw new MalformedEventException("received pairing code was not a number", e);
                    }

                    String token = pairingManager.pair(code, cancelPairingCallback);

                    if (token == null) {
                        Log.w(TAG, "pairing code was wrong");
                        controlScheme.getOverlayOutputOptional().ifPresent(o ->
                                o.displayNotification(R.string.notification_pairing_failed_title, R.string.notification_pairing_failed_description_invalid_code, R.drawable.denied));
                        throw new RemoteProtocolException(R.string.protocol_error_pairing_code_invalid, "pairing code wrong");
                    }

                    Log.i(TAG, "pairing complete");
                    controlScheme.getOverlayOutputOptional().ifPresent(o ->
                            o.displayNotification(R.string.notification_pairing_complete_title, R.string.notification_pairing_complete_description, R.drawable.accepted));
                    return token;
                }, true),
                new OperationDefinition(OP_PING, () -> {}),
        };
    }

    private OperationDefinition[] getRemoteOperations() {
        return new OperationDefinition[] {
                new OperationDefinition(OP_DPAD_UP, () -> controlScheme.getDirectionalPadInput().dpadUp()),
                new OperationDefinition(OP_DPAD_DOWN, () -> controlScheme.getDirectionalPadInput().dpadDown()),
                new OperationDefinition(OP_DPAD_LEFT, () -> controlScheme.getDirectionalPadInput().dpadLeft()),
                new OperationDefinition(OP_DPAD_RIGHT, () -> controlScheme.getDirectionalPadInput().dpadRight()),
                new OperationDefinition(OP_DPAD_SELECT, () -> controlScheme.getDirectionalPadInput().dpadSelect()),
                new OperationDefinition(OP_DPAD_LONG_PRESS, () -> controlScheme.getDirectionalPadInput().dpadLongPress()),

                new OperationDefinition(OP_NAV_HOME, () -> controlScheme.getNavigationInput().navHome()),
                new OperationDefinition(OP_NAV_BACK, () -> controlScheme.getNavigationInput().navBack()),
                new OperationDefinition(OP_NAV_RECENT, () -> controlScheme.getNavigationInput().navRecent()),
                new OperationDefinition(OP_NAV_APPS, () -> controlScheme.getNavigationInput().navApps()),
                new OperationDefinition(OP_NAV_NOTIFICATIONS, () -> controlScheme.getNavigationInput().navNotifications()),
                new OperationDefinition(OP_NAV_QUICK_SETTINGS, () -> controlScheme.getNavigationInput().navQuickSettings()),

                new OperationDefinition(OP_VOLUME_UP, () -> controlScheme.getVolumeInput().volumeUp()),
                new OperationDefinition(OP_VOLUME_DOWN, () -> controlScheme.getVolumeInput().volumeDown()),
                new OperationDefinition(OP_MUTE, () -> controlScheme.getVolumeInput().toggleMute()),

                new OperationDefinition(OP_PAUSE, () -> controlScheme.getMediaInput().playPause()),
                new OperationDefinition(OP_NEXT_TRACK, () -> controlScheme.getMediaInput().nextTrack()),
                new OperationDefinition(OP_PREV_TRACK, () -> controlScheme.getMediaInput().prevTrack()),
                new OperationDefinition(OP_SKIP_BACKWARD, () -> controlScheme.getMediaInput().skipBackward()),
                new OperationDefinition(OP_SKIP_FORWARD, () -> controlScheme.getMediaInput().skipForward()),

                new OperationDefinition(OP_CURSOR_SHOW, () -> controlScheme.getCursorInput().showCursor()),
                new OperationDefinition(OP_CURSOR_HIDE, () -> controlScheme.getCursorInput().hideCursor()),
                new OperationDefinition(OP_CURSOR_MOVE, extra -> {
                    protocolAssert(extra != null, R.string.protocol_error_mouse_move_bad_coordinates, "no mouse coordinates provided");
                    int iSep = extra.indexOf(' ');
                    protocolAssert(iSep > 0, R.string.protocol_error_mouse_move_bad_coordinates, "not enough mouse coordinates were provided");

                    int x, y;
                    try {
                        x = Integer.parseInt(extra.substring(0, iSep));
                        y = Integer.parseInt(extra.substring(iSep + 1));
                    } catch (NumberFormatException e) {
                        throw new RemoteProtocolException(R.string.protocol_error_mouse_move_bad_coordinates, "one or more mouse coordinates were not integers", e);
                    }

                    controlScheme.getCursorInput().cursorMove(x, y);
                }),
                new OperationDefinition(OP_CURSOR_DOWN, () -> controlScheme.getCursorInput().cursorDown()),
                new OperationDefinition(OP_CURSOR_UP, () -> controlScheme.getCursorInput().cursorUp()),

                new OperationDefinition(OP_EXTRA_BUTTON, extra -> {
                    switch (extra) {
                        case EXTRA_BUTTON_GTV_DASHBOARD -> controlScheme.getNavigationInput().navNotifications();
                        case EXTRA_BUTTON_LINEAGE_SYSTEM_OPTIONS -> controlScheme.getActivityLauncherInput().launchActivity(LINEAGE_SYSTEM_OPTIONS_ACTIVITY);
                        default -> throw new RemoteProtocolException(R.string.protocol_error_extra_button_no_such_button, "no such button: " + extra);
                    }
                }),

                new OperationDefinition(OP_PING, () -> {}),
        };
    }

}
