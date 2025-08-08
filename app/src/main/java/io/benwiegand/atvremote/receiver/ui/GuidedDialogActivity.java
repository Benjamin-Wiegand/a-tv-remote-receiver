package io.benwiegand.atvremote.receiver.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.fragment.app.FragmentActivity;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.util.UiUtil;

public class GuidedDialogActivity extends FragmentActivity {
    private static final String TAG = GuidedDialogActivity.class.getSimpleName();

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_DESCRIPTION = "description";
    public static final String EXTRA_BREADCRUMB = "breadcrumb";
    public static final String EXTRA_ICON_RESOURCE = "icon_res";
    public static final String BUTTON_TEXT_RESOURCES = "buttons";

    public static final String EXTRA_RESULT_BUTTON_INDEX = "pressed";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guided_dialog);

        Intent intent = getIntent();

        Drawable icon = null;
        if (intent.hasExtra(EXTRA_ICON_RESOURCE)) {
            icon = AppCompatResources.getDrawable(this, intent.getIntExtra(EXTRA_ICON_RESOURCE, -1));
        }

        int[] buttonTexts = intent.getIntArrayExtra(BUTTON_TEXT_RESOURCES);
        if (buttonTexts == null) {
            Log.wtf(TAG, "no buttons provided in launch intent");
            finish();
            assert false;
            return;
        }

        UiUtil.ButtonPreset[] buttons = new UiUtil.ButtonPreset[buttonTexts.length];
        for (int i = 0; i < buttonTexts.length; i++) {
            int buttonId = i;
            buttons[i] = new UiUtil.ButtonPreset(
                    buttonTexts[i],
                    v -> onButton(buttonId)
            );
        }

        GuidedDialogFragment.GuidedDialogSpec spec = new GuidedDialogFragment.GuidedDialogSpec(
                intent.getStringExtra(EXTRA_TITLE),
                intent.getStringExtra(EXTRA_DESCRIPTION),
                intent.getStringExtra(EXTRA_BREADCRUMB),
                icon,
                buttons
        );

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.root, new GuidedDialogFragment(spec))
                .commitNow();

    }

    private void onButton(int i) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT_BUTTON_INDEX, i));
        finish();
    }

    public record RequestData(
            String title,
            String description,
            String breadcrumb,
            @DrawableRes Integer iconRes,
            Runnable onCancel,
            UiUtil.ButtonPreset[] buttons
    ) {
        private static String getStringRes(Resources res, @StringRes Integer str) {
            if (str == null) return null;
            return res.getString(str);
        }

        public RequestData(Resources res, @StringRes Integer title, @StringRes Integer description,
                           @StringRes Integer breadcrumb, @DrawableRes Integer iconRes,
                           Runnable onCancel, UiUtil.ButtonPreset... buttons) {
            this(getStringRes(res, title), getStringRes(res, description), getStringRes(res, breadcrumb), iconRes, onCancel, buttons);
        }
    }

    public static class Contract extends ActivityResultContract<RequestData, Void> {
        private RequestData requestData = null;

        @NonNull
        @Override
        public Intent createIntent(@NonNull Context context, RequestData data) {
            requestData = data;
            int[] buttonResources = new int[data.buttons().length];
            for (int i = 0; i < data.buttons().length; i++)
                buttonResources[i] = data.buttons()[i].text();

            Intent intent = new Intent(context, GuidedDialogActivity.class)
                    .putExtra(EXTRA_TITLE, data.title())
                    .putExtra(EXTRA_DESCRIPTION, data.description())
                    .putExtra(EXTRA_BREADCRUMB, data.breadcrumb())
                    .putExtra(BUTTON_TEXT_RESOURCES, buttonResources);

            if (data.iconRes() != null)
                intent.putExtra(EXTRA_ICON_RESOURCE, data.iconRes());

            return intent;
        }

        @Override
        public Void parseResult(int code, @Nullable Intent intent) {
            if (code == RESULT_CANCELED || intent == null) {
                requestData.onCancel().run();
                return null;
            }

            int resultButtonIndex = intent.getIntExtra(EXTRA_RESULT_BUTTON_INDEX, -1);
            if (resultButtonIndex < 0 || resultButtonIndex >= requestData.buttons().length) {
                requestData.onCancel().run();
                return null;
            }

            requestData.buttons()[resultButtonIndex]
                    .clickListener()
                    .onClick(null);
            return null;
        }
    }
}
