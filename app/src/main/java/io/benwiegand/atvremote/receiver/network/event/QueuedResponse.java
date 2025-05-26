package io.benwiegand.atvremote.receiver.network.event;

public record QueuedResponse(String response) implements QueuedOutput {
    public Type type() {
        return Type.RESPONSE;
    }
}
