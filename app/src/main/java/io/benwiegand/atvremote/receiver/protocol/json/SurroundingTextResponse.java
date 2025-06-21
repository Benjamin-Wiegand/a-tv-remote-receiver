package io.benwiegand.atvremote.receiver.protocol.json;

import android.os.Build;
import android.view.inputmethod.SurroundingText;

import androidx.annotation.RequiresApi;

/**
 * serializable object for {@link android.view.inputmethod.SurroundingText}
 */
public record SurroundingTextResponse(
        int selectionStart,
        int selectionEnd,
        int offset,
        String text) {

    @RequiresApi(api = Build.VERSION_CODES.S)
    public SurroundingTextResponse(SurroundingText surroundingText) {
        this(surroundingText.getSelectionStart(),
                surroundingText.getSelectionEnd(),
                surroundingText.getOffset(),
                surroundingText.getText().toString());
    }
}
