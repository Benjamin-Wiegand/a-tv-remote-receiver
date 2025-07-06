package io.benwiegand.atvremote.receiver.control.output;

import io.benwiegand.atvremote.receiver.ui.PermissionRequestOverlay;

public interface PermissionRequestOutput extends OutputHandler {
    boolean showPermissionDialog(PermissionRequestOverlay.PermissionRequestSpec permissionRequestSpec);
}
