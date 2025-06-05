package io.benwiegand.atvremote.receiver.protocol.limiting;

import io.benwiegand.atvremote.receiver.async.Sec;

public interface Limiter<T, U> {
    Sec<U> send(T event);
}
