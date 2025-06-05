package io.benwiegand.atvremote.receiver.protocol.limiting;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import io.benwiegand.atvremote.receiver.async.Sec;

/**
 * like a replacement limiter, but with channels that allow for appropriate handling of multiple independent states.
 */
public class ChanneledReplacementLimiter<T, U> implements Limiter<T, U> {
    private final Map<Object, ReplacementLimiter<T, U>> limiterMap = Collections.synchronizedMap(new HashMap<>()); //todo
    private final Function<T, Sec<U>> eventSender;
    private final Callback<T, U> callback;

    public interface Callback<T, U> {
        void onSent(Object channel, T event, boolean more);
        void onFailure(Object channel, T event, Throwable t, @Nullable Supplier<Sec<U>> retry);
    }

    public ChanneledReplacementLimiter(Function<T, Sec<U>> eventSender, Callback<T, U> callback) {
        this.eventSender = eventSender;
        this.callback = callback;
    }

    /**
     * sends an event on a channel.
     * @param channel the channel key
     * @param event the event
     * @return the Sec result
     * @throws IllegalArgumentException if the channel does not exist
     */
    public Sec<U> send(Object channel, T event) {
        ReplacementLimiter<T, U> limiter = limiterMap.get(channel);
        if (limiter == null) throw new IllegalArgumentException("channel does not exist: " + channel);

        return limiter.send(event);
    }

    /**
     * sends an event on the "null" channel. equivalent to calling send(null, event).
     * most likely you would want to specify a channel instead of using this.
     * note that there is no null channel by default, so this would throw
     * IllegalArgumentException if addChannel(null) was never called.
     * @throws IllegalArgumentException if the null channel was never added
     */
    @Override
    public Sec<U> send(T event) {
        return send(null, event);
    }

    /**
     * removes a channel from this limiter
     * @param channel the channel key
     * @throws IllegalArgumentException if the channel doesn't exist or was already removed
     */
    public void removeChannel(Object channel) {
        ReplacementLimiter<T, U> limiter = limiterMap.remove(channel);
        if (limiter == null) throw new IllegalArgumentException("channel does not exist");
    }

    /**
     * adds a channel that can be used with this limiter
     * @param channel the channel key
     * @throws IllegalArgumentException if the channel already exists
     */
    public void addChannel(Object channel) {
        ReplacementLimiter<T, U> limiter = new ReplacementLimiter<>(eventSender, adaptCallback(channel));
        ReplacementLimiter<T, U> existingLimiter = limiterMap.putIfAbsent(channel, limiter);
        if (existingLimiter != null) throw new IllegalArgumentException("channel already exists");
    }

    private ReplacementLimiter.Callback<T, U> adaptCallback(Object channel) {
        return new ReplacementLimiter.Callback<T, U>() {
            @Override
            public void onSent(T event, boolean more) {
                callback.onSent(channel, event, more);
            }

            @Override
            public void onFailure(T event, Throwable t, @Nullable Supplier<Sec<U>> retry) {
                callback.onFailure(channel, event, t, retry);
            }
        };
    }
}
