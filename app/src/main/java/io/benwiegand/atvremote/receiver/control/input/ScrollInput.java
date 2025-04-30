package io.benwiegand.atvremote.receiver.control.input;

public interface ScrollInput extends InputHandler {
    void scrollVertical(double trajectory, boolean glide);
    void scrollHorizontal(double trajectory, boolean glide);
}
