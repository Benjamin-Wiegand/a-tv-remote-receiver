package io.benwiegand.atvremote.receiver.control.input;

import io.benwiegand.atvremote.receiver.protocol.KeyEventType;

public interface FullNavigationInput extends BackNavigationInput {
    void navHome(KeyEventType type);
    void navRecent(KeyEventType type);
    void navNotifications(KeyEventType type);
    void navQuickSettings();
}
