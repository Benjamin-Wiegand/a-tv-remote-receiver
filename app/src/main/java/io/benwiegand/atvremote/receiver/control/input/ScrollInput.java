package io.benwiegand.atvremote.receiver.control.input;

public interface ScrollInput extends InputController {
    void scrollVertical(double trajectory, boolean glide);
    void scrollHorizontal(double trajectory, boolean glide);
}
