package io.benwiegand.atvremote.receiver.protocol;

/**
 * enum representation of the optional key event type extra.
 * <ul>
 *     <li>CLICK: simulates a simple button press.</li>
 *     <li>DOWN: simulates a key down event.</li>
 *     <li>UP: simulates a key up event.</li>
 * </ul>
 *
 * an input handler which only supports CLICK will try to simulate the desired behavior when DOWN
 * and UP are sent.
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
