package io.benwiegand.atvremote.receiver.protocol.json;

public record MediaPositionEvent(
        String id,
        Long position,
        Long bufferedPosition
) {
    public MediaPositionEvent(String id) {
        this(id, null, null);
    }
}
