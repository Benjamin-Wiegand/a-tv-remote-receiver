package io.benwiegand.atvremote.receiver.network;

import static io.benwiegand.atvremote.receiver.protocol.ProtocolConstants.NEWLINE;

import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;

public class TCPWriter implements Closeable {
    private static final String TAG = TCPWriter.class.getSimpleName();

    private final OutputStreamWriter writer;

    public TCPWriter(OutputStreamWriter writer) {
        this.writer = writer;
    }

    public void sendLine(String line) throws IOException {
        if (NetworkDebugConstants.NETWORK_DEBUG_LOGS) Log.d(TAG, "TX: " + line);
        writer.write(line + NEWLINE);
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }

    public static TCPWriter createFromStream(OutputStream os, Charset cs) {
        return new TCPWriter(new OutputStreamWriter(os, cs));
    }

}
