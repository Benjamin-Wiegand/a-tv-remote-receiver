package io.benwiegand.atvremote.receiver.network.event;

public record QueuedResponse(String response) implements QueuedOutput {
    @Override
    public Type type() {
        return Type.RESPONSE;
    }
}
