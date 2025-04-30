package io.benwiegand.atvremote.receiver.control.input;

public interface MediaInput extends InputController {
    void pause();
    void nextTrack();
    void prevTrack();
    void skipBackward();
    void skipForward();
}
