package io.benwiegand.atvremote.receiver.protocol.stream;

import java.util.UUID;

public interface OutgoingEventStream {

    void subscribe(UUID connectionUUID);
    void unsubscribe(UUID connectionUUID);
}
