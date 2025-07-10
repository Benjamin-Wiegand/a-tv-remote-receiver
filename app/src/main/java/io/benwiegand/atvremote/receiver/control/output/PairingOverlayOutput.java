package io.benwiegand.atvremote.receiver.control.output;

import io.benwiegand.atvremote.receiver.protocol.PairingCallback;
import io.benwiegand.atvremote.receiver.ui.PairingDialog;

public interface PairingOverlayOutput extends OutputHandler {
    PairingDialog createPairingDialog(PairingCallback callback, int pairingCode, byte[] fingerprint);
}
