package io.benwiegand.atvremote.receiver.network.event;

public interface QueuedOutput {
    enum Type {
        EVENT,
        RESPONSE,
        DISCONNECTION,
    }

    Type type();
}
