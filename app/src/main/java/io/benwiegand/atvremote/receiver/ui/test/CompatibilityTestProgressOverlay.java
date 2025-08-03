package io.benwiegand.atvremote.receiver.ui.test;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.ui.MakeshiftActivity;

public class CompatibilityTestProgressOverlay extends MakeshiftActivity {
    protected CompatibilityTestProgressOverlay(Context context, OverlayMode overlayMode) {
        super(context, R.layout.layout_compatibility_test_progress_overlay, new WindowManager.LayoutParams(
                overlayMode.toLayoutParamsType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT));
    }

    public void logTestResult(String testName, boolean pass) {
        runOnUiThread(() -> {
            TextView resultText = new TextView(getContext());
            String passFailText;
            if (pass) {
                passFailText = getContext().getString(R.string.input_compatibility_test_pass_text);
                resultText.setTextColor(Color.GREEN);
            } else {
                passFailText = getContext().getString(R.string.input_compatibility_test_fail_text);
                resultText.setTextColor(Color.RED);
            }
            resultText.setText(testName + " - " + passFailText);
            ViewGroup resultList = root.findViewById(R.id.test_results_list);
            resultList.addView(resultText);
        });
    }
}
