package io.benwiegand.atvremote.receiver.protocol.json;

public record MediaMetaEvent(
        String id,
        String title,
        String subtitle,
        String sourceName,
        long length
) { }
