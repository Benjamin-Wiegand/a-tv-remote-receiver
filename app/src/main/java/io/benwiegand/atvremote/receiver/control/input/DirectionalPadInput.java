package io.benwiegand.atvremote.receiver.control.input;

public interface DirectionalPadInput extends InputHandler {
    void dpadDown();
    void dpadUp();
    void dpadLeft();
    void dpadRight();
    void dpadSelect();
    void dpadLongPress();
}
