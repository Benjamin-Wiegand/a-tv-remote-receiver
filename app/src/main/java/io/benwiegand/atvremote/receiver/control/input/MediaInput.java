package io.benwiegand.atvremote.receiver.control.input;

import io.benwiegand.atvremote.receiver.protocol.KeyEventType;

public interface MediaInput extends InputHandler {
    void playPause(KeyEventType type);
    void pause(KeyEventType type);
    void play(KeyEventType type);
    void nextTrack(KeyEventType type);
    void prevTrack(KeyEventType type);
    void skipBackward(KeyEventType type);
    void skipForward(KeyEventType type);
}
