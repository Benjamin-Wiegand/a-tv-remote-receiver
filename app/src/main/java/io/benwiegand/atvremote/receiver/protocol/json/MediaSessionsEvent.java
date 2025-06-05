package io.benwiegand.atvremote.receiver.protocol.json;

public record MediaSessionsEvent(
        String[] activeSessionIds
) {
}
