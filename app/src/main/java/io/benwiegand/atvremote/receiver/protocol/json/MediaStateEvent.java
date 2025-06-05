package io.benwiegand.atvremote.receiver.protocol.json;

public record MediaStateEvent(
        String id,
        int state,
        boolean playing
) { }
