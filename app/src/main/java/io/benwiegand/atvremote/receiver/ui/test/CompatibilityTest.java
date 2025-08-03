package io.benwiegand.atvremote.receiver.ui.test;

import android.os.SystemClock;
import android.util.Log;
public abstract class CompatibilityTest {
    private final String TAG = getLogTag();

    protected final Callback callback;

    private long testStartedAt = 0;

    private final Object resultLock = new Object();
    private boolean finished = false;
    private boolean pass = false;

    protected CompatibilityTest(Callback callback) {
        this.callback = callback;
    }

    public void cancelTest() {
        synchronized (resultLock) {
            if (finished) {
                Log.e(TAG, "cannot cancel test: it's already over");
                return;
            }
            finished = true;
            Log.i(TAG, "test cancelled:\n- pass = " + pass + "\n- elapsed ms = " + getElapsedTime());
        }

        callback.onFinished(pass);
    }

    public void startTest() {
        testStartedAt = SystemClock.elapsedRealtime();
        Log.v(TAG, "test starting");
    }

    protected void setResult(boolean pass) {
        synchronized (resultLock) {
            if (finished) {
                Log.e(TAG, "test result came after test already over");
                return;
            }

            this.pass = pass;
            finished = true;

            Log.i(TAG, "test completion:\n- pass = " + pass + "\n- elapsed ms = " + getElapsedTime());
        }

        callback.onFinished(pass);
    }

    protected long getElapsedTime() {
        return SystemClock.elapsedRealtime() - testStartedAt;
    }

    protected abstract String getLogTag();

    protected boolean isPass() {
        return pass;
    }

    public interface Callback {
        void onFinished(boolean pass);
    }
}
