package io.benwiegand.atvremote.receiver.control.cursor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.Log;

public class AccessibilityGestureCursor extends FakeCursor {
    private static final String TAG = AccessibilityGestureCursor.class.getSimpleName();

    private GestureDescription.StrokeDescription gestureStroke = null;


    public AccessibilityGestureCursor(AccessibilityService context) {
        super(context);
    }

    private void dispatchStrokeLocked() {
        context.dispatchGesture(
                new GestureDescription.Builder().addStroke(gestureStroke).build(),
                new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        Log.d(TAG, "on gesture completed");
                        super.onCompleted(gestureDescription);
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        Log.w(TAG, "on gesture cancelled");
                        super.onCancelled(gestureDescription);
                    }
                }, handler);
    }

    @Override
    protected void handleDragLocked(int oldX, int oldY) {
        if (gestureStroke == null) return;

        // click and drag
        Log.d(TAG, "STROKING " + oldX + ", " + oldY + " -> " + cursorX + ", " + cursorY);
        Path path = new Path();
        path.moveTo(oldX, oldY);
        path.lineTo(cursorX, cursorY);
        gestureStroke = gestureStroke.continueStroke(path, 0, 1, true);
        dispatchStrokeLocked();
    }

    @Override
    protected void handleMouseDownLocked() {
        // no need to cancel active stroke, this will overwrite it
        Log.d(TAG, "START STROKE: " + cursorX + ", " + cursorY);
        Path path = new Path();
        path.moveTo(cursorX, cursorY);
        gestureStroke = new GestureDescription.StrokeDescription(path, 0, 1, true);
        dispatchStrokeLocked();
    }

    @Override
    protected void handleMouseUpLocked() {
        if (gestureStroke == null) return;

        Log.d(TAG, "END STROKE: " + cursorX + ", " + cursorY);
        Path path = new Path();
        path.moveTo(cursorX, cursorY);
        gestureStroke = gestureStroke.continueStroke(path, 0, 1, false);
        dispatchStrokeLocked();
        gestureStroke = null;
    }

    @Override
    public void destroy() {
        super.destroy();
        synchronized (cursorLock) {
            handleMouseUpLocked();
        }
    }

}
