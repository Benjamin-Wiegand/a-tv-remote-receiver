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
    private ActivityLauncherInput activityLauncherInput = null;
    private CursorInput cursorInput = null;
    private DirectionalPadInput directionalPadInput = null;
    private KeyboardInput keyboardInput = null;
    private MediaInput mediaInput = null;
    private NavigationInput navigationInput = null;
    private ScrollInput scrollInput = null;
    private VolumeInput volumeInput = null;
    // todo: power (sleep and menu)

    // output
    private OverlayOutput overlayOutput = null;

    private ControlSourceErrors controlSourceErrors;

    public ControlScheme(ControlSourceErrors controlSourceErrors) {
        this.controlSourceErrors = controlSourceErrors;
    }

    public void setControlSourceExceptions(ControlSourceErrors controlSourceErrors) {
        this.controlSourceErrors = controlSourceErrors;
    }

    public ActivityLauncherInput getActivityLauncherInput() throws ControlNotInitializedException {
        if (activityLauncherInput == null) throw new ControlNotInitializedException(controlSourceErrors.activityLauncherInputException());
        return activityLauncherInput;
    }

    public void setActivityLauncherInput(ActivityLauncherInput activityLauncherInput) {
        this.activityLauncherInput = activityLauncherInput;
    }

    public CursorInput getCursorInput() throws ControlNotInitializedException {
        if (cursorInput == null) throw new ControlNotInitializedException(controlSourceErrors.cursorInputException());
        return cursorInput;
    }

    public void setCursorInput(CursorInput cursorInput) {
        this.cursorInput = cursorInput;
    }

    public DirectionalPadInput getDirectionalPadInput() throws ControlNotInitializedException {
        if (directionalPadInput == null) throw new ControlNotInitializedException(controlSourceErrors.directionalPadInputException());
        return directionalPadInput;
    }

    public void setDirectionalPadInput(DirectionalPadInput directionalPadInput) {
        this.directionalPadInput = directionalPadInput;
    }

    public KeyboardInput getKeyboardInput() throws ControlNotInitializedException {
        if (keyboardInput == null) throw new ControlNotInitializedException(controlSourceErrors.keyboardInputException());
        return keyboardInput;
    }

    public void setKeyboardInput(KeyboardInput keyboardInput) {
        this.keyboardInput = keyboardInput;
    }

    public MediaInput getMediaInput() throws ControlNotInitializedException {
        if (mediaInput == null) throw new ControlNotInitializedException(controlSourceErrors.mediaInputException());
        return mediaInput;
    }

    public void setMediaInput(MediaInput mediaInput) {
        this.mediaInput = mediaInput;
    }

    public NavigationInput getNavigationInput() throws ControlNotInitializedException {
        if (navigationInput == null) throw new ControlNotInitializedException(controlSourceErrors.navigationInputException());
        return navigationInput;
    }

    public void setNavigationInput(NavigationInput navigationInput) {
        this.navigationInput = navigationInput;
    }

    public ScrollInput getScrollInput() throws ControlNotInitializedException {
        if (scrollInput == null) throw new ControlNotInitializedException(controlSourceErrors.scrollInputException());
        return scrollInput;
    }

    public void setScrollInput(ScrollInput scrollInput) {
        this.scrollInput = scrollInput;
    }

    public VolumeInput getVolumeInput() throws ControlNotInitializedException {
        if (volumeInput == null) throw new ControlNotInitializedException(controlSourceErrors.volumeInputException());
        return volumeInput;
    }

    public void setVolumeInput(VolumeInput volumeInput) {
        this.volumeInput = volumeInput;
    }

    public OverlayOutput getOverlayOutput() throws ControlNotInitializedException {
        if (overlayOutput == null) throw new ControlNotInitializedException(controlSourceErrors.overlayOutputException());
        return overlayOutput;
    }

    public Optional<OverlayOutput> getOverlayOutputOptional() {
        return Optional.ofNullable(overlayOutput);
    }

    public void setOverlayOutput(OverlayOutput overlayOutput) {
        this.overlayOutput = overlayOutput;
    }
}
