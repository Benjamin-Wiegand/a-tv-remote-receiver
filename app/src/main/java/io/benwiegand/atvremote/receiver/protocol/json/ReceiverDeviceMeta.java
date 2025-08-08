package io.benwiegand.atvremote.receiver.protocol.json;

import android.content.Context;

import io.benwiegand.atvremote.receiver.compatibility.CompatibilityManager;

public record ReceiverDeviceMeta(
    ReceiverCapabilities capabilities
) {

    public static ReceiverDeviceMeta getDeviceMeta(Context context) {
        CompatibilityManager compatibilityManager = new CompatibilityManager(context);
        return new ReceiverDeviceMeta(compatibilityManager.generateCapabilities());
    }

}
