package io.benwiegand.atvremote.receiver.ui.test;


import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import java.util.Optional;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.control.ControlSourceConnector;

public class InputTestActivity extends FragmentActivity {
    private static final String TAG = InputTestActivity.class.getSimpleName();

    /**
     *  sits over text layer
     */
    private static final float TEST_CONTAINER_ALPHA = 0.3f;

    private static final float TEST_IN_PROGRESS_NOTICE_SIZE = 42;


    private static final FrameLayout.LayoutParams FRAME_LAYOUT_MATCH_PARENT = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

    private ViewNavigationCompatibilityTest activeTest = null;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private FrameLayout testContainer;
    private LinearLayout textContainer;

    private ControlSourceConnector controlSourceConnector;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        controlSourceConnector = new ControlSourceConnector(this, b -> {});

        root = new FrameLayout(this);
        setContentView(root);

        textContainer = new LinearLayout(this);
        textContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(textContainer, FRAME_LAYOUT_MATCH_PARENT);

        TextView notice = new TextView(this);
        notice.setText(R.string.input_compatibility_test_in_progress_text);
        notice.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        notice.setTextSize(TEST_IN_PROGRESS_NOTICE_SIZE);
        textContainer.addView(notice);

        testContainer = new FrameLayout(this);
        testContainer.setAlpha(TEST_CONTAINER_ALPHA);
        root.addView(testContainer, FRAME_LAYOUT_MATCH_PARENT);

        // todo: fix arbitrary delay
        handler.postDelayed(() -> {
            testImeFocusBug(testContainer);
        }, 1000);

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        Optional.ofNullable(activeTest)
                .ifPresent(ViewNavigationCompatibilityTest::cancelTest);

        controlSourceConnector.destroy();
    }

    private void testFakeDpad(FrameLayout container) {

        BasicButtonGridDpadTest test = new BasicButtonGridDpadTest(
                container,
                FRAME_LAYOUT_MATCH_PARENT,
                this::onTestFinished,
                controlSourceConnector.getAccessibilityFakeDirectionalPadInput());
        test.startTest();

    }

    private void testImeFocusBug(FrameLayout container) {

        ImeFocusBugTest test = new ImeFocusBugTest(
                container,
                FRAME_LAYOUT_MATCH_PARENT,
                this::onTestFinished,
                getSupportFragmentManager(),
                controlSourceConnector.getImeDirectionalPadInput());
        test.startTest();

    }

    private void onTestFinished(boolean pass) {
        TextView resultText = new TextView(this);
        resultText.setTextSize(24);
        if (pass) {
            resultText.setText(R.string.input_compatibility_test_pass_text);
            resultText.setTextColor(Color.GREEN);
        } else {
            resultText.setText(R.string.input_compatibility_test_fail_text);
            resultText.setTextColor(Color.RED);
        }
        textContainer.addView(resultText);

        activeTest = null;
    }

}
