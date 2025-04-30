package io.benwiegand.atvremote.receiver.control.input;


public interface KeyboardInput extends InputHandler {
    void hideSoftKeyboard();
    void showSoftKeyboard();
    void setSoftKeyboardEnabled(boolean enabled);

    void keyboardInput(String input);
    void pressKey(int keyCode);

}
