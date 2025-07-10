package io.benwiegand.atvremote.receiver.control;

import android.content.Context;
import android.util.Log;

import io.benwiegand.atvremote.receiver.control.output.PairingOverlayOutput;
import io.benwiegand.atvremote.receiver.control.output.PermissionRequestOutput;
import io.benwiegand.atvremote.receiver.protocol.PairingCallback;
import io.benwiegand.atvremote.receiver.stuff.Destroyable;
import io.benwiegand.atvremote.receiver.ui.PairingDialog;
import io.benwiegand.atvremote.receiver.ui.PermissionRequestOverlay;

public class ApplicationOverlayOutputHandler implements PermissionRequestOutput, PairingOverlayOutput, Destroyable {
    private static final String TAG = ApplicationOverlayOutputHandler.class.getSimpleName();

    private final Object lock = new Object();

    private final Context context;
    private PermissionRequestOverlay permissionRequestOverlay = null;
    private boolean dead = false;

    public ApplicationOverlayOutputHandler(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean showPermissionDialog(PermissionRequestOverlay.PermissionRequestSpec permissionRequestSpec) {
        synchronized (lock) {
            if (dead) return false;
            if (permissionRequestOverlay != null) return false;
            Log.d(TAG, "showing permission dialog");
            permissionRequestOverlay = new PermissionRequestOverlay(context, permissionRequestSpec, () -> permissionRequestOverlay = null);
            permissionRequestOverlay.start();
            return true;
        }
    }

    @Override
    public void destroy() {
        synchronized (lock) {
            if (dead) return;
            dead = true;
            if (permissionRequestOverlay != null) permissionRequestOverlay.destroy();
        }
    }

    @Override
    public PairingDialog createPairingDialog(PairingCallback callback, int pairingCode, byte[] fingerprint) {
        return new PairingDialog(context, callback, pairingCode, fingerprint);
    }

}
