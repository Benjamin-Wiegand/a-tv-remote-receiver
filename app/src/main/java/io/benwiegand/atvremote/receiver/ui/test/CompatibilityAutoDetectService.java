package io.benwiegand.atvremote.receiver.ui.test;

import static io.benwiegand.atvremote.receiver.util.UiUtil.FRAME_LAYOUT_MATCH_PARENT;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.StringRes;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.async.PendingSec;
import io.benwiegand.atvremote.receiver.async.SecAdapter;
import io.benwiegand.atvremote.receiver.control.AccessibilityInputService;
import io.benwiegand.atvremote.receiver.control.ControlHandler;
import io.benwiegand.atvremote.receiver.control.ControlSourceConnector;
import io.benwiegand.atvremote.receiver.control.input.DirectionalPadInput;
import io.benwiegand.atvremote.receiver.protocol.KeyEventType;
import io.benwiegand.atvremote.receiver.stuff.makeshiftbind.MakeshiftServiceConnection;
import io.benwiegand.atvremote.receiver.ui.MakeshiftActivity;
import io.benwiegand.atvremote.receiver.ui.test.feature.MenuFeatureCompatibilityTest;
import io.benwiegand.atvremote.receiver.ui.test.navigation.BasicButtonGridDpadTest;
import io.benwiegand.atvremote.receiver.ui.test.navigation.DpadTextTrapBugTest;
import io.benwiegand.atvremote.receiver.ui.test.navigation.ImeFocusBugTest;

public class CompatibilityAutoDetectService extends Service {
    private static final String TAG = CompatibilityAutoDetectService.class.getSimpleName();

    private static final long CONTROL_SOURCE_TIMEOUT = 2000;

    private ControlSourceConnector controlSourceConnector;
    private final MakeshiftServiceConnection accessibilityServiceConnection = new AccessibilityServiceConnection();
    private AccessibilityInputService.AccessibilityInputHandler accessibilityBinder = null;
    private final Binder binder = new ServiceBinder();

    private Runnable cancelCurrentTest = () -> {};

    private enum TestType {
        INPUT_NAVIGATION,
        FEATURE_PRESENCE;

        public boolean shouldShowLog() {
            return this == INPUT_NAVIGATION || this == FEATURE_PRESENCE;
        }
    }

    private record Test<T>(
            int id,
            String name,
            TestType type,
            Function<InputTestActivity, PendingSec<T>> creator
    ) {
        public Test(Context context, @StringRes int nameRes, TestType type, Function<InputTestActivity, PendingSec<T>> creator) {
            this(nameRes, context.getString(nameRes), type, creator);
        }
    }

    private final Queue<Test<?>> inputCompatibilityTests = new LinkedList<>();

    private final Map<Integer, Object> compatibilityTestResults = new HashMap<>();

    private CompatibilityTestProgressOverlay testProgressOverlay = null;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Executor redExecutor = r -> new Thread(r).start();

    @Override
    public void onCreate() {
        super.onCreate();
        controlSourceConnector = new ControlSourceConnector(this, b -> {});
        MakeshiftServiceConnection.bindService(this, new ComponentName(this, AccessibilityInputService.class), accessibilityServiceConnection);

        generateInputCompatibilityTestQueue();
        generateFeatureInputCompatibilityTestQueue();

        if (Settings.canDrawOverlays(getApplicationContext())) {
            testProgressOverlay = new CompatibilityTestProgressOverlay(getApplicationContext(), MakeshiftActivity.OverlayMode.APPLICATION_OVERLAY);
            testProgressOverlay.show();
        } else {
            // todo: test should wait for accessibility connection
        }


        startActivity(new Intent(getApplicationContext(), InputTestActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        cancelCurrentTest.run();
        controlSourceConnector.destroy();
        accessibilityServiceConnection.destroy();
        getTestProgressOverlay().ifPresent(MakeshiftActivity::destroy);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private Optional<CompatibilityTestProgressOverlay> getTestProgressOverlay() {
        return Optional.ofNullable(testProgressOverlay);
    }

    private void generateInputCompatibilityTestQueue() {

        // basic test of all the dpads to see what works

        // some vendors seemingly break background ime context
        inputCompatibilityTests.add(new Test<>(
                this, R.string.input_compatibility_test_ime_dpad_basic, TestType.INPUT_NAVIGATION,
                activity -> getControlHandler(ControlSourceConnector::getImeDirectionalPadInput)
                        .flatMap(directionalPadInput -> createBasicButtonGridDpadTest(activity, directionalPadInput))
        ));

        // sanity check. if ime dpad and fake dpad both work, this should also work.
        // in reality, if there's an issue with receiving ui updates as an accessibility service, this will break.
        // thankfully, there are no known cases of this yet.
        inputCompatibilityTests.add(new Test<>(
                this, R.string.input_compatibility_test_accessibility_assisted_ime_dpad_basic, TestType.INPUT_NAVIGATION,
                activity -> getControlHandler(ControlSourceConnector::getAccessibilityAssistedImeDirectionalPadInput)
                        .flatMap(directionalPadInput -> createBasicButtonGridDpadTest(activity, directionalPadInput))
        ));

        // hours of work have made this not the worst thing ever
        // still doesn't work in many apps, but may be the only option for some older devices
        inputCompatibilityTests.add(new Test<>(
                this, R.string.input_compatibility_test_fake_dpad_basic, TestType.INPUT_NAVIGATION,
                activity -> getControlHandler(ControlSourceConnector::getAccessibilityFakeDirectionalPadInput)
                        .flatMap(directionalPadInput -> createBasicButtonGridDpadTest(activity, directionalPadInput))
        ));

        // should be the best, but apparently not on some devices (see DpadTextTrapBugTest)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            inputCompatibilityTests.add(new Test<>(
                    this, R.string.input_compatibility_test_accessibility_dpad_basic, TestType.INPUT_NAVIGATION,
                    activity -> getControlHandler(ControlSourceConnector::getAccessibilityAccDirectionalPadInput)
                            .flatMap(directionalPadInput -> createBasicButtonGridDpadTest(activity, directionalPadInput))
            ));
        }

        // test focus bug that seems to happen on android 12 and below
        // makes apps like settings completely unusable without assistance
        inputCompatibilityTests.add(new Test<>(
                this, R.string.input_compatibility_test_ime_dpad_focus_bug, TestType.INPUT_NAVIGATION,
                activity -> getControlHandler(ControlSourceConnector::getImeDirectionalPadInput)
                        .flatMap(directionalPadInput -> createImeFocusBugTest(activity, directionalPadInput))
        ));

        // verify that the assisted ime dpad is able to overcome the concern from the previous test
        // if it is unable to listen for UI changes or accurately detect when the ime bug happens, using it would be worse than using ime with the bug
        inputCompatibilityTests.add(new Test<>(
                this, R.string.input_compatibility_test_accessibility_assisted_ime_dpad_focus_bug, TestType.INPUT_NAVIGATION,
                activity -> getControlHandler(ControlSourceConnector::getAccessibilityAssistedImeDirectionalPadInput)
                        .flatMap(directionalPadInput -> createImeFocusBugTest(activity, directionalPadInput))
        ));

        // the accessibility dpad gets trapped in text boxes on some devices, which can be annoying for the user
        // if this is a case, a fix is needed. todo: that fix hasn't been implemented yet
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            inputCompatibilityTests.add(new Test<>(
                    this, R.string.input_compatibility_test_accessibility_dpad_text_editor_trap_bug, TestType.INPUT_NAVIGATION,
                    activity -> getControlHandler(ControlSourceConnector::getAccessibilityAccDirectionalPadInput)
                            .flatMap(directionalPadInput -> createDpadTextTrapBugTest(activity, directionalPadInput))
            ));
        }
    }

    private void generateFeatureInputCompatibilityTestQueue() {
        // real android tv builds usually only have this on android <= 9
        inputCompatibilityTests.add(new Test<>(
                this, R.string.input_feature_compatibility_test_overview_button, TestType.FEATURE_PRESENCE,
                activity -> getControlHandler(ControlSourceConnector::getAccessibilityFullNavigationInput)
                        .flatMap(fullNavigationInput -> createMenuFeatureTest(() -> fullNavigationInput.navRecent(KeyEventType.CLICK)))
        ));

        // some android tv vendors seem to break this?
        inputCompatibilityTests.add(new Test<>(
                this, R.string.input_feature_compatibility_test_notification_button, TestType.FEATURE_PRESENCE,
                activity -> getControlHandler(ControlSourceConnector::getAccessibilityFullNavigationInput)
                        .flatMap(fullNavigationInput -> createMenuFeatureTest(() -> fullNavigationInput.navNotifications(KeyEventType.CLICK)))
        ));

        // real android tv builds usually don't support this
        inputCompatibilityTests.add(new Test<>(
                this, R.string.input_feature_compatibility_test_quick_settings_action, TestType.FEATURE_PRESENCE,
                activity -> getControlHandler(ControlSourceConnector::getAccessibilityFullNavigationInput)
                        .flatMap(fullNavigationInput -> createMenuFeatureTest(fullNavigationInput::navQuickSettings))
        ));

        // android tv 8 usually doesn't have this
        // in the future, this functionality can possibly be emulated
        inputCompatibilityTests.add(new Test<>(
                this, R.string.input_feature_compatibility_test_home_button, TestType.FEATURE_PRESENCE,
                activity -> getControlHandler(ControlSourceConnector::getAccessibilityFullNavigationInput)
                        .flatMap(fullNavigationInput -> createMenuFeatureTest(() -> fullNavigationInput.navHome(KeyEventType.CLICK)))
        ));
    }

    private PendingSec<Boolean> createBasicButtonGridDpadTest(InputTestActivity activity, DirectionalPadInput directionalPadInput) {
        return SecAdapter.create(handler, secAdapter -> {
            BasicButtonGridDpadTest test = new BasicButtonGridDpadTest(
                    activity.getTestContainer(),
                    FRAME_LAYOUT_MATCH_PARENT,
                    secAdapter::provideResult,
                    directionalPadInput);
            test.startTest();
            cancelCurrentTest = test::cancelTest;
        });
    }

    private PendingSec<Boolean> createImeFocusBugTest(InputTestActivity activity, DirectionalPadInput directionalPadInput) {
        return SecAdapter.create(handler, secAdapter -> {
            ImeFocusBugTest test = new ImeFocusBugTest(
                    activity.getTestContainer(),
                    FRAME_LAYOUT_MATCH_PARENT,
                    secAdapter::provideResult,
                    activity.getSupportFragmentManager(),
                    directionalPadInput);
            test.startTest();
            cancelCurrentTest = test::cancelTest;
        });
    }

    private PendingSec<Boolean> createDpadTextTrapBugTest(InputTestActivity activity, DirectionalPadInput directionalPadInput) {
        return SecAdapter.create(handler, secAdapter -> {
            DpadTextTrapBugTest test = new DpadTextTrapBugTest(
                    activity.getTestContainer(),
                    FRAME_LAYOUT_MATCH_PARENT,
                    secAdapter::provideResult,
                    directionalPadInput);
            test.startTest();
            cancelCurrentTest = test::cancelTest;
        });
    }

    private PendingSec<Boolean> createMenuFeatureTest(Runnable openMenu) {
        return SecAdapter.create(handler, secAdapter -> {
            MenuFeatureCompatibilityTest test = new MenuFeatureCompatibilityTest(
                    secAdapter::provideResult,
                    getAccessibilityBinder()
                            .map(b -> (Supplier<Integer>) b::getUiUpdateSerial)
                            .orElseThrow(),
                    openMenu);
            test.startTest();
            cancelCurrentTest = test::cancelTest;
        });
    }

    private <T extends ControlHandler> PendingSec<T> getControlHandler(Function<ControlSourceConnector, T> getter) {
        return SecAdapter.createSimple(redExecutor, () -> {
            T controlHandler = controlSourceConnector.waitForControl(getter, CONTROL_SOURCE_TIMEOUT);
            if (controlHandler == null) throw new TimeoutException("timed out waiting for control handler");
            return controlHandler;
        });
    }

    public class ServiceBinder extends Binder {

        public void onInputTestActivityReady(InputTestActivity activity) {
            Test<?> test = inputCompatibilityTests.poll();
            if (test == null) {
                //todo
                Log.i(TAG, "end of test queue");
                return;
            }

            Log.i(TAG, "starting test: " + test.name());
            if (compatibilityTestResults.containsKey(test.id())) {
                Log.w(TAG, "test result already present!");
            }
            test.creator().apply(activity)
                    .start()
                    .doOnResult(result -> {
                        Log.i(TAG, "test finished with result: " + result);

                        compatibilityTestResults.put(test.id(), result);

                        if (test.type.shouldShowLog()) {
                            assert result instanceof Boolean;
                            getTestProgressOverlay()
                                    .ifPresent(overlay -> overlay.logTestResult(test.name(), (boolean) result));
                        }

                        activity.resetForNextTest();
                    })
                    .doOnError(t -> {
                        Log.e(TAG, "test failed, exception thrown", t);

                        if (test.type.shouldShowLog()) {
                            getTestProgressOverlay()
                                    .ifPresent(overlay -> overlay.logTestResult(test.name(), false));
                        }

                        activity.resetForNextTest();
                    })
                    .callMeWhenDone();
        }

    }

    private Optional<AccessibilityInputService.AccessibilityInputHandler> getAccessibilityBinder() {
        return Optional.ofNullable(accessibilityBinder);
    }

    public class AccessibilityServiceConnection extends MakeshiftServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "accessibility service connected");
            accessibilityBinder = (AccessibilityInputService.AccessibilityInputHandler) service;

            // if the user hasn't already granted this, it's not going to be granted during the test. also it interferes with the test.
            accessibilityBinder.silencePromptForImeDpadAssist();

            if (testProgressOverlay == null) {
                testProgressOverlay = new CompatibilityTestProgressOverlay(accessibilityBinder.getService(), MakeshiftActivity.OverlayMode.ACCESSIBILITY_OVERLAY);
                testProgressOverlay.start();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "accessibility service disconnected");
            accessibilityBinder = null;
        }
    }
}