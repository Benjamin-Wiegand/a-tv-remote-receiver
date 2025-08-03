package io.benwiegand.atvremote.receiver.ui.test;

import static io.benwiegand.atvremote.receiver.util.UiUtil.FRAME_LAYOUT_MATCH_PARENT;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import io.benwiegand.atvremote.receiver.async.PendingSec;
import io.benwiegand.atvremote.receiver.async.SecAdapter;
import io.benwiegand.atvremote.receiver.control.AccessibilityInputService;
import io.benwiegand.atvremote.receiver.control.ControlHandler;
import io.benwiegand.atvremote.receiver.control.ControlSourceConnector;
import io.benwiegand.atvremote.receiver.control.input.DirectionalPadInput;
import io.benwiegand.atvremote.receiver.stuff.makeshiftbind.MakeshiftServiceConnection;

public class CompatibilityAutoDetectService extends Service {
    private static final String TAG = CompatibilityAutoDetectService.class.getSimpleName();

    private static final long CONTROL_SOURCE_TIMEOUT = 2000;

    private ControlSourceConnector controlSourceConnector;
    private final MakeshiftServiceConnection accessibilityServiceConnection = new AccessibilityServiceConnection();
    private AccessibilityInputService.AccessibilityInputHandler accessibilityBinder = null;
    private final Binder binder = new ServiceBinder();

    private Runnable cancelCurrentTest = () -> {};

    private enum TestType {
        INPUT_NAVIGATION;

        public boolean shouldShowLog() {
            return this == INPUT_NAVIGATION;
        }
    }

    private record Test<T>(
            String name,
            TestType type,
            Function<InputTestActivity, PendingSec<T>> creator
    ) {}

    private final Queue<Test<?>> inputCompatibilityTests = new LinkedList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Executor redExecutor = r -> new Thread(r).start();

    @Override
    public void onCreate() {
        super.onCreate();
        controlSourceConnector = new ControlSourceConnector(this, b -> {});
        MakeshiftServiceConnection.bindService(this, new ComponentName(this, AccessibilityInputService.class), accessibilityServiceConnection);

        generateInputCompatibilityTestQueue();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        cancelCurrentTest.run();
        controlSourceConnector.destroy();
        accessibilityServiceConnection.destroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void generateInputCompatibilityTestQueue() {

        // basic test of all the dpads to see what works
        inputCompatibilityTests.add(new Test<>(
                "IME DPAD - basic test", TestType.INPUT_NAVIGATION,
                activity -> getControlHandler(ControlSourceConnector::getImeDirectionalPadInput)
                        .flatMap(directionalPadInput -> createBasicButtonGridDpadTest(activity, directionalPadInput))
        ));

        inputCompatibilityTests.add(new Test<>(
                "Accessibility assisted IME DPAD - basic test", TestType.INPUT_NAVIGATION,
                activity -> getControlHandler(ControlSourceConnector::getAccessibilityAssistedImeDirectionalPadInput)
                        .flatMap(directionalPadInput -> createBasicButtonGridDpadTest(activity, directionalPadInput))
        ));

        inputCompatibilityTests.add(new Test<>(
                "Accessibility fake DPAD - basic test", TestType.INPUT_NAVIGATION,
                activity -> getControlHandler(ControlSourceConnector::getAccessibilityFakeDirectionalPadInput)
                        .flatMap(directionalPadInput -> createBasicButtonGridDpadTest(activity, directionalPadInput))
        ));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            inputCompatibilityTests.add(new Test<>(
                    "Accessibility DPAD - basic test", TestType.INPUT_NAVIGATION,
                    activity -> getControlHandler(ControlSourceConnector::getAccessibilityAccDirectionalPadInput)
                            .flatMap(directionalPadInput -> createBasicButtonGridDpadTest(activity, directionalPadInput))
            ));
        }

        // test focus bug that seems to happen on android 12 and below
        // makes apps like settings completely unusable without assistance
        inputCompatibilityTests.add(new Test<>(
                "IME DPAD - IME focus bug test", TestType.INPUT_NAVIGATION,
                activity -> getControlHandler(ControlSourceConnector::getImeDirectionalPadInput)
                        .flatMap(directionalPadInput -> createImeFocusBugTest(activity, directionalPadInput))
        ));

        // verify that the assisted ime dpad is able to overcome the concern from the previous test
        // if it is unable to listen for UI changes or accurately detect when the ime bug happens, using it would be worse than using ime with the bug
        inputCompatibilityTests.add(new Test<>(
                "Accessibility assisted IME DPAD - IME focus bug test", TestType.INPUT_NAVIGATION,
                activity -> getControlHandler(ControlSourceConnector::getAccessibilityAssistedImeDirectionalPadInput)
                        .flatMap(directionalPadInput -> createImeFocusBugTest(activity, directionalPadInput))
        ));

        // the accessibility dpad gets trapped in text boxes on some devices, which can be annoying for the user
        // if this is a case, a fix is needed. todo: that fix hasn't been implemented yet
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            inputCompatibilityTests.add(new Test<>(
                    "Accessibility DPAD - text editor trap bug test", TestType.INPUT_NAVIGATION,
                    activity -> getControlHandler(ControlSourceConnector::getAccessibilityAccDirectionalPadInput)
                            .flatMap(directionalPadInput -> createDpadTextTrapBugTest(activity, directionalPadInput))
            ));
        }
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
            test.creator().apply(activity)
                    .start()
                    .doOnResult(result -> {
                        Log.i(TAG, "test finished with result: " + result);

                        if (test.type.shouldShowLog()) {
                            assert result instanceof Boolean;
                            activity.logTestResultOnscreen(test.name(), (boolean) result);
                        }

                        activity.resetForNextTest();
                    })
                    .doOnError(t -> {
                        Log.e(TAG, "test failed, exception thrown", t);

                        if (test.type.shouldShowLog()) {
                            activity.logTestResultOnscreen(test.name(), false);
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
            ((AccessibilityInputService.AccessibilityInputHandler) service).silencePromptForImeDpadAssist();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "accessibility service disconnected");
            accessibilityBinder = null;
        }
    }
}