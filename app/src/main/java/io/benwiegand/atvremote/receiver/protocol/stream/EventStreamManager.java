package io.benwiegand.atvremote.receiver.protocol.stream;

import android.util.Log;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import io.benwiegand.atvremote.receiver.async.Sec;

public class EventStreamManager {
    private static final String TAG = EventStreamManager.class.getSimpleName();

    private final Map<String, OutgoingEventStream> eventStreamMap = new ConcurrentHashMap<>();

    private final BiFunction<UUID, String, Sec<Void>> eventSender;

    public EventStreamManager(BiFunction<UUID, String, Sec<Void>> eventSender) {
        this.eventSender = eventSender;
    }

    /**
     * subscribes a connection to an event stream.
     * @param connectionUUID uuid of the subscribing connection
     * @param eventType event stream type to subscribe to
     * @return true if stream exists, false if not
     */
    public boolean subscribeToStream(UUID connectionUUID, String eventType) {
        Log.v(TAG, "subscribing connection '" + connectionUUID + "' to stream: " + eventType);
        OutgoingEventStream eventStream = eventStreamMap.get(eventType);
        if (eventStream == null) return false;

        eventStream.subscribe(connectionUUID);
        return true;
    }

    /**
     * unsubscribes a connection from an event stream.
     * @param connectionUUID uuid of the unsubscribing connection
     * @param eventType event stream type to unsubscribe from
     */
    public void unsubscribeFromStream(UUID connectionUUID, String eventType) {
        Log.v(TAG, "unsubscribing connection '" + connectionUUID + "' from stream: " + eventType);
        OutgoingEventStream eventStream = eventStreamMap.get(eventType);
        if (eventStream == null) return;

        eventStream.unsubscribe(connectionUUID);
    }

    public OutgoingStateEventStream getOrCreateStateEventStream(String eventType) {
        synchronized (eventStreamMap) {
            return (OutgoingStateEventStream) eventStreamMap.computeIfAbsent(eventType,
                    t -> new OutgoingStateEventStream(t, eventSender));
        }
    }

    public void destroy() {
        // nothing here for now
    }
}
