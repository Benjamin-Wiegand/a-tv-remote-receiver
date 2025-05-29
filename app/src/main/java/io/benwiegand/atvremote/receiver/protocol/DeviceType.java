package io.benwiegand.atvremote.receiver.protocol;

import androidx.annotation.DrawableRes;

import io.benwiegand.atvremote.receiver.R;

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

    @DrawableRes
    public int toDrawable() {
        // everything is a phone for now
        return switch(this) {
            case UNKNOWN -> R.drawable.phone;
            case PHONE -> R.drawable.phone;
            case TABLET -> R.drawable.phone;
            case COMPUTER -> R.drawable.phone;
        };
    }
}
