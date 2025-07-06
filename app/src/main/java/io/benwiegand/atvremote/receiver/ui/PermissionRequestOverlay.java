package io.benwiegand.atvremote.receiver.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.StringRes;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.util.UiUtil;

public class PermissionRequestOverlay extends MakeshiftActivity {
    public record PermissionRequestSpec(
            @StringRes int title,
            @StringRes int subtitle,
            @StringRes int[] features,
            @StringRes int instructionsHeader,
            @StringRes int instructionsDetails,
            UiUtil.ButtonPreset grantButtonPreset,
            Runnable onCancel) {

        public String createFeatureList(Context context) {
            if (features.length == 0) return "";
            StringBuilder sb = new StringBuilder();
            for (int feature : features) sb
                    .append("- ")
                    .append(context.getString(feature))
                    .append("\n");
            return sb.substring(0, sb.length() - 1); // cut off last newline
        }

    }

    private final PermissionRequestSpec permissionRequestSpec;
    private final Runnable onClose;

    public PermissionRequestOverlay(Context context, PermissionRequestSpec permissionRequestSpec, Runnable onClose) {
        super(context, R.layout.layout_permission_request_overlay, new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                0,
                PixelFormat.TRANSLUCENT));

        assert context.getApplicationContext() == context; // application overlay needs application context

        this.permissionRequestSpec = permissionRequestSpec;
        this.onClose = onClose;

        runOnUiThread(this::inflateLayout);
    }

    @Override
    public void show() {
        super.show();
        root.findViewById(R.id.grant_button).requestFocus();
    }

    @Override
    public void destroy() {
        super.destroy();
        onClose.run();
    }

    private void inflateLayout() {
        TextView titleText = root.findViewById(R.id.title_text);
        TextView subtitleText = root.findViewById(R.id.subtitle_text);
        TextView featureListText = root.findViewById(R.id.feature_list_text);
        TextView instructionsHeaderText = root.findViewById(R.id.instructions_header_text);
        TextView instructionsDetailsText = root.findViewById(R.id.instructions_details_text);
        Button grantButton = root.findViewById(R.id.grant_button);
        Button cancelButton = root.findViewById(R.id.cancel_button);

        subtitleText.setText(permissionRequestSpec.subtitle());
        titleText.setText(permissionRequestSpec.title());
        featureListText.setText(permissionRequestSpec.createFeatureList(getContext()));
        instructionsHeaderText.setText(permissionRequestSpec.instructionsHeader());
        instructionsDetailsText.setText(permissionRequestSpec.instructionsDetails());

        UiUtil.inflateButtonPreset(grantButton, permissionRequestSpec.grantButtonPreset().wrapAction(this::destroy));
        cancelButton.setOnClickListener(v -> {
            permissionRequestSpec.onCancel().run();
            destroy();
        });
    }
}
