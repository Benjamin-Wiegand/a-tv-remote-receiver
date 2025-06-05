package io.benwiegand.atvremote.receiver.protocol.limiting;

import androidx.annotation.Nullable;

import java.util.function.Function;
import java.util.function.Supplier;

import io.benwiegand.atvremote.receiver.async.Sec;
import io.benwiegand.atvremote.receiver.async.SecAdapter;
import io.benwiegand.atvremote.receiver.stuff.SerialInt;

/**
 * limits by skipping all events which are submitted while an event is sending, except for the latest event.
 * You could think of it like a queue with max 1 element, and overflowing the queue overwrites that element.
 */
public class ReplacementLimiter<T, U> implements Limiter<T, U> {
    private final Object lock = new Object();
    private final Function<T, Sec<U>> eventSender;
    private final Callback<T, U> callback;
    private T latestEvent = null;
    private SecAdapter<U> latestEventAdapter = null;
    private boolean sending = false;
    private final SerialInt serial = new SerialInt();

    public interface Callback<T, U> {
        void onSent(T event, boolean more);
        void onFailure(T event, Throwable t, @Nullable Supplier<Sec<U>> retry);
    }

    public static class ReplacedException extends Exception {
        public ReplacedException() {
            super();
        }
    }

    public ReplacementLimiter(Function<T, Sec<U>> eventSender, Callback<T, U> callback) {
        this.eventSender = eventSender;
        this.callback = callback;
    }

    private Sec<U> sendLocked(T event) {
        SecAdapter.SecWithAdapter<U> secWithAdapter = SecAdapter.createThreadless();

        if (latestEventAdapter != null) {
            latestEventAdapter.throwError(new ReplacedException());
        }

        latestEventAdapter = secWithAdapter.secAdapter();
        latestEvent = event;
        serial.advance();
        if (!sending) sendLatestLocked();

        return secWithAdapter.sec();
    }

    @Override
    public Sec<U> send(T event) {
        synchronized (lock) {
            return sendLocked(event);
        }
    }

    private void sendLatestLocked() {
        sending = latestEventAdapter != null;
        if (!sending) return;
        assert latestEvent != null;

        SecAdapter<U> eventAdapter = latestEventAdapter;
        T event = latestEvent;
        int eventSerial = serial.get();

        latestEventAdapter = null;
        latestEvent = null;

        eventSender.apply(event)
                .doOnResult(r -> {
                    eventAdapter.provideResult(r);
                    synchronized (lock) {
                        callback.onSent(event, !serial.isValid(eventSerial));
                        sendLatestLocked();
                    }
                })
                .doOnError(t -> {
                    eventAdapter.throwError(t);
                    Supplier<Sec<U>> retry = () -> {
                        synchronized (lock) {
                            if (!serial.isValid(eventSerial))
                                return Sec.premeditatedError(new ReplacedException());

                            assert !sending;
                            return sendLocked(event);
                        }
                    };
                    synchronized (lock) {
                        callback.onFailure(event, t, serial.isValid(eventSerial) ? retry : null);
                        sendLatestLocked();
                    }
                })
                .callMeWhenDone();
    }
}