package io.benwiegand.atvremote.receiver.stuff;

public interface ThrowingFunction<T, U> {
    U apply(T t) throws Throwable;
}
