package io.benwiegand.atvremote.receiver.control.cursor;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.appcompat.content.res.AppCompatResources;

import io.benwiegand.atvremote.receiver.R;

public abstract class FakeCursor implements CursorController {
    private static final String TAG = FakeCursor.class.getSimpleName();
    private static final long HIDE_CURSOR_AFTER = 10000;
    private static final long HIDE_CURSOR_POLL_INTERVAL = 1000;

    private static final WindowManager.LayoutParams CURSOR_LAYOUT_PARAMS = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);

    protected final Handler handler = new Handler(Looper.getMainLooper());
    protected AccessibilityService context;

    protected final Object cursorLock = new Object();
    protected int cursorX = 0;
    protected int cursorY = 0;
    private View overlayView = null;
    private boolean destroyed = false;
    private long cursorLastTouched = 0;


    public FakeCursor(AccessibilityService context) {
        this.context = context;
    }

    protected abstract void handleDragLocked(int oldX, int oldY);
    protected abstract void handleMouseDownLocked();
    protected abstract void handleMouseUpLocked();

    @SuppressLint("InflateParams")
    private View inflateCursor() {
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.layout_cursor, null);
        // todo: fix cutout (tvs don't usually have cutouts so this isn't too important)

        // set image manually because there's no root for the inflater to derive it from
        ImageView cursor = view.findViewById(R.id.cursor);
        Drawable cursorImage = AppCompatResources.getDrawable(context, R.drawable.mouse);
        cursor.setImageDrawable(cursorImage);

        // make sure cursor is in the right position
        cursor.setTranslationX(cursorX);
        cursor.setTranslationY(cursorY);

        return view;
    }

    public void keepCursorVisible() {
        synchronized (cursorLock) {
            cursorLastTouched = SystemClock.elapsedRealtime();
            showCursor();
        }
    }

    private void hideCursorAfterTimeout() {
        synchronized (cursorLock) {
            if (SystemClock.elapsedRealtime() - cursorLastTouched >= HIDE_CURSOR_AFTER)
                hideCursor();
            else
                handler.postDelayed(this::hideCursorAfterTimeout, HIDE_CURSOR_POLL_INTERVAL);
        }
    }

    @Override
    public void showCursor() {
        if (overlayView != null) return;

        handler.post(() -> {
            View view = inflateCursor();
            WindowManager wm = context.getSystemService(WindowManager.class);

            synchronized (cursorLock) {
                if (destroyed) return;
                if (overlayView != null) return;
                wm.addView(view, CURSOR_LAYOUT_PARAMS);
                overlayView = view;

                handler.postDelayed(this::hideCursorAfterTimeout, HIDE_CURSOR_AFTER);
            }
        });
    }

    @Override
    public void hideCursor() {
        if (overlayView == null) return;

        handler.post(() -> {
            WindowManager wm = context.getSystemService(WindowManager.class);

            synchronized (cursorLock) {
                if (overlayView == null) return;
                wm.removeView(overlayView);
                overlayView = null;
            }
        });
    }

    @Override
    public void cursorMove(int x, int y) {
        keepCursorVisible();

        handler.post(() -> {
            synchronized (cursorLock) {
                if (overlayView == null) return;

                int oldX = cursorX;
                int oldY = cursorY;
                cursorX += x;
                cursorY += y;

                // limit cursor to overlay bounds
                int width = overlayView.getWidth();
                int height = overlayView.getHeight();
                if (width != 0 && height != 0) {    // dimensions may be 0 for a brief period while showing
                    if (cursorX > width) cursorX = width;
                    else if (cursorX < 0) cursorX = 0;
                    if (cursorY > height) cursorY = height;
                    else if (cursorY < 0) cursorY = 0;
                }

                // update visible cursor
                View cursor = overlayView.findViewById(R.id.cursor);
                cursor.setTranslationX(cursorX);
                cursor.setTranslationY(cursorY);

                handleDragLocked(oldX, oldY);
            }
        });
    }

    @Override
    public void cursorDown() {
        keepCursorVisible();

        handler.post(() -> {
            synchronized (cursorLock) {
                if (overlayView == null) return;
                handleMouseDownLocked();
            }
        });

    }

    @Override
    public void cursorUp() {
        keepCursorVisible();

        handler.post(() -> {
            synchronized (cursorLock) {
                if (overlayView == null) return;
                handleMouseUpLocked();
            }
        });
    }

    @Override
    public void destroy() {
        Log.d(TAG, "destroy()");
        synchronized (cursorLock) {
            destroyed = true;
            if (overlayView != null) {
                WindowManager wm = context.getSystemService(WindowManager.class);
                wm.removeView(overlayView);
                overlayView = null;
            }
        }

        context = null;
    }
}
