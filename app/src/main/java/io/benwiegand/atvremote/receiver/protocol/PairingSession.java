package io.benwiegand.atvremote.receiver.protocol;

import java.util.List;

import io.benwiegand.atvremote.receiver.ui.PairingDialog;

public record PairingSession(PairingDialog dialog, List<Runnable> cancelCallbacks, int pairingCode, byte[] fingerprint) {
}
