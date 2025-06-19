package io.benwiegand.atvremote.receiver.control.input;

import io.benwiegand.atvremote.receiver.protocol.KeyEventType;

public interface VolumeInput extends InputHandler {
    void volumeUp(KeyEventType type);
    void volumeDown(KeyEventType type);

    void mute();
    void unmute();
    void toggleMute(KeyEventType type);
}
