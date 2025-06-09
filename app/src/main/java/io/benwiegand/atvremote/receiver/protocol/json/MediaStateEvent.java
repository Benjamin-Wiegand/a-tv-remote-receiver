package io.benwiegand.atvremote.receiver.protocol.json;

public record MediaStateEvent(
        String id,
        Integer state,
        Boolean playing,
        Boolean paused
) {
    // no media
    public MediaStateEvent(String id) {
        this(id, null, null, null);
    }
}
