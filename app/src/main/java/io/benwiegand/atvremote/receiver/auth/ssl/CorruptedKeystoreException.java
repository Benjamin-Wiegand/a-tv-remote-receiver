package io.benwiegand.atvremote.receiver.auth.ssl;

public class CorruptedKeystoreException extends Exception {
    public CorruptedKeystoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
