package io.benwiegand.atvremote.receiver.protocol.stream;

import android.util.Log;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import io.benwiegand.atvremote.receiver.async.Sec;
import io.benwiegand.atvremote.receiver.protocol.limiting.ChanneledReplacementLimiter;

/**
 * OutgoingEventStream ideal for state updates:
 * <ul>
 *     <li>when connections are added they will be sent a copy of the last issued event</li>
 *     <li>events can have a "channel" that will be used for rate-limiting and optimising of state updates for a common state</li>
 *     <li>state updates within the same channel are throttled to not overwhelm the receiver</li>
 *     <li>the latest event within a channel is considered the current state, and any missed states before that will be forgotten</li>
 * </ul>
 */
public class OutgoingStateEventStream implements OutgoingEventStream {
    private static final String TAG = OutgoingStateEventStream.class.getSimpleName();

    private static final long RETRY_DELAY = 2500;

    private final Map<UUID, ChanneledReplacementLimiter<String, Void>> subscriptionMap = new ConcurrentHashMap<>();
    private final Map<Object, String> latestEventMap = Collections.synchronizedMap(new HashMap<>()); //todo
    private final Set<Object> channels = new HashSet<>();
    private final String type;

    private final BiFunction<UUID, String, Sec<Void>> eventSender;
    private final Timer retryTimer;

    public OutgoingStateEventStream(String type, BiFunction<UUID, String, Sec<Void>> eventSender, Timer retryTimer) {
        this.type = type;
        this.eventSender = eventSender;
        this.retryTimer = retryTimer;
    }

    /**
     * sends an event over the specified channel.
     * @param channel the event channel key
     * @param event the event to send
     */
    public void sendEvent(Object channel, String event) {
        synchronized (channels) {
            if (!channels.contains(channel)) throw new IllegalArgumentException("channel does not exist");

            latestEventMap.put(channel, event);

            synchronized (latestEventMap) {
                for (ChanneledReplacementLimiter<String, Void> limiter : subscriptionMap.values()) {
                    limiter.send(channel, event);
                }
            }
        }
    }

    /**
     * sends an event over the "null" channel.
     * @param event the event to send
     */
    public void sendEvent(String event) {
        sendEvent(null, event);
    }

    /**
     * adds an event channel
     * @param channel the event channel key
     * @return true if the channel didn't already exist
     */
    public boolean addChannel(Object channel) {
        synchronized (channels) {
            if (channels.contains(channel)) return false;

            synchronized (subscriptionMap) {
                for (ChanneledReplacementLimiter<String, Void> limiter : subscriptionMap.values())
                    limiter.addChannel(channel);
            }

            boolean added = channels.add(channel);
            assert added;

            return true;
        }
    }

    /**
     * removes an event channel
     * @param channel the channel key
     * @return true if the channel existed
     */
    public boolean removeChannel(Object channel) {
        synchronized (channels) {
            if (!channels.remove(channel)) return false;
            latestEventMap.remove(channel);
        }

        synchronized (subscriptionMap) {
            for (ChanneledReplacementLimiter<String, Void> limiter : subscriptionMap.values())
                limiter.removeChannel(channel);
        }

        return true;
    }

    private Function<String, Sec<Void>> adaptEventSender(UUID connectionUUID) {
        return event -> eventSender.apply(connectionUUID, type + " " + event);
    }

    private ChanneledReplacementLimiter<String, Void> createLimiter(UUID connectionUUID) {
        return new ChanneledReplacementLimiter<>(adaptEventSender(connectionUUID), new ChanneledReplacementLimiter.Callback<>() {
            @Override
            public void onSent(Object channel, String event, boolean more) {
                Log.d(TAG, "sent event to: " + connectionUUID + "\nchannel = " + channel + "\nmore after this = " + more);
            }

            @Override
            public void onFailure(Object channel, String event, Throwable t, @Nullable Supplier<Sec<Void>> retry) {
                Log.e(TAG, "event failed to send to connection '" + connectionUUID + "'", t);
                if (retry == null) return;

                Log.v(TAG, "scheduling retry");
                retryTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        if (!subscriptionMap.containsKey(connectionUUID)) return;
                        retry.get();
                    }
                }, RETRY_DELAY);
            }
        });
    }

    @Override
    public void subscribe(UUID connectionUUID) {
        ChanneledReplacementLimiter<String, Void> limiter = createLimiter(connectionUUID);

        synchronized (channels) {
            for (Object channel : channels)
                limiter.addChannel(channel);

            synchronized (subscriptionMap) {
                ChanneledReplacementLimiter<String, Void> existingLimiter = subscriptionMap.putIfAbsent(connectionUUID, limiter);

                // soft failure, this is the remotes responsibility
                if (existingLimiter != null) {
                    Log.w(TAG, "connection " + connectionUUID + " already subscribed to " + type);
                    limiter = existingLimiter;
                }

                // send latest states for each channel
                for (Map.Entry<Object, String> entry : latestEventMap.entrySet()) {
                    limiter.send(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    @Override
    public void unsubscribe(UUID connectionUUID) {
        synchronized (subscriptionMap) {
            ChanneledReplacementLimiter<String, Void> limiter = subscriptionMap.remove(connectionUUID);

            // soft failure, this is the remotes responsibility
            if (limiter == null)
                Log.w(TAG, "connection " + connectionUUID + " was never subscribed to " + type);
        }
    }
}
