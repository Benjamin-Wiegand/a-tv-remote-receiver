package io.benwiegand.atvremote.receiver.protocol.json;

import android.content.Context;

import io.benwiegand.atvremote.receiver.control.ControlScheme;

public record ReceiverDeviceMeta(
    ReceiverCapabilities capabilities
) {

    public static ReceiverDeviceMeta getDeviceMeta(Context context, ControlScheme controlScheme) {
        return new ReceiverDeviceMeta(ReceiverCapabilities.getCapabilities(context, controlScheme));
    }

}
