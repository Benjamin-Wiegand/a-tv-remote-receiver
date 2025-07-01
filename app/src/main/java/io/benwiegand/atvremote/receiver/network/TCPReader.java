package io.benwiegand.atvremote.receiver.network;

import static io.benwiegand.atvremote.receiver.network.SocketUtil.tryClose;

import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class TCPReader implements Closeable {
    private final static String TAG = TCPReader.class.getSimpleName();
    private final static int CHAR_BUFFER_SIZE = 1024;
    private static final int MAX_LINE_BUFFER = 5;

    private final InputStreamReader reader;

    private final Thread readThread = new Thread(this::readLoop);
    private boolean dead = false;
    private IOException deathException = new IOException("unknown error");

    // two locks (including lineBuffer itself) because read thread needs to wait for reads for
    // buffer limit and read calls need to wait for lineBuffer to have things
    private final Object lineBufferPollNotificationLock = new Object();
    private final Queue<String> lineBuffer = new ConcurrentLinkedQueue<>();

    public TCPReader(InputStreamReader reader) {
        this.reader = reader;
        readThread.start();
    }

    /**
     * @return true if buffer limit reduced, false if interrupted before that happens
     */
    private boolean waitForLineBufferLimit() {
        synchronized (lineBufferPollNotificationLock) {
            try {
                while (lineBuffer.size() >= MAX_LINE_BUFFER) lineBufferPollNotificationLock.wait();
                return true;
            } catch (InterruptedException e) {
                Log.d(TAG, "interrupted");
                return false;
            }
        }
    }

    private void readLoop() {
        Log.d(TAG, "starting read loop");
        try {
            char[] buffer = new char[CHAR_BUFFER_SIZE];
            StringBuilder lineBuilder = new StringBuilder();
            boolean cr = false;

            while (!dead) {

                if (lineBuffer.size() >= MAX_LINE_BUFFER) {
                    Log.w(TAG, "hit line buffer limit");
                    if (!waitForLineBufferLimit()) continue;
                }

                int len = reader.read(buffer);
                int offset = 0;
                for (int i = 0; i < len; i++) {
                    if (buffer[i] == '\n') {
                        lineBuilder.append(buffer, offset, i - offset);
                        String line = cr ?  // remove cr for crlf compatibility
                                lineBuilder.substring(0, lineBuilder.length() - 1) :
                                lineBuilder.toString();
                        lineBuilder = new StringBuilder();

                        synchronized (lineBuffer) {
                            lineBuffer.add(line);
                            lineBuffer.notify();
                        }

                        offset = i + 1;
                        cr = false;

                    } else cr = buffer[i] == '\r';
                }

                if (len == -1) throw new IOException("EOS (got -1)");
                lineBuilder.append(buffer, offset, len - offset);
            }

            deathException = new IOException("stream closed");

        } catch (IOException e) {
            Log.w(TAG, "read thread encountered IOException", e);
            deathException = e;
        } catch (RuntimeException e) {
            Log.e(TAG, "read thread encountered unexpected exception", e);
            deathException = new IOException("read thread encountered unexpected exception and will terminate", e);
        } finally {
            Log.d(TAG, "read thread terminating. dead = " + dead);
            tryClose(this);
        }
    }

    public String nextLine(long timeout) throws IOException, InterruptedException {
        synchronized (lineBuffer) {
            if (dead) throw new IOException(deathException);

            String line = lineBuffer.poll();
            if (line == null) {
                lineBuffer.wait(timeout);
                line = lineBuffer.poll();
            }

            if (line == null) {
                if (dead) throw new IOException(deathException);
                return null;
            }

            synchronized (lineBufferPollNotificationLock) {
                lineBufferPollNotificationLock.notifyAll();
            }

            if (NetworkDebugConstants.NETWORK_DEBUG_LOGS) Log.d(TAG, "RX: " + line);
            return line;
        }
    }

    public boolean isDead() {
        return dead;
    }

    @Override
    public void close() {
        dead = true;

        // close reader if not already
        tryClose(reader);

        // stop the read thread
        try {
            readThread.interrupt();
        } catch (SecurityException e) {
            Log.wtf(TAG, "failed to interrupt read thread due to security exception", e);
        }

        // free up threads blocking for next line
        synchronized (lineBuffer) {
            lineBuffer.notifyAll();
        }
    }

    public static TCPReader createFromStream(InputStream is, Charset cs) {
        return new TCPReader(new InputStreamReader(is, cs));
    }

}
