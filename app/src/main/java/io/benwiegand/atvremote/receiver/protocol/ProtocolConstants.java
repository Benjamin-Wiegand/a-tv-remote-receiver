package io.benwiegand.atvremote.receiver.protocol;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class ProtocolConstants {
    // a less barbaric yet still simple protocol that supports concurrent events both ways

    public static final String NEWLINE = "\n";
    public static final Charset CHARSET = StandardCharsets.UTF_8;

    // version
    public static final String VERSION_1 = "v1";

    // responses
    public static final String OP_CONFIRM = "OK";
    public static final String OP_ERR = "ERR";
    public static final String OP_UNAUTHORIZED = "BAD_AUTH";
    public static final String OP_UNSUPPORTED = "HUH?";

    // init operations
    public static final String INIT_OP_PAIR = "PAIR";
    public static final String INIT_OP_CONNECT = "CONN";

    // global operations
    public static final String OP_PING = "PING";

    // pairing operations
    public static final String OP_TRY_PAIRING_CODE = "IS_THIS_YOUR_CARD?";

    // remote control operations
    public static final String OP_DPAD_UP = "DPAD_UP";
    public static final String OP_DPAD_DOWN = "DPAD_DOWN";
    public static final String OP_DPAD_LEFT = "DPAD_LEFT";
    public static final String OP_DPAD_RIGHT = "DPAD_RIGHT";
    public static final String OP_DPAD_SELECT = "DPAD_SELECT";
    public static final String OP_DPAD_LONG_PRESS = "DPAD_HOLD";

    public static final String OP_NAV_HOME = "NAV_HOME";
    public static final String OP_NAV_BACK = "NAV_BACK";
    public static final String OP_NAV_RECENT = "NAV_RECENT";
    public static final String OP_NAV_APPS = "NAV_APPS";
    public static final String OP_NAV_NOTIFICATIONS = "NAV_NOTIFICATIONS";
    public static final String OP_NAV_QUICK_SETTINGS = "NAV_QUICK_SETTINGS";

    public static final String OP_VOLUME_UP = "VOL_UP";
    public static final String OP_VOLUME_DOWN = "VOL_DOWN";
    public static final String OP_MUTE = "MUTE";

    public static final String OP_PAUSE = "PAUSE";
    public static final String OP_NEXT_TRACK = "NEXT_TRACK";
    public static final String OP_PREV_TRACK = "PREV_TRACK";
    public static final String OP_SKIP_BACKWARD = "SKIP_BACKWARD";
    public static final String OP_SKIP_FORWARD = "SKIP_FORWARD";

    public static final String OP_CURSOR_SHOW = "CURSOR_SHOW";
    public static final String OP_CURSOR_HIDE = "CURSOR_HIDE";
    public static final String OP_CURSOR_MOVE = "CURSOR_MOVE";
    public static final String OP_CURSOR_DOWN = "CURSOR_DOWN";
    public static final String OP_CURSOR_UP = "CURSOR_UP";

    public static final String MDNS_SERVICE_TYPE = "_atv_remote_receiver_bw._tcp";

}
