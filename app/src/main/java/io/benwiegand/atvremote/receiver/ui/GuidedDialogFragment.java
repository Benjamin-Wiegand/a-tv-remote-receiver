package io.benwiegand.atvremote.receiver.ui;

import android.graphics.drawable.Drawable;
import android.os.Bundle;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

import io.benwiegand.atvremote.receiver.util.UiUtil;

public class GuidedDialogFragment extends GuidedStepSupportFragment {

    public record GuidedDialogSpec(
            String title,
            String description,
            String breadcrumb,
            Drawable icon,
            UiUtil.ButtonPreset[] buttons
    ) {}

    private final GuidedDialogSpec spec;

    public GuidedDialogFragment(GuidedDialogSpec spec) {
        this.spec = spec;
    }

    @Override
    public GuidanceStylist.@NonNull Guidance onCreateGuidance(@Nullable Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(
                spec.title(),
                spec.description(),
                spec.breadcrumb(),
                spec.icon()
        );
    }

    @Override
    public void onCreateActions(@NonNull List<GuidedAction> actions, @Nullable Bundle savedInstanceState) {
        super.onCreateActions(actions, savedInstanceState);

        for (int i = 0; i < spec.buttons().length; i++) {
            UiUtil.ButtonPreset preset = spec.buttons()[i];
            actions.add(new GuidedAction.Builder(getContext())
                    .id(i)
                    .title(preset.text())
                    .build());
        }
    }

    @Override
    public void onGuidedActionClicked(@NonNull GuidedAction action) {
        super.onGuidedActionClicked(action);
        if (action.getId() < 0 || action.getId() >= spec.buttons().length) return;
        int i = (int) action.getId();
        spec.buttons()[i].clickListener().onClick(null);

        finishGuidedStepSupportFragments();
    }
}
