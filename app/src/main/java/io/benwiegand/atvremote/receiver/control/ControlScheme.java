package io.benwiegand.atvremote.receiver.control;

import java.util.Optional;

import io.benwiegand.atvremote.receiver.control.input.ActivityLauncherInput;
import io.benwiegand.atvremote.receiver.control.input.BackNavigationInput;
import io.benwiegand.atvremote.receiver.control.input.CursorInput;
import io.benwiegand.atvremote.receiver.control.input.DirectionalPadInput;
import io.benwiegand.atvremote.receiver.control.input.KeyboardInput;
import io.benwiegand.atvremote.receiver.control.input.MediaInput;
import io.benwiegand.atvremote.receiver.control.input.FullNavigationInput;
import io.benwiegand.atvremote.receiver.control.input.ScrollInput;
import io.benwiegand.atvremote.receiver.control.input.VolumeInput;
import io.benwiegand.atvremote.receiver.control.output.OverlayOutput;
import io.benwiegand.atvremote.receiver.control.output.PairingOverlayOutput;
import io.benwiegand.atvremote.receiver.control.output.PermissionRequestOutput;

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
    private ControlHandlerSupplier<ActivityLauncherInput> activityLauncherInputSupplier;
    private ControlHandlerSupplier<CursorInput> cursorInputSupplier;
    private ControlHandlerSupplier<DirectionalPadInput> directionalPadInputSupplier;
    private ControlHandlerSupplier<KeyboardInput> keyboardInputSupplier;
    private ControlHandlerSupplier<MediaInput> mediaInputSupplier;
    private ControlHandlerSupplier<FullNavigationInput> fullNavigationInputSupplier;
    private ControlHandlerSupplier<BackNavigationInput> backNavigationInputSupplier;
    private ControlHandlerSupplier<ScrollInput> scrollInputSupplier;
    private ControlHandlerSupplier<VolumeInput> volumeInputSupplier;
    // todo: power (sleep and menu)

    // output
    private ControlHandlerSupplier<OverlayOutput> overlayOutputSupplier;
    private ControlHandlerSupplier<PermissionRequestOutput> permissionRequestOutputSupplier;
    private ControlHandlerSupplier<PairingOverlayOutput> pairingOverlayOutputSupplier;

    public ControlScheme(ControlHandlerSupplier<ActivityLauncherInput> activityLauncherInputSupplier, ControlHandlerSupplier<CursorInput> cursorInputSupplier, ControlHandlerSupplier<DirectionalPadInput> directionalPadInputSupplier, ControlHandlerSupplier<KeyboardInput> keyboardInputSupplier, ControlHandlerSupplier<MediaInput> mediaInputSupplier, ControlHandlerSupplier<FullNavigationInput> fullNavigationInputSupplier, ControlHandlerSupplier<BackNavigationInput> backNavigationInputSupplier, ControlHandlerSupplier<ScrollInput> scrollInputSupplier, ControlHandlerSupplier<VolumeInput> volumeInputSupplier, ControlHandlerSupplier<OverlayOutput> overlayOutputSupplier, ControlHandlerSupplier<PermissionRequestOutput> permissionRequestOutputSupplier, ControlHandlerSupplier<PairingOverlayOutput> pairingOverlayOutputSupplier) {
        this.activityLauncherInputSupplier = activityLauncherInputSupplier;
        this.cursorInputSupplier = cursorInputSupplier;
        this.directionalPadInputSupplier = directionalPadInputSupplier;
        this.keyboardInputSupplier = keyboardInputSupplier;
        this.mediaInputSupplier = mediaInputSupplier;
        this.fullNavigationInputSupplier = fullNavigationInputSupplier;
        this.backNavigationInputSupplier = backNavigationInputSupplier;
        this.scrollInputSupplier = scrollInputSupplier;
        this.volumeInputSupplier = volumeInputSupplier;
        this.overlayOutputSupplier = overlayOutputSupplier;
        this.permissionRequestOutputSupplier = permissionRequestOutputSupplier;
        this.pairingOverlayOutputSupplier = pairingOverlayOutputSupplier;
    }

    /**
     * updates this control scheme to make it the same as the provided new control scheme.
     * this allows the entire control scheme to be update atomically without having to restart anything.
     * @param newControlScheme the control scheme to "become" (more or less)
     */
    public void update(ControlScheme newControlScheme) {
        activityLauncherInputSupplier = newControlScheme.activityLauncherInputSupplier;
        cursorInputSupplier = newControlScheme.cursorInputSupplier;
        directionalPadInputSupplier = newControlScheme.directionalPadInputSupplier;
        keyboardInputSupplier = newControlScheme.keyboardInputSupplier;
        mediaInputSupplier = newControlScheme.mediaInputSupplier;
        fullNavigationInputSupplier = newControlScheme.fullNavigationInputSupplier;
        backNavigationInputSupplier = newControlScheme.backNavigationInputSupplier;
        scrollInputSupplier = newControlScheme.scrollInputSupplier;
        volumeInputSupplier = newControlScheme.volumeInputSupplier;
        overlayOutputSupplier = newControlScheme.overlayOutputSupplier;
        permissionRequestOutputSupplier = newControlScheme.permissionRequestOutputSupplier;
        pairingOverlayOutputSupplier = newControlScheme.pairingOverlayOutputSupplier;
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

    public FullNavigationInput getFullNavigationInput() throws ControlNotInitializedException {
        return fullNavigationInputSupplier.get();
    }

    public BackNavigationInput getBackNavigationInput() throws ControlNotInitializedException {
        return backNavigationInputSupplier.get();
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
        try {
            return Optional.ofNullable(overlayOutputSupplier.get());
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    public Optional<PermissionRequestOutput> getPermissionRequestOutputOptional() {
        try {
            return Optional.ofNullable(permissionRequestOutputSupplier.get());
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    public PairingOverlayOutput getPairingOverlayOutput() throws ControlNotInitializedException {
        return pairingOverlayOutputSupplier.get();
    }

}
