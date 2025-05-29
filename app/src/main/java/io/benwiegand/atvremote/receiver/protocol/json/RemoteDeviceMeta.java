package io.benwiegand.atvremote.receiver.protocol.json;

public record RemoteDeviceMeta(
        int type,
        String friendlyName
) { }
