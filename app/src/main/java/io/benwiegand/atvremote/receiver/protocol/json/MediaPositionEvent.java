package io.benwiegand.atvremote.receiver.protocol.json;

public record MediaPositionEvent(
        String id,
        long position
) { }
