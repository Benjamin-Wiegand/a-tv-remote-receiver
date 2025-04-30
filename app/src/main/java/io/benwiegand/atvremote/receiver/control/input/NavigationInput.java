package io.benwiegand.atvremote.receiver.control.input;

public interface NavigationInput extends InputHandler {
    void navHome();
    void navBack();
    void navRecent();
    void navApps();
    void navNotifications();
    void navQuickSettings();
}
