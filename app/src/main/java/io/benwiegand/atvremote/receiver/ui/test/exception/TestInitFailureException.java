package io.benwiegand.atvremote.receiver.ui.test.exception;

public class TestInitFailureException extends TestException {
    public TestInitFailureException(String message) {
        super(message);
    }

    public TestInitFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
