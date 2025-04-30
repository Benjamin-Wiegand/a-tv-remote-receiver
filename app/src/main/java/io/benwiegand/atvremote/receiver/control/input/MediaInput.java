package io.benwiegand.atvremote.receiver.control.input;

public interface MediaInput extends InputHandler {
    void pause();
    void nextTrack();
    void prevTrack();
    void skipBackward();
    void skipForward();
}
