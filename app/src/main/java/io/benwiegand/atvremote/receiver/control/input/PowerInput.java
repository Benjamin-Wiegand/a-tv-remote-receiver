package io.benwiegand.atvremote.receiver.control.input;

import io.benwiegand.atvremote.receiver.control.ControlHandler;
import io.benwiegand.atvremote.receiver.protocol.KeyEventType;

public interface PowerInput extends ControlHandler {
    void powerButton(KeyEventType type);
}
