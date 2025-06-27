package io.benwiegand.atvremote.receiver.control;

import java.util.Optional;

import io.benwiegand.atvremote.receiver.control.input.ActivityLauncherInput;
import io.benwiegand.atvremote.receiver.control.input.CursorInput;
import io.benwiegand.atvremote.receiver.control.input.DirectionalPadInput;
import io.benwiegand.atvremote.receiver.control.input.KeyboardInput;
import io.benwiegand.atvremote.receiver.control.input.MediaInput;
import io.benwiegand.atvremote.receiver.control.input.NavigationInput;
import io.benwiegand.atvremote.receiver.control.input.ScrollInput;
import io.benwiegand.atvremote.receiver.control.input.VolumeInput;
import io.benwiegand.atvremote.receiver.control.output.OverlayOutput;

// breaks up various categories of control into configurable sections
// ex:
//  - on android 9 the accessibility dpad experience is abysmal, so use adb
//  - the mouse pointer can be done through accessibility or adb
//     - regardless, if the app isn't system, accessibility is needed to show the cursor
// another ex:
//  - android tv 15 works great using accessibility for everything
//  - I do my own android tv 15 builds, it might be nice to also have everything doable as a system app
public class ControlScheme {
    // input
    private final ControlHandlerSupplier<ActivityLauncherInput> activityLauncherInputSupplier;
    private final ControlHandlerSupplier<CursorInput> cursorInputSupplier;
    private final ControlHandlerSupplier<DirectionalPadInput> directionalPadInputSupplier;
    private final ControlHandlerSupplier<KeyboardInput> keyboardInputSupplier;
    private final ControlHandlerSupplier<MediaInput> mediaInputSupplier;
    private final ControlHandlerSupplier<NavigationInput> navigationInputSupplier;
    private final ControlHandlerSupplier<ScrollInput> scrollInputSupplier;
    private final ControlHandlerSupplier<VolumeInput> volumeInputSupplier;
    // todo: power (sleep and menu)

    // output
    private final ControlHandlerSupplier<OverlayOutput> overlayOutputSupplier;

    public ControlScheme(ControlHandlerSupplier<ActivityLauncherInput> activityLauncherInputSupplier, ControlHandlerSupplier<CursorInput> cursorInputSupplier, ControlHandlerSupplier<DirectionalPadInput> directionalPadInputSupplier, ControlHandlerSupplier<KeyboardInput> keyboardInputSupplier, ControlHandlerSupplier<MediaInput> mediaInputSupplier, ControlHandlerSupplier<NavigationInput> navigationInputSupplier, ControlHandlerSupplier<ScrollInput> scrollInputSupplier, ControlHandlerSupplier<VolumeInput> volumeInputSupplier, ControlHandlerSupplier<OverlayOutput> overlayOutputSupplier) {
        this.activityLauncherInputSupplier = activityLauncherInputSupplier;
        this.cursorInputSupplier = cursorInputSupplier;
        this.directionalPadInputSupplier = directionalPadInputSupplier;
        this.keyboardInputSupplier = keyboardInputSupplier;
        this.mediaInputSupplier = mediaInputSupplier;
        this.navigationInputSupplier = navigationInputSupplier;
        this.scrollInputSupplier = scrollInputSupplier;
        this.volumeInputSupplier = volumeInputSupplier;
        this.overlayOutputSupplier = overlayOutputSupplier;
    }

    public ActivityLauncherInput getActivityLauncherInput() throws ControlNotInitializedException {
        return activityLauncherInputSupplier.get();
    }

    public CursorInput getCursorInput() throws ControlNotInitializedException {
        return cursorInputSupplier.get();
    }

    public DirectionalPadInput getDirectionalPadInput() throws ControlNotInitializedException {
        return directionalPadInputSupplier.get();
    }

    public KeyboardInput getKeyboardInput() throws ControlNotInitializedException {
        return keyboardInputSupplier.get();
    }

    public MediaInput getMediaInput() throws ControlNotInitializedException {
        return mediaInputSupplier.get();
    }

    public NavigationInput getNavigationInput() throws ControlNotInitializedException {
        return navigationInputSupplier.get();
    }

    public ScrollInput getScrollInput() throws ControlNotInitializedException {
        return scrollInputSupplier.get();
    }

    public VolumeInput getVolumeInput() throws ControlNotInitializedException {
        return volumeInputSupplier.get();
    }

    public OverlayOutput getOverlayOutput() throws ControlNotInitializedException {
        return overlayOutputSupplier.get();
    }

    public Optional<OverlayOutput> getOverlayOutputOptional() {
        return Optional.ofNullable(overlayOutputSupplier.get());
    }

}
