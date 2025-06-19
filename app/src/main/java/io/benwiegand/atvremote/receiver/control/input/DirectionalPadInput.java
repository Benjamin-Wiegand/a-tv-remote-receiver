package io.benwiegand.atvremote.receiver.control.input;

import io.benwiegand.atvremote.receiver.protocol.KeyEventType;

public interface DirectionalPadInput extends InputHandler {
    void dpadDown(KeyEventType type);
    void dpadUp(KeyEventType type);
    void dpadLeft(KeyEventType type);
    void dpadRight(KeyEventType type);
    void dpadSelect(KeyEventType type);
    void dpadLongPress();
}
