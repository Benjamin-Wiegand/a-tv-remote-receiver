package io.benwiegand.atvremote.receiver.control.input;

public interface CursorInput extends InputHandler {
    void showCursor();
    void hideCursor();
    void cursorMove(int deltaX, int deltaY);
    void cursorDown();
    void cursorUp();
    void cursorClick();

}
