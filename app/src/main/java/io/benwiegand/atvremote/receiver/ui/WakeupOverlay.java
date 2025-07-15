package io.benwiegand.atvremote.receiver.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.View;
import android.view.WindowManager;

public class WakeupOverlay extends MakeshiftActivity {
    private WakeupOverlay(Context context) {
        super(context, new View(context), new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                PixelFormat.TRANSLUCENT));
    }

    public static void triggerWakeup(Context accessibilityContext) {
        WakeupOverlay overlay = new WakeupOverlay(accessibilityContext);
        overlay.start();
        overlay.destroy();
    }
}
