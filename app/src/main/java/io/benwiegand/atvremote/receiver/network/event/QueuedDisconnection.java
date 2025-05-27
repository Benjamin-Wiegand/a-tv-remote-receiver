package io.benwiegand.atvremote.receiver.network.event;

public record QueuedDisconnection() implements QueuedOutput {
    @Override
    public Type type() {
        return Type.DISCONNECTION;
    }
}
