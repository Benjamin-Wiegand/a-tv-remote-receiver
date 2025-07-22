package io.benwiegand.atvremote.receiver.async;

import java.util.function.Supplier;

public class PendingSec<T> {
    private final Object lock = new Object();

    private boolean started = false;

    private final Supplier<Sec<T>> secStarter;

    PendingSec(Supplier<Sec<T>> secStarter) {
        this.secStarter = secStarter;
    }

    public Sec<T> start() {
        synchronized (lock) {
            if (started) throw new IllegalStateException("already started");
            started = true;
        }
        return secStarter.get();
    }
}
