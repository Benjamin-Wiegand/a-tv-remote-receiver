package io.benwiegand.atvremote.receiver.control;

public interface ControlHandlerSupplier<T extends ControlHandler> {
    T get() throws ControlNotInitializedException;
}
