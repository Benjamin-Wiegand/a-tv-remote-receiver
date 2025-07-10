package io.benwiegand.atvremote.receiver.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.concurrent.TimeUnit;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.protocol.PairingCallback;
import io.benwiegand.atvremote.receiver.util.ByteUtil;

public class PairingDialog extends MakeshiftActivity {
    private static final String TAG = PairingDialog.class.getSimpleName();

    private final PairingCallback callback;
    private final int pairingCode;
    private final byte[] fingerprint;

    @SuppressLint("InflateParams")
    public PairingDialog(Context context, PairingCallback callback, int pairingCode, byte[] fingerprint) {
        // todo: use system overlay if system app
        super(context, R.layout.layout_pairing, new WindowManager.LayoutParams(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 0, PixelFormat.TRANSLUCENT));

        assert context.getApplicationContext() == context; // application overlay needs application context

        this.pairingCode = pairingCode;
        this.fingerprint = fingerprint;
        this.callback = callback;

        runOnUiThread(() -> {
            updateText();
            bindButtons();
        });
    }

    @Override
    public void show() {
        runOnUiThread(() -> {
            super.show();
            root.findViewById(R.id.cancel_button).requestFocus();
        });
    }

    private void updateText() {
        TextView fingerprintText = root.findViewById(R.id.certificate_fingerprint_text);
        fingerprintText.setText(ByteUtil.hexOf(fingerprint));

        TextView fingerprintElevatedText = root.findViewById(R.id.certificate_fingerprint_elevated_text);
        fingerprintElevatedText.setText("70 D0"); // todo

        // pad pairing code with leading 0s
        StringBuilder pairingCodeString = new StringBuilder(String.valueOf(pairingCode));
        while (pairingCodeString.length() < 6)
            pairingCodeString.insert(0, '0');

        TextView pairingCodeText = root.findViewById(R.id.pairing_code_text);
        pairingCodeText.setText(pairingCodeString);
    }

    private void bindButtons() {
        root.findViewById(R.id.cancel_button)
                .setOnClickListener(v -> callback.cancel());
        root.findViewById(R.id.cancel_forawhile_button)
                .setOnClickListener(v -> callback.disablePairingForAWhile(TimeUnit.HOURS, 6));
    }
}
