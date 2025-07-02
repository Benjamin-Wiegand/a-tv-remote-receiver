package io.benwiegand.atvremote.receiver.ui;

import static io.benwiegand.atvremote.receiver.util.UiUtil.crtAnimation;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import io.benwiegand.atvremote.receiver.util.UiUtil;

public class FakeFocusOverlay extends MakeshiftActivity {
    private static final String TAG = FakeFocusOverlay.class.getSimpleName();

    private static final long ANIMATE_IN_DURATION = 150;
    private static final long ANIMATE_MOVE_DURATION = 100;
    private static final long ANIMATE_OUT_DURATION = 150;

    private static final long HIGHLIGHT_UPDATE_INTERVAL = 50;

    private static final float FAKE_FOCUS_HIGHLIGHT_STROKE_THICKNESS_DP = 3;
    private static final int FAKE_FOCUS_HIGHLIGHT_MIDDLE_COLOR = 0x4264b5d8;
    private static final int FAKE_FOCUS_HIGHLIGHT_BORDER_COLOR = 0xde4dbeef;

    private View highlightView = null;
    private final Rect highlightRect = new Rect();
    private AccessibilityNodeInfo focusedNode = null;

    public FakeFocusOverlay(Context context) {
        super(context, new FrameLayout(context), new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT));
    }

    @Override
    public void start() {
        super.start();
        hide();
    }

    private int getBorderThickness() {
        return (int) UiUtil.dpToPx(getContext(), FAKE_FOCUS_HIGHLIGHT_STROKE_THICKNESS_DP);
    }

    private View inflateFakeFocusHighlight() {
        int borderThickness = getBorderThickness();

        RectShape borderShape = new RectShape();
        ShapeDrawable borderDrawable = new ShapeDrawable(borderShape);
        borderDrawable.getPaint().setStyle(Paint.Style.STROKE);
        borderDrawable.getPaint().setStrokeWidth(borderThickness * 2);  // half of stroke gets cut off by edge of view
        borderDrawable.getPaint().setColor(FAKE_FOCUS_HIGHLIGHT_BORDER_COLOR);

        RectShape fillShape = new RectShape();
        ShapeDrawable fillDrawable = new ShapeDrawable(fillShape);
        fillDrawable.getPaint().setStyle(Paint.Style.FILL_AND_STROKE);
        fillDrawable.getPaint().setColor(FAKE_FOCUS_HIGHLIGHT_MIDDLE_COLOR);

        LayerDrawable highlightDrawable = new LayerDrawable(new Drawable[] {fillDrawable, borderDrawable});

        View view = new View(getContext());
        view.setBackground(highlightDrawable);

        return view;
    }

    public void drawHighlight(AccessibilityNodeInfo node) {
        runOnUiThread(() -> {
            focusedNode = node;
            if (highlightView != null) return;

            show();
            ViewGroup rootViewGroup = (ViewGroup) root;
            focusedNode.getBoundsInScreen(highlightRect);

            int borderThickness = getBorderThickness();
            float targetX = highlightRect.left - borderThickness;
            float targetY = highlightRect.top - borderThickness;
            int targetWidth = highlightRect.width() + borderThickness * 2;
            int targetHeight = highlightRect.height() + borderThickness * 2;

            highlightView = inflateFakeFocusHighlight();

            highlightView.setTranslationX(targetX);
            highlightView.setTranslationY(targetY);
            FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(targetWidth, targetHeight);
            rootViewGroup.addView(highlightView, layoutParams);

            highlightView.animate()
                    .setDuration(ANIMATE_IN_DURATION)
                    .setUpdateListener(crtAnimation(highlightView, borderThickness))
                    .start();

            getHandler().postDelayed(this::updateHighlightBounds, HIGHLIGHT_UPDATE_INTERVAL);
        });
    }

    private void updateHighlightBounds() {
        Rect oldHighlightRect = new Rect(highlightRect);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (focusedNode == null) return;

                oldHighlightRect.set(highlightRect);
                focusedNode.getBoundsInScreen(highlightRect);
                if (!oldHighlightRect.equals(highlightRect))
                    updateHighlight();

                getHandler().postDelayed(this, HIGHLIGHT_UPDATE_INTERVAL);
            }
        });
    }

    private void updateHighlight() {
        runOnUiThread(() -> {
            Log.d(TAG, "updating highlight");

            int borderThickness = getBorderThickness();
            float targetX = highlightRect.left - borderThickness;
            float targetY = highlightRect.top - borderThickness;
            int targetWidth = highlightRect.width() + borderThickness * 2;
            int targetHeight = highlightRect.height() + borderThickness * 2;

            ViewGroup.LayoutParams layoutParams = highlightView.getLayoutParams();
            int startWidth = layoutParams.width;
            int startHeight = layoutParams.height;

            highlightView.animate()
                    .setDuration(ANIMATE_MOVE_DURATION)
                    .setInterpolator(UiUtil.EASE_OUT)
                    .translationX(targetX)
                    .translationY(targetY)
                    .setUpdateListener(animation -> {
                        layoutParams.width = (int) (startWidth + (targetWidth - startWidth) * animation.getAnimatedFraction());
                        layoutParams.height = (int) (startHeight + (targetHeight - startHeight) * animation.getAnimatedFraction());
                        highlightView.setLayoutParams(layoutParams);
                    })
                    .start();

        });
    }

    public void removeHighlight() {
        runOnUiThread(() -> {
            if (highlightView == null) return;
            ViewGroup rootViewGroup = (ViewGroup) root;

            View oldHighlightView = highlightView;
            highlightView = null;
            focusedNode = null;

            oldHighlightView.animate()
                    .setDuration(ANIMATE_OUT_DURATION)
                    .setInterpolator(UiUtil.REVERSE)
                    .setUpdateListener(crtAnimation(oldHighlightView, getBorderThickness()))
                    .withEndAction(() -> runOnUiThread(() -> {
                        rootViewGroup.removeView(oldHighlightView);
                        if (highlightView == null) hide();
                    }))
                    .start();
        });
    }

}
