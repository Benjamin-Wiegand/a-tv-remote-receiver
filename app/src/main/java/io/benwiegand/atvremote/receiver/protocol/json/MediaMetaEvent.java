package io.benwiegand.atvremote.receiver.protocol.json;

public record MediaMetaEvent(
        String id,
        String title,
        String subtitle,
        String sourceName,
        Long length
) {
    // no media
    public MediaMetaEvent(String id, String sourceName) {
        this(id, null, null, sourceName, null);
    }
}
