package io.benwiegand.atvremote.receiver.control.input;

import io.benwiegand.atvremote.receiver.protocol.KeyEventType;

public interface CursorInput extends InputHandler {
    void showCursor();
    void hideCursor();
    void cursorMove(int deltaX, int deltaY);
    void leftClick(KeyEventType type);

}
