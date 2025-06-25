package io.benwiegand.atvremote.receiver.stuff;

import io.benwiegand.atvremote.receiver.protocol.KeyEventType;

/**
 * class to emulate key down/up event handling for input handlers that can't natively pass such events.
 */
public class FakeKeyDownUpHandler {

    private final Object lock = new Object();

    private final Runnable onShortPress;
    private final Runnable onLongPress;
    private final boolean clickOnKeyDown;
    private boolean pressed = false;
    private boolean longPressed = false;

    /**
     * creates an instance that emulates a long-pressable button
     * @param onShortPress runnable to be run when pressed
     * @param onLongPress runnable to be run (once) when held
     */
    public FakeKeyDownUpHandler(Runnable onShortPress, Runnable onLongPress) {
        this.onShortPress = onShortPress;
        this.onLongPress = onLongPress;
        clickOnKeyDown = false;
    }

    /**
     * creates an instance that emulates a button with no long press behavior
     * @param onShortPress runnable to be run when pressed
     * @param clickOnKeyDown true if the runnable should be run on key down, false if on key up
     */
    public FakeKeyDownUpHandler(Runnable onShortPress, boolean clickOnKeyDown) {
        this.onShortPress = onShortPress;
        this.onLongPress = null;
        this.clickOnKeyDown = clickOnKeyDown;
    }

    public void onKeyEvent(KeyEventType type) {
        Runnable action = switch (type) {
            case CLICK -> onShortPress;
            case DOWN -> {
                synchronized (lock) {
                    if (onLongPress == null) {
                        // single-click mode
                        if (!pressed && clickOnKeyDown) {
                            pressed = true;
                            yield onShortPress;
                        }
                    } else {
                        // hold action mode
                        if (!pressed) {
                            pressed = true;
                        } else if (!longPressed) {
                            longPressed = true;
                            yield onLongPress;
                        }
                    }
                    yield null;
                }
            }
            case UP -> {
                synchronized (lock) {
                    if (onLongPress == null) {
                        // single-click mode
                        pressed = false;
                        longPressed = false;
                        if (!clickOnKeyDown) yield onShortPress;
                    } else {
                        // hold action mode
                        pressed = false;
                        if (!longPressed) yield onShortPress;
                        longPressed = false;
                    }
                    yield null;
                }
            }
        };

        if (action == null) return;
        action.run();
    }

    public boolean isHandlingKeyPress() {
        synchronized (lock) {
            return pressed;
        }
    }
}
