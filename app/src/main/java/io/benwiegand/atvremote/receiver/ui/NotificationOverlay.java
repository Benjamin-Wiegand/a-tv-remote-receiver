package io.benwiegand.atvremote.receiver.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.util.UiUtil;

public class NotificationOverlay extends MakeshiftActivity {

    public static final long NOTIFICATION_DURATION = 6000L;
    public static final float NOTIFICATION_ALPHA = 0.9f;

    // animation constants
    public static final int FALLBACK_TRANSLATION_X = 1000;
    public static final int FLY_OUT_DELAY = 100;
    public static final int FLY_OUT_DURATION_INNER = 300;
    public static final int FLY_OUT_DURATION_OUTER = 250;
    public static final int CASCADE_DURATION = 250;
    public static final long CASCADE_LAG_MULTIPLIER = 100L;

    private final Object lock = new Object();


    public NotificationOverlay(Context context) {
        // todo: system overlay if system
        super(context, R.layout.layout_notification_overlay, new WindowManager.LayoutParams(
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

    private void insertNotification(LinearLayout notificationList, View notification) {
        runOnUiThread(() -> {
            show();
            synchronized (lock) {
                animateCascadeLocked(notificationList);

                notification.setAlpha(NOTIFICATION_ALPHA);
                notificationList.addView(notification, 0);

                animateFlyOutLocked(notification);

                scheduleRemovalLocked(notificationList, notification, NOTIFICATION_DURATION);
            }
        });
    }

    private void scheduleRemovalLocked(LinearLayout notificationList, View notification, long duration) {
        getHandler().postDelayed(() -> {
            synchronized (lock) {
                notification.getWidth();
                View outline = notification.findViewById(R.id.background_outline);
                View background = notification.findViewById(R.id.inner_background);

                outline.animate()
                        .setDuration(FLY_OUT_DURATION_OUTER)
                        .setInterpolator(UiUtil.WIND_UP)
                        .translationX(outline.getWidth())
                        .start();

                background.animate()
                        .setDuration(FLY_OUT_DURATION_INNER)
                        .setInterpolator(UiUtil.EASE_IN)
                        .translationX(background.getWidth())
                        .withEndAction(() -> {
                            notificationList.removeView(notification);
                            if (notificationList.getChildCount() == 0) {
                                hide();
                            }
                        })
                        .start();
            }
        }, duration);
    }

    private void animateCascadeLocked(LinearLayout notificationList) {
        // cascade existing notifications down
        View firstSibling = notificationList.getChildAt(0);
        if (firstSibling == null) return;

        float margin = firstSibling.getY();

        for (int i = 0; i < notificationList.getChildCount(); i++) {
            View sibling = notificationList.getChildAt(i);
            boolean alreadyAnimating = sibling.getTranslationY() != 0;

            sibling.setTranslationY(-margin - sibling.getHeight() + sibling.getTranslationY());
            sibling.animate()
                    .setStartDelay(alreadyAnimating ? 0 : i * CASCADE_LAG_MULTIPLIER)
                    .setInterpolator(alreadyAnimating ? UiUtil.EASE_OUT : UiUtil.EASE_IN_OUT)
                    .setDuration(CASCADE_DURATION)
                    .translationY(0)
                    .start();
        }
    }

    private void animateFlyOutLocked(View notification) {
        // notification fly out
        View outline = notification.findViewById(R.id.background_outline);
        View background = notification.findViewById(R.id.inner_background);

        outline.setTranslationX(FALLBACK_TRANSLATION_X);
        outline.animate()
                .setStartDelay(FLY_OUT_DELAY)
                .setDuration(FLY_OUT_DURATION_OUTER)
                .setInterpolator(UiUtil.EASE_OUT)
                .withStartAction(() -> outline.setTranslationX(outline.getWidth()))
                .translationX(0)
                .start();

        background.setTranslationX(FALLBACK_TRANSLATION_X);
        background.animate()
                .setStartDelay(FLY_OUT_DELAY)
                .setDuration(FLY_OUT_DURATION_INNER)
                .setInterpolator(UiUtil.EASE_OUT)
                .withStartAction(() -> {
                    background.setTranslationX(background.getWidth());
                    background.setAlpha(0f);
                })
                .translationX(0)
                .alpha(1f)
                .start();
    }

    public void displayNotification(String title, String description, @DrawableRes int icon) {
        LinearLayout notificationList = root.findViewById(R.id.notification_list);
        View notification = getLayoutInflater().inflate(R.layout.layout_notification, notificationList, false);

        TextView titleText = notification.findViewById(R.id.notification_title);
        titleText.setText(title);

        TextView descriptionText = notification.findViewById(R.id.notification_description);
        descriptionText.setText(description);

        ImageView iconView = notification.findViewById(R.id.notification_icon);
        iconView.setImageResource(icon);

        insertNotification(notificationList, notification);
    }

    public void displayNotification(@StringRes int title, @StringRes int description, @DrawableRes int icon) {
        displayNotification(getContext().getString(title), getContext().getString(description), icon);
    }

    public void displayNotification(String title, @StringRes int description, @DrawableRes int icon) {
        displayNotification(title, getContext().getString(description), icon);
    }

    public void displayNotification(@StringRes int title, String description, @DrawableRes int icon) {
        displayNotification(getContext().getString(title), description, icon);
    }

}
