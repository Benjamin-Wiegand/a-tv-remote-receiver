package io.benwiegand.atvremote.receiver.protocol;

import androidx.annotation.StringRes;

import io.benwiegand.atvremote.receiver.ui.ErrorMessageException;

public class RemoteProtocolException extends ErrorMessageException {

    public RemoteProtocolException(String message) {
        super(message);
    }

    public RemoteProtocolException(@StringRes int stringResMessage, String message) {
        super(stringResMessage, message);
    }

    public RemoteProtocolException(String message, Throwable cause) {
        super(message, cause);
    }

    public RemoteProtocolException(@StringRes int stringResMessage, String message, Throwable cause) {
        super(stringResMessage, message, cause);
    }
}
