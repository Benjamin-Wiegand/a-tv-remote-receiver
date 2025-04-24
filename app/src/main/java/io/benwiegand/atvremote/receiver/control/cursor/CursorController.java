package io.benwiegand.atvremote.receiver.control.cursor;

public interface CursorController {
    void showCursor();
    void hideCursor();
    void cursorMove(int deltaX, int deltaY);
    void cursorDown();
    void cursorUp();

    void destroy();
}
