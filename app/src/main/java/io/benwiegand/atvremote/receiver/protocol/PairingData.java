package io.benwiegand.atvremote.receiver.protocol;

import android.content.SharedPreferences;

import java.time.Instant;

import io.benwiegand.atvremote.receiver.protocol.json.RemoteDeviceMeta;

public record PairingData(String token, String friendlyName, String lastConnectedIpAddress, long lastConnectedTimestamp, int deviceType) {

    public Instant lastConnectedInstant() {
        if (lastConnectedTimestamp() < 0) return null;
        return Instant.ofEpochSecond(lastConnectedTimestamp());
    }

    public DeviceType deviceTypeEnum() {
        return DeviceType.fromInt(deviceType());
    }

    public PairingData updateLastConnection(String ipAddress, long timestamp) {
        return new PairingData(token, friendlyName, ipAddress, timestamp, deviceType);
    }

    public PairingData updateDeviceMeta(RemoteDeviceMeta deviceMeta) {
        return new PairingData(token, deviceMeta.friendlyName(), lastConnectedIpAddress, lastConnectedTimestamp, deviceMeta.type());
    }

    // for shared preferences
    public static final String KEY_TOKEN = "token";
    public static final String KEY_FRIENDLY_NAME = "name";
    public static final String KEY_LAST_CONNECTED_IP_ADDRESS = "addr";
    public static final String KEY_LAST_CONNECTED_TIMESTAMP = "last_connected";
    public static final String KEY_DEVICE_TYPE = "type";

    public static PairingData readFromPreferences(SharedPreferences sp) {
        // token is required
        String token = sp.getString(KEY_TOKEN, null);
        if (token == null) return null;

        // there are better ways of doing this. reflection is one of them. last time I tried
        // reflection in an AOSP build it broke. this can always be replaced with a better solution.
        // if you are reading this and have a better solution, please contribute it I would really appreciate it.
        return new PairingData(
                token,
                sp.getString(KEY_FRIENDLY_NAME, null),
                sp.getString(KEY_LAST_CONNECTED_IP_ADDRESS, null),
                sp.getLong(KEY_LAST_CONNECTED_TIMESTAMP, -1),
                sp.getInt(KEY_DEVICE_TYPE, -1)
        );
    }

    public boolean writeToPreferences(SharedPreferences.Editor spe) {
        return spe.clear()
                .putString(KEY_TOKEN, token())
                .putString(KEY_FRIENDLY_NAME, friendlyName())
                .putString(KEY_LAST_CONNECTED_IP_ADDRESS, lastConnectedIpAddress())
                .putLong(KEY_LAST_CONNECTED_TIMESTAMP, lastConnectedTimestamp())
                .putInt(KEY_DEVICE_TYPE, deviceType())
                .commit();
    }
}
