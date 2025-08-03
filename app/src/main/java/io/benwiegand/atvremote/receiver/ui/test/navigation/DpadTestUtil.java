package io.benwiegand.atvremote.receiver.ui.test.navigation;

import java.util.function.Consumer;

import io.benwiegand.atvremote.receiver.control.input.DirectionalPadInput;
import io.benwiegand.atvremote.receiver.protocol.KeyEventType;

public class DpadTestUtil {
    public enum DpadDirection {
        DPAD_UP,
        DPAD_DOWN,
        DPAD_LEFT,
        DPAD_RIGHT;

        public Consumer<KeyEventType> getMethodCall(DirectionalPadInput input) {
            return switch (this) {
                case DPAD_UP -> input::dpadUp;
                case DPAD_DOWN -> input::dpadDown;
                case DPAD_LEFT -> input::dpadLeft;
                case DPAD_RIGHT -> input::dpadRight;
            };
        }
    }



}
