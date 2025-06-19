package io.benwiegand.atvremote.receiver.control.input;

import io.benwiegand.atvremote.receiver.protocol.KeyEventType;

public interface NavigationInput extends InputHandler {
    void navHome(KeyEventType type);
    void navBack(KeyEventType type);
    void navRecent(KeyEventType type);
    void navNotifications(KeyEventType type);
    void navQuickSettings();
}
