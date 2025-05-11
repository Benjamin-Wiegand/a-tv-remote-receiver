package io.benwiegand.atvremote.receiver.protocol;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

public class RemoteProtocolException extends Exception {
    @StringRes private Integer stringResMessage = null;

    public RemoteProtocolException(String message) {
        super(message);
    }

    public RemoteProtocolException(@StringRes int stringResMessage, String message) {
        super(message);
        this.stringResMessage = stringResMessage;
    }

    public RemoteProtocolException(String message, Throwable cause) {
        super(message, cause);
    }

    public RemoteProtocolException(@StringRes int stringResMessage, String message, Throwable cause) {
        super(message, cause);
        this.stringResMessage = stringResMessage;
    }

    @Nullable
    @StringRes
    public Integer getStringResMessage() {
        return stringResMessage;
    }

}
