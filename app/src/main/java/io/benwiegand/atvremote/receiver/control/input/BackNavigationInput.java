package io.benwiegand.atvremote.receiver.control.input;

import io.benwiegand.atvremote.receiver.protocol.KeyEventType;

public interface BackNavigationInput extends InputHandler {
    void navBack(KeyEventType type);
}
