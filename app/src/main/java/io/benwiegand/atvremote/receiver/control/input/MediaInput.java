package io.benwiegand.atvremote.receiver.control.input;

public interface MediaInput extends InputHandler {
    void playPause();
    void pause();
    void play();
    void nextTrack();
    void prevTrack();
    void skipBackward();
    void skipForward();
}
