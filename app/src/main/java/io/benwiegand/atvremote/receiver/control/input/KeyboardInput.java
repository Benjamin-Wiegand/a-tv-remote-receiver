package io.benwiegand.atvremote.receiver.control.input;


import io.benwiegand.atvremote.receiver.protocol.KeyEventType;

public interface KeyboardInput extends InputHandler {
    void setSoftKeyboardEnabled(boolean enabled);

    // trying to mirror the important parts of the InputMethod API
    boolean commitText(String input, int newCursorPosition);
    void getSurroundingText(int beforeLength, int afterLength);
    boolean setSelection(int start, int end);
    boolean deleteSurroundingText(int beforeLength, int afterLength);
    boolean performContextMenuAction(int id);
    boolean performEditorAction(int id);
    boolean sendKeyEvent(int keyCode, KeyEventType type);

}
