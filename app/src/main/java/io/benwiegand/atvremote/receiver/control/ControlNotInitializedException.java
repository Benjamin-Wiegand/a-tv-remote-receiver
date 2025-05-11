package io.benwiegand.atvremote.receiver.control;

import io.benwiegand.atvremote.receiver.protocol.RemoteProtocolException;

public class ControlNotInitializedException extends RemoteProtocolException {
    public ControlNotInitializedException(String message) {
      super(message);
    }
}
