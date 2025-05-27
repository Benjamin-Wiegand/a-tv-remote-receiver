package io.benwiegand.atvremote.receiver.network;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.google.gson.Gson;

import java.io.Closeable;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.async.Sec;
import io.benwiegand.atvremote.receiver.async.SecAdapter;
import io.benwiegand.atvremote.receiver.auth.ssl.KeyUtil;
import io.benwiegand.atvremote.receiver.network.event.EventResult;
import io.benwiegand.atvremote.receiver.network.event.InFlightEvent;
import io.benwiegand.atvremote.receiver.network.event.QueuedDisconnection;
import io.benwiegand.atvremote.receiver.network.event.QueuedEvent;
import io.benwiegand.atvremote.receiver.network.event.QueuedOutput;
import io.benwiegand.atvremote.receiver.network.event.QueuedResponse;
import io.benwiegand.atvremote.receiver.protocol.MalformedEventException;
import io.benwiegand.atvremote.receiver.protocol.OperationDefinition;
import io.benwiegand.atvremote.receiver.protocol.RemoteProtocolException;
import io.benwiegand.atvremote.receiver.protocol.json.ErrorDetails;
import io.benwiegand.atvremote.receiver.stuff.ThrowingRunnable;
import io.benwiegand.atvremote.receiver.util.ErrorUtil;

import static io.benwiegand.atvremote.receiver.network.SocketUtil.tryClose;
import static io.benwiegand.atvremote.receiver.protocol.ProtocolConstants.*;

/**
 * it juggles the events between the devices
 */
public class EventJuggler implements Closeable {
    private static final String TAG = EventJuggler.class.getSimpleName();

    private static final int BASE64_FLAGS = Base64.DEFAULT | Base64.NO_WRAP | Base64.NO_PADDING;
    private static final long EVENT_TIMEOUT = 5000;

    private static final Gson gson = new Gson();

    private final ThreadPoolExecutor threadPool = new ThreadPoolExecutor(2, 8, 3, TimeUnit.SECONDS, new LinkedBlockingDeque<>());

    // incoming events
    private final Thread inThread = new Thread(runLoop(this::inputLoop));
    private final TCPReader reader;
    private final Map<String, OperationDefinition> operationMap = new ConcurrentHashMap<>();
    private final Semaphore outQueueSemaphore = new Semaphore(0);

    // outgoing events
    private final Thread outThread = new Thread(runLoop(this::outputLoop));
    private final TCPWriter writer;
    private final Queue<QueuedOutput> outQueue = new ConcurrentLinkedQueue<>();
    private final Map<String, InFlightEvent> responseMap = new ConcurrentHashMap<>();

    // event ids
    private final SecureRandom random = KeyUtil.getSecureRandom();
    private final byte[] serialBuffer = new byte[3];

    // misc
    private final Context context;
    private final long pingInterval;
    private final long pingTimeout;
    private final Consumer<Throwable> onDeath;
    private boolean onDeathCalled = false;
    private boolean dead = false;
    private final Object handlingTimeoutsLock = new Object();
    private boolean handlingTimeouts = false;

    EventJuggler(Context context, TCPReader reader, TCPWriter writer, Consumer<Throwable> onDeath, long pingInterval, long pingTimeout) {
        this.context = context;
        this.reader = reader;
        this.writer = writer;
        this.pingInterval = pingInterval;
        this.pingTimeout = pingTimeout;
        this.onDeath = onDeath;
    }

    public void start(OperationDefinition[] operations) {
        inThread.start();
        outThread.start();
        for (OperationDefinition operation : operations) {
            operationMap.put(operation.operation(), operation);
        }
    }

    public void close() {
        Log.d(TAG, "close()");
        synchronized (onDeath) {
            if (dead) {
                Log.w(TAG, "close() called but already dead", new Throwable());
                return;
            }
            dead = true;
        }

        try {
            inThread.interrupt();
            outThread.interrupt();
        } catch (Throwable t) {
            Log.e(TAG, "failed to interrupt threads", t);
        }

        QueuedOutput entry;
        while ((entry = outQueue.poll()) != null) {
            if (entry.type() == QueuedOutput.Type.EVENT)
                ((QueuedEvent) entry).adapter().throwError(new IOException("connection closed"));
        }

        tryClose(reader);
        tryClose(writer);

        threadPool.shutdown();
    }

    public boolean isDead() {
        return dead;
    }

    public Sec<EventResult> sendEvent(String event) {
        SecAdapter.SecWithAdapter<EventResult> secWithAdapter = SecAdapter.createThreadless();

        QueuedEvent queuedEvent = new QueuedEvent(event, secWithAdapter.secAdapter());
        enqueueOutput(queuedEvent);

        // do this after to prevent race conditions while avoiding needing a lock
        if (dead) {
            removeOutputFromQueue(queuedEvent);
            return Sec.premeditatedError(new IOException("connection is dead"));
        }

        return secWithAdapter.sec();
    }

    private void enqueueOutput(QueuedOutput output) {
        outQueue.add(output);
        outQueueSemaphore.release();
    }

    /**
     * not intended to be reliable, don't use this for anything important
     * @param output queue entry to remove
     */
    private void removeOutputFromQueue(QueuedOutput output) {
        if (outQueueSemaphore.tryAcquire() && !outQueue.remove(output))
            outQueueSemaphore.release();
    }

    private QueuedResponse createErrorResponse(String eventId, ErrorDetails e) {
        return new QueuedResponse("!" + eventId + " " + OP_ERR + " " + gson.toJson(e));
    }

    private QueuedResponse createErrorResponse(String eventId, Throwable t) {
        return createErrorResponse(eventId, ErrorDetails.fromException(context, t));
    }

    private void handleTimeouts() {
        synchronized (handlingTimeoutsLock) {
            if (handlingTimeouts) return;
            if (responseMap.isEmpty()) return;
            handlingTimeouts = true;
            try {
                threadPool.execute(() -> {
                    try {
                        List<String> expired = new LinkedList<>();
                        for (Map.Entry<String, InFlightEvent> entry : responseMap.entrySet()) {
                            if (entry.getValue().isExpired(EVENT_TIMEOUT))
                                expired.add(entry.getKey());
                        }

                        for (String eventId : expired) {
                            InFlightEvent inFlightEvent = responseMap.remove(eventId);
                            if (inFlightEvent == null) continue;
                            Log.d(TAG, "expiring event: " + eventId);
                            threadPool.execute(() -> inFlightEvent.adapter()
                                    .throwError(new RemoteProtocolException(R.string.protocol_error_event_timeout, "timed out")));
                        }
                    } finally {
                        handlingTimeouts = false;
                    }
                });
            } catch (Throwable t) {
                handlingTimeouts = false;
                throw t;
            }
        }
    }

    private void handleResponse(String line) {
        int i = line.indexOf(' ');
        if (i < 2) throw new MalformedEventException("response has no event id");
        if (line.length() < i + 2) throw new MalformedEventException("response is empty");

        threadPool.execute(() -> {
            String eventId = line.substring(1, i);

            InFlightEvent inFlightEvent = responseMap.remove(eventId);
            if (inFlightEvent == null) {
                // the event could have timed out
                Log.w(TAG, "got response for non-existent event: " + eventId);
                return;
            }

            inFlightEvent.adapter().provideResult(new EventResult(line.substring(i + 1)));
        });
    }

    private void handleEvent(String line) {
        // responses start with '!'
        if (line.charAt(0) == '!') {
            threadPool.execute(() -> handleResponse(line));
            return;
        }

        int iId = line.indexOf(' ');
        if (iId < 1 || line.length() < iId + 2) throw new MalformedEventException("no operation");

        threadPool.execute(() -> {
            String eventId = line.substring(0, iId);

            int iExtra = line.indexOf(' ', iId + 1);
            String op, extra;
            if (iExtra < 0) {
                op = line.substring(iId + 1);
                extra = null;
            } else {
                op = line.substring(iId + 1, iExtra);
                extra = line.substring(iExtra + 1);
            }

            OperationDefinition definition = operationMap.get(op);

            if (definition == null) {
                enqueueOutput(new QueuedResponse("!" + eventId + " " + OP_UNSUPPORTED));
                return;
            }

            try {
                String responseExtra = definition.handler().apply(extra);
                String response = "!" + eventId + " " + OP_CONFIRM;
                if (responseExtra != null) response += " " + responseExtra;
                enqueueOutput(new QueuedResponse(response));
            } catch (Throwable t) {
                enqueueOutput(createErrorResponse(eventId, t));
                if (definition.closeConnectionOnFailure())
                    enqueueOutput(new QueuedDisconnection());
            }
        });

    }

    private void inputLoop() throws IOException, InterruptedException {
        while (!dead) {
            String line = reader.nextLine(pingTimeout);
            if (line == null) throw new IOException("no message or ping received within timeout");
            if (line.isEmpty()) continue;

            handleEvent(line);
        }
    }

    /**
     * generates a random event id consisting of 3 bytes, encoded in base64 resulting in a length
     * of 4 characters and no padding needed.
     * <p>
     *     <small>This means that there is a 1 in 16,777,216 chance that you could get a collision
     *     with another event in the response map. <sub>This is extremely insignificant because
     *     usually there won't even be another event in the map to collide with. And even if you
     *     really do have the worst luck, it checks and just generates a new one.</sub></small>
     * </p>
     * @return a new event id
     */
    private String generateEventId() {
        String eventId;
        do {
            random.nextBytes(serialBuffer);
            eventId = Base64.encodeToString(serialBuffer, BASE64_FLAGS);
        } while (responseMap.containsKey(eventId));
        return eventId;
    }

    private QueuedEvent createPing() {
        SecAdapter.SecWithAdapter<EventResult> secWithAdapter = SecAdapter.createThreadless();
        return new QueuedEvent(OP_PING, secWithAdapter.secAdapter());
    }

    private void outputLoop() throws IOException, InterruptedException {
        while (!dead) {
            if (outQueueSemaphore.availablePermits() == 0) handleTimeouts();

            QueuedOutput output;
            if (outQueueSemaphore.tryAcquire(pingInterval, TimeUnit.MILLISECONDS)) {
                output = outQueue.remove();
            } else {
                output = createPing();
            }

            switch (output.type()) {
                case RESPONSE -> {
                    QueuedResponse response = (QueuedResponse) output;
                    writer.sendLine(response.response());
                }
                case EVENT -> {
                    QueuedEvent event = (QueuedEvent) output;
                    try {
                        String eventId = generateEventId();
                        assert responseMap.putIfAbsent(eventId, event.toInFlightEvent()) == null;
                        writer.sendLine(eventId + " " + event.event());
                    } catch (Throwable t) {
                        threadPool.execute(() -> event.adapter().throwError(t));
                        throw t;
                    }
                }
                case DISCONNECTION -> {
                    Log.i(TAG, "disconnection event");
                    tryClose(this);
                }
            }
        }
    }

    private Runnable runLoop(ThrowingRunnable loop) {
        return () -> {
            Throwable exitThrowable = null;
            try {
                loop.run();
            } catch (IOException e) {
                Log.e(TAG, "connection died:\n" + ErrorUtil.getLightStackTrace(e));
                exitThrowable = e;
            } catch (InterruptedException e) {
                Log.e(TAG, "connection loop interrupted:\n" + ErrorUtil.getLightStackTrace(e));
                exitThrowable = e;
            } catch (Throwable t) {
                Log.e(TAG, "unexpected error in connection", t);
                exitThrowable = t;
            } finally {
                boolean closeMe, callOnDeath;
                synchronized (onDeath) {
                    closeMe = !dead;
                    if (dead) exitThrowable = null; // discard reactions from an intentional close
                    callOnDeath = !onDeathCalled;
                    onDeathCalled = true;
                }
                if (closeMe) tryClose(this);
                if (callOnDeath) onDeath.accept(exitThrowable);
            }
        };
    }

}
