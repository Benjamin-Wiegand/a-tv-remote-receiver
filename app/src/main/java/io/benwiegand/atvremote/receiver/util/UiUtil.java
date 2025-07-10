package io.benwiegand.atvremote.receiver.util;

import static android.util.TypedValue.COMPLEX_UNIT_DIP;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.StringRes;

public class UiUtil {
    private static final String TAG = UiUtil.class.getSimpleName();

    public static final TimeInterpolator EASE_OUT = t -> 1-(t-1f)*(t-1f);
    public static final TimeInterpolator EASE_IN = t -> t*t;
    public static final TimeInterpolator EASE_IN_OUT = chainTimeFunctions(EASE_IN, EASE_OUT);
    public static final TimeInterpolator WIND_UP = t -> 2.70158f*t*t*t - 1.70158f*t*t;
    public static final TimeInterpolator REVERSE = t -> 1 - t;

    public static TimeInterpolator chainTimeFunctions(TimeInterpolator tf, TimeInterpolator... additionalTfs) {
        for (TimeInterpolator func : additionalTfs) {
            TimeInterpolator prevTf = tf;
            tf = x -> func.getInterpolation(prevTf.getInterpolation(x));
        }
        return tf;
    }

    public record ButtonPreset(
            @StringRes int text,
            View.OnClickListener clickListener
    ) {

        public ButtonPreset wrapAction(Runnable after) {
            return new ButtonPreset(text(), v -> {
                if (clickListener() != null)
                    clickListener().onClick(v);
                after.run();
            });
        }

    }

    public static void inflateButtonPreset(Button button, ButtonPreset preset) {
        if (preset == null) {
            button.setVisibility(View.GONE);
            return;
        }

        button.setText(preset.text());
        button.setOnClickListener(preset.clickListener());
        button.setVisibility(View.VISIBLE);
    }

    public static ValueAnimator.AnimatorUpdateListener crtAnimation(View view, int scanLineHeight) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        float targetX = view.getTranslationX();
        float targetY = view.getTranslationY();
        int targetWidth = layoutParams.width;
        int targetHeight = layoutParams.height;

        float startX = view.getTranslationX() + targetWidth / 2f;
        float startY = view.getTranslationY() + targetHeight / 2f;
        float deltaX = targetX - startX;
        float deltaY = targetY - startY;
        int deltaHeight = targetHeight - scanLineHeight;

        return animation -> {
            float t = animation.getAnimatedFraction();
            if (t < 0.5f) {
                // expand horizontal "scan line" out from center
                t = UiUtil.EASE_OUT.getInterpolation(t * 2);
                view.setTranslationX(startX + deltaX * t);
                view.setTranslationY(startY);
                layoutParams.width = (int) (targetWidth * t);
                layoutParams.height = scanLineHeight;
            } else {
                // increase height
                t = UiUtil.EASE_IN_OUT.getInterpolation((t - 0.5f) * 2);
                view.setTranslationX(targetX);
                view.setTranslationY(startY + deltaY * t);
                layoutParams.width = targetWidth;
                layoutParams.height = (int) (scanLineHeight + deltaHeight * t);
            }

            view.setLayoutParams(layoutParams);
        };
    }

    public static float dpToPx(Context context, float dp) {
        return TypedValue.applyDimension(COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }

    public static boolean tryActivityIntents(Context context, Intent... intents) {
        for (Intent intent : intents) {
            try {
                context.startActivity(intent);
                return true;
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "can't find activity for intent: " + intent, e);
            }
        }

        Log.e(TAG, "none of the provided intents were successful");
        return false;
    }

}
