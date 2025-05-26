package io.benwiegand.atvremote.receiver.protocol.json;

import android.content.Context;

import java.text.MessageFormat;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.protocol.RemoteProtocolException;
import io.benwiegand.atvremote.receiver.ui.ErrorMessageException;

public record ErrorDetails(String text) {
    public RemoteProtocolException toException() {
        return new RemoteProtocolException(text());
    }

    public static ErrorDetails fromException(Context context, Throwable t) {
        if (t instanceof ErrorMessageException e) {
            return new ErrorDetails(e.getLocalizedMessage(context));
        }
        return new ErrorDetails(MessageFormat.format(
                context.getString(R.string.protocol_error_unexpected),
                t.getClass().getSimpleName(),
                t.getLocalizedMessage()));
    }
}
