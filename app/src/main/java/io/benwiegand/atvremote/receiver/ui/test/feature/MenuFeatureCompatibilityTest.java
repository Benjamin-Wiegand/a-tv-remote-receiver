package io.benwiegand.atvremote.receiver.ui.test.feature;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.function.Supplier;

import io.benwiegand.atvremote.receiver.ui.test.CompatibilityTest;
import io.benwiegand.atvremote.receiver.ui.test.exception.TestException;
import io.benwiegand.atvremote.receiver.ui.test.exception.TestInitFailureException;

public class MenuFeatureCompatibilityTest extends CompatibilityTest {
    private final String TAG = MenuFeatureCompatibilityTest.class.getSimpleName();

    /**
     * no ui changes for at least this many milliseconds before considering the ui "settled" and
     * ready for the test
     */
    private static final long UI_SERIAL_SETTLE_PERIOD = 500;

    private static final long UI_SERIAL_SETTLE_MAX_ITERATIONS = 10;

    /**
     * max milliseconds to wait for the ui to update after opening the menu
     */
    private static final long UI_UPDATE_TIMEOUT = 5000;

    private static final long UI_UPDATE_POLL_INTERVAL = 200;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Supplier<Integer> uiSerialGetter;
    private final Runnable openMenu;

    private int uiSerial = -1;
    private int uiSerialSettleIterations = 0;

    public MenuFeatureCompatibilityTest(Callback callback, Supplier<Integer> uiSerialGetter, Runnable openMenu) {
        super(callback);
        this.uiSerialGetter = uiSerialGetter;
        this.openMenu = openMenu;
    }

    @Override
    public void startTest() {
        super.startTest();

        Log.i(TAG, "waiting for ui to settle");
        tryInitUiSerial();
    }

    public void tryInitUiSerial() {
        if (uiSerialSettleIterations++ >= UI_SERIAL_SETTLE_MAX_ITERATIONS) {
            Log.e(TAG, "UI serial didn't settle within the maximum allowed time");
            setError(new TestInitFailureException("UI serial didn't settle within " + uiSerialSettleIterations * UI_SERIAL_SETTLE_PERIOD + " ms"));
            return;
        }

        int currentUiSerial;
        try {
            currentUiSerial = uiSerialGetter.get();
        } catch (Throwable t) {
            Log.e(TAG, "exception while getting ui serial", t);
            setError(new TestInitFailureException("failed to get ui serial", t));
            return;
        }

        if (currentUiSerial != uiSerial) {
            Log.d(TAG, "ui not settled");
            uiSerial = currentUiSerial;
            handler.postDelayed(this::tryInitUiSerial, UI_SERIAL_SETTLE_PERIOD);
            return;
        }

        Log.d(TAG, "ui settled");
        beginTest();
    }

    private void beginTest() {
        Log.i(TAG, "starting test");
        openMenu.run();

        long timeoutTimestamp = SystemClock.elapsedRealtime() + UI_UPDATE_TIMEOUT;

        Log.v(TAG, "starting ui serial: " + uiSerial);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                int currentUiSerial;
                try {
                    currentUiSerial = uiSerialGetter.get();
                } catch (Throwable t) {
                    Log.e(TAG, "exception while getting ui serial", t);
                    setError(new TestException("failed to get ui serial", t));
                    return;
                }

                if (currentUiSerial != uiSerial) {
                    Log.v(TAG, "ui serial changed: " + currentUiSerial);
                    setResult(true);
                    return;
                }

                if (timeoutTimestamp < SystemClock.elapsedRealtime()) {
                    Log.e(TAG, "timed out waiting for ui update");
                    setResult(false);
                    return;
                }

                handler.postDelayed(this, UI_UPDATE_POLL_INTERVAL);
            }
        }, UI_UPDATE_POLL_INTERVAL);
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }


}
