package io.benwiegand.atvremote.receiver.stuff;

public interface ThrowingSupplier<T> {
    T get() throws Throwable;
}
