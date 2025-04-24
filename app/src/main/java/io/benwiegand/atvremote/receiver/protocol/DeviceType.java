package io.benwiegand.atvremote.receiver.protocol;

public enum DeviceType {
    UNKNOWN,
    PHONE,
    TABLET,
    COMPUTER;

    public static DeviceType fromInt(int type) {
        if (type < 0) return UNKNOWN;
        DeviceType[] types = values();
        if (type >= types.length) return UNKNOWN;
        return types[type];
    }

    // for naming consistency sake
    public int toInt() {
        return ordinal();
    }
}
