package io.benwiegand.atvremote.receiver.stuff;

public interface ThrowingConsumer<T> {
    void accept(T t) throws Throwable;
}
