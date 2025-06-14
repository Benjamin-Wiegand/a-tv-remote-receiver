package io.benwiegand.atvremote.receiver.ui;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.benwiegand.atvremote.receiver.R;

public class DebugOverlay extends MakeshiftActivity {

    private final Map<String, View> rectangles = new HashMap<>();

    public DebugOverlay(Context context) {
        super(context, R.layout.layout_debug_overlay, new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT));

    }

    private View inflateRectangle(ViewGroup parent, int width, int height, int left, int top, int color) {
        RectShape rectShape = new RectShape();
        rectShape.resize(width, height);

        ShapeDrawable bg = new ShapeDrawable(rectShape);
        bg.getPaint().setStyle(Paint.Style.STROKE);
        bg.getPaint().setStrokeWidth(10);
        bg.getPaint().setColor(color);

        View rectView = new View(getContext());
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(width, height);
        layoutParams.setMargins(left, top, 0, 0);

        rectView.setBackground(bg);
        rectView.setAlpha(0.5f);

        parent.addView(rectView, layoutParams);
        return rectView;
    }

    public void removeRect(String key) {
        runOnUiThread(() -> {
            ViewGroup rootViewGroup = (ViewGroup) root;
            View oldRect = rectangles.remove(key);
            if (oldRect != null)
                rootViewGroup.removeView(oldRect);
        });
    }

    public void drawRect(String key, Rect rect, int color) {
        int width = rect.width(), height = rect.height(), left = rect.left, top = rect.top;
        runOnUiThread(() -> {
            ViewGroup rootViewGroup = (ViewGroup) root;

            View rectView = inflateRectangle(rootViewGroup, width, height, left, top, color);

            removeRect(key);
            rectangles.put(key, rectView);
        });
    }

    public void drawRectGroup(String key, List<Rect> rects, int color) {
        runOnUiThread(() -> {
            ViewGroup rootViewGroup = (ViewGroup) root;

            FrameLayout rectViewGroup = new FrameLayout(getContext());

            for (Rect rect : rects) {
                inflateRectangle(rectViewGroup, rect.width(), rect.height(), rect.left, rect.top, color);
            }

            rootViewGroup.addView(rectViewGroup);

            removeRect(key);
            rectangles.put(key, rectViewGroup);
        });
    }
}
