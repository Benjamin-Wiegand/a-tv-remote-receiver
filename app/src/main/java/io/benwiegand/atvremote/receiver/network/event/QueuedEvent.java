package io.benwiegand.atvremote.receiver.network.event;

import android.os.SystemClock;

import io.benwiegand.atvremote.receiver.async.SecAdapter;

public record QueuedEvent(String event, SecAdapter<EventResult> adapter, long enqueuedAt) implements QueuedOutput {
    public QueuedEvent(String event, SecAdapter<EventResult> adapter) {
        this(event, adapter, SystemClock.elapsedRealtime());
    }

    public Type type() {
        return Type.EVENT;
    }

    public InFlightEvent toInFlightEvent() {
        return new InFlightEvent(adapter(), enqueuedAt());
    }
}
