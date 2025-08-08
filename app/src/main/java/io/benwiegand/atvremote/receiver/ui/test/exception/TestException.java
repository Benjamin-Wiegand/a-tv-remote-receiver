package io.benwiegand.atvremote.receiver.ui.test.exception;

import androidx.annotation.Nullable;

public class TestException extends Exception {
    private Long elapsedMs = null;

    public TestException(Throwable cause) {
        super(cause);
    }

    public TestException(String message, Throwable cause) {
        super(message, cause);
    }

    public TestException(String message) {
        super(message);
    }

    public void setDetails(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    @Nullable
    @Override
    public String getMessage() {
        String message = super.getMessage();
        String result = "";
        if (message != null) result += message + " ||| ";
        result += "elapsed ms = " + elapsedMs;
        return result;
    }
}
