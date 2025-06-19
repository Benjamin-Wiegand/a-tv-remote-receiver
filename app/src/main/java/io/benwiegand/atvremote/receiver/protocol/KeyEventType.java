package io.benwiegand.atvremote.receiver.protocol;

/**
 * enum representation of the optional key event type extra.
 * <ul>
 *     <li>CLICK: simulates a simple button press.</li>
 *     <li>DOWN: simulates a key down event. this will still be handled if the input handler only supports CLICK.</li>
 *     <li>UP: simulates a key up event. this may be ignored if the input handler only supports CLICK.</li>
 * </ul>
 */
public enum KeyEventType {
    CLICK,
    DOWN,
    UP;

    public static KeyEventType parse(String str) {
        if (str == null) return CLICK; // default to a normal click
        for (KeyEventType type : values()) {
            if (type.name().equals(str)) return type;
        }
        return CLICK;
    }
}
