package io.benwiegand.atvremote.receiver.control.output;

import android.content.Context;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import io.benwiegand.atvremote.receiver.protocol.PairingCallback;
import io.benwiegand.atvremote.receiver.ui.PairingDialog;

public interface OverlayOutput extends OutputHandler {
    PairingDialog createPairingDialog(PairingCallback callback, int pairingCode, byte[] fingerprint);

    void displayNotification(String title, String description, @DrawableRes int icon);
    void displayNotification(@StringRes int title, @StringRes int description, @DrawableRes int icon);
    void displayNotification(String title, @StringRes int description, @DrawableRes int icon);
    void displayNotification(@StringRes int title, String description, @DrawableRes int icon);
}
