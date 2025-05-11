package io.benwiegand.atvremote.receiver.protocol.json;

import android.content.Context;

import java.text.MessageFormat;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.protocol.RemoteProtocolException;

public record ErrorDetails(String text) {
    public static ErrorDetails fromProtocolException(Context context, RemoteProtocolException e) {
        if (e.getStringResMessage() != null)
            return new ErrorDetails(context.getString(e.getStringResMessage()));
        return new ErrorDetails(e.getLocalizedMessage());
    }

    public static ErrorDetails fromException(Context context, Throwable t) {
        return new ErrorDetails(MessageFormat.format(
                context.getString(R.string.protocol_error_unexpected),
                t.getClass().getSimpleName(),
                t.getLocalizedMessage()));
    }
}
