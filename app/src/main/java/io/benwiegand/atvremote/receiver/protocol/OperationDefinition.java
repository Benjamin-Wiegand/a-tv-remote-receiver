package io.benwiegand.atvremote.receiver.protocol;

import io.benwiegand.atvremote.receiver.stuff.ThrowingConsumer;
import io.benwiegand.atvremote.receiver.stuff.ThrowingFunction;
import io.benwiegand.atvremote.receiver.stuff.ThrowingRunnable;

public record OperationDefinition(String operation, ThrowingFunction<String, String> handler, boolean closeConnectionOnFailure) {

    public OperationDefinition(String operation, ThrowingFunction<String, String> handler) {
        this(operation, handler, false);
    }

    public OperationDefinition(String operation, ThrowingRunnable handler) {
        this(operation, e -> {
            handler.run();
            return null;
        }, false);
    }

    public OperationDefinition(String operation, ThrowingConsumer<String> handler) {
        this(operation, e -> {
            handler.accept(e);
            return null;
        }, false);
    }
}
