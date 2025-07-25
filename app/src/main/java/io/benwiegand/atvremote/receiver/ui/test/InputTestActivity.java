package io.benwiegand.atvremote.receiver.ui.test;


import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.async.PendingSec;
import io.benwiegand.atvremote.receiver.async.SecAdapter;
import io.benwiegand.atvremote.receiver.control.ControlHandler;
import io.benwiegand.atvremote.receiver.control.ControlSourceConnector;
import io.benwiegand.atvremote.receiver.control.input.DirectionalPadInput;

public class InputTestActivity extends FragmentActivity {
    private static final String TAG = InputTestActivity.class.getSimpleName();

    /**
     *  sits over text layer
     */
    private static final float TEST_CONTAINER_ALPHA = 0.3f;

    private static final float TEST_IN_PROGRESS_NOTICE_SIZE = 42;

    private static final long CONTROL_SOURCE_TIMEOUT = 2000;


    private static final FrameLayout.LayoutParams FRAME_LAYOUT_MATCH_PARENT = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

    private ViewNavigationCompatibilityTest activeTest = null;

    private final Executor redExecutor = r -> new Thread(r).start();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private FrameLayout testContainer;
    private LinearLayout textContainer;

    private ControlSourceConnector controlSourceConnector;

    private record Test(String testName, PendingSec<Boolean> pendingSec) { }

    private final Queue<Test> tests = new LinkedList<>();

    private void generateTestList() {

        // basic test of all the dpads to see what works
        tests.add(new Test(
                "IME DPAD basic test",
                getControlHandler(ControlSourceConnector::getImeDirectionalPadInput)
                        .flatMap(this::basicDpadTest)));
        tests.add(new Test(
                "Accessibility assisted IME DPAD basic test",
                getControlHandler(ControlSourceConnector::getAccessibilityAssistedImeDirectionalPadInput)
                        .flatMap(this::basicDpadTest)));
        tests.add(new Test(
                "Accessibility fake DPAD basic test",
                getControlHandler(ControlSourceConnector::getAccessibilityFakeDirectionalPadInput)
                        .flatMap(this::basicDpadTest)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            tests.add(new Test(
                    "Accessibility DPAD basic test",
                    getControlHandler(ControlSourceConnector::getAccessibilityAccDirectionalPadInput)
                            .flatMap(this::basicDpadTest)));
        }

        // test focus bug that seems to happen on android 12 and below
        // makes apps like settings completely unusable without assistance
        tests.add(new Test(
                "IME DPAD - IME focus bug test",
                getControlHandler(ControlSourceConnector::getImeDirectionalPadInput)
                        .flatMap(this::imeFocusBugTest)));

        // verify that the assisted ime dpad is able to overcome the concern from the previous test
        // if it is unable to listen for UI changes or accurately detect when the ime bug happens, using it would be worse than using ime with the bug
        tests.add(new Test(
                "Accessibility assisted IME DPAD - IME focus bug test",
                getControlHandler(ControlSourceConnector::getAccessibilityAssistedImeDirectionalPadInput)
                        .flatMap(this::imeFocusBugTest)));

        // the accessibility dpad gets trapped in text boxes on some devices, which can be annoying for the user
        // if this is a case, a fix is needed. todo: that fix hasn't been implemented yet
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            tests.add(new Test(
                    "Accessibility DPAD - text editor trap bug test",
                    getControlHandler(ControlSourceConnector::getAccessibilityAccDirectionalPadInput)
                            .flatMap(this::textViewTrapTest)));
        }

    }

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

        generateTestList();

        startNextTest();

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        Optional.ofNullable(activeTest)
                .ifPresent(ViewNavigationCompatibilityTest::cancelTest);

        controlSourceConnector.destroy();
    }

    private void startNextTest() {

        runOnUiThread(() -> testContainer.removeAllViews());

        Test test = tests.poll();
        if (test == null) {
            Log.i(TAG, "no more tests");
            return;
        }

        Log.i(TAG, "running test: " + test.testName());
        test.pendingSec()
                .start()
                .doOnResult(pass -> {
                    logTestResultOnscreen(test.testName(), pass);
                    startNextTest();
                })
                .doOnError(t -> {
                    Log.e(TAG, "failed to start test", t);
                    logTestResultOnscreen(test.testName(), false);
                    startNextTest();
                })
                .callMeWhenDone();

    }

    private void logTestResultOnscreen(String testName, boolean pass) {
        runOnUiThread(() -> {
            TextView resultText = new TextView(this);
            String passFailText;
            if (pass) {
                passFailText = getString(R.string.input_compatibility_test_pass_text);
                resultText.setTextColor(Color.GREEN);
            } else {
                passFailText = getString(R.string.input_compatibility_test_fail_text);
                resultText.setTextColor(Color.RED);
            }
            resultText.setText(testName + " - " + passFailText);
            textContainer.addView(resultText);
        });
    }

    private <T extends ControlHandler> PendingSec<T> getControlHandler(Function<ControlSourceConnector, T> getter) {
        return SecAdapter.createSimple(redExecutor, () -> {
            T controlHandler = controlSourceConnector.waitForControl(getter, InputTestActivity.CONTROL_SOURCE_TIMEOUT);
            if (controlHandler == null) throw new TimeoutException("timed out waiting for control handler");
            return controlHandler;
        });
    }

    private PendingSec<Boolean> basicDpadTest(DirectionalPadInput directionalPadInput) {
        return SecAdapter.create(handler, secAdapter -> {
            try {
                BasicButtonGridDpadTest test = new BasicButtonGridDpadTest(
                        testContainer,
                        FRAME_LAYOUT_MATCH_PARENT,
                        secAdapter::provideResult,
                        directionalPadInput);
                test.startTest();
                activeTest = test;
            } catch (RuntimeException e) {
                secAdapter.throwError(e);
            }
        });
    }

    private PendingSec<Boolean> imeFocusBugTest(DirectionalPadInput directionalPadInput) {
        return SecAdapter.create(handler, secAdapter -> {
            try {
                ImeFocusBugTest test = new ImeFocusBugTest(
                        testContainer,
                        FRAME_LAYOUT_MATCH_PARENT,
                        secAdapter::provideResult,
                        getSupportFragmentManager(),
                        directionalPadInput);
                test.startTest();
                activeTest = test;
            } catch (RuntimeException e) {
                secAdapter.throwError(e);
            }
        });
    }

    private PendingSec<Boolean> textViewTrapTest(DirectionalPadInput directionalPadInput) {
        return SecAdapter.create(handler, secAdapter -> {
            try {
                DpadTextTrapBugTest test = new DpadTextTrapBugTest(
                        testContainer,
                        FRAME_LAYOUT_MATCH_PARENT,
                        secAdapter::provideResult,
                        directionalPadInput);
                test.startTest();
                activeTest = test;
            } catch (RuntimeException e) {
                secAdapter.throwError(e);
            }
        });
    }

}
