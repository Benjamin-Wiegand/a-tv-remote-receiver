package io.benwiegand.atvremote.receiver.control;

public interface InputHandler {

    int getScreenWidth();
    int getScreenHeight();

    void dpadDown();
    void dpadUp();
    void dpadLeft();
    void dpadRight();
    void dpadSelect();
    void dpadLongPress();

    void navHome();
    void navBack();
    void navRecent();
    void navApps();
    void navNotifications();
    void navQuickSettings();

    void volumeUp();
    void volumeDown();
    void mute();

    void pause();
    void nextTrack();
    void prevTrack();
    void skipBackward();
    void skipForward();

    boolean softKeyboardEnabled();
    boolean softKeyboardVisible();
    void showSoftKeyboard();
    void hideSoftKeyboard();
    void setSoftKeyboardEnabled(boolean enabled);
    void keyboardInput(String input);

    boolean cursorSupported();
    void showCursor();
    void hideCursor();
    void cursorMove(int x, int y);
    void cursorDown();
    void cursorUp();
    void cursorContext();

    void scrollVertical(double trajectory, boolean glide);
    void scrollHorizontal(double trajectory, boolean glide);
}
