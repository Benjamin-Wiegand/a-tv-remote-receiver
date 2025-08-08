package io.benwiegand.atvremote.receiver.ui.test;

import android.os.SystemClock;
import android.util.Log;

import io.benwiegand.atvremote.receiver.ui.test.exception.TestCancelledException;
import io.benwiegand.atvremote.receiver.ui.test.exception.TestException;

public abstract class CompatibilityTest {
    private final String TAG = getLogTag();

    protected final Callback callback;

    private long testStartedAt = 0;

    private final Object resultLock = new Object();
    private boolean finished = false;
    private boolean pass = false;
    private TestException error = null;
    private long elapsedMs = 0;

    protected CompatibilityTest(Callback callback) {
        this.callback = callback;
    }

    public void cancelTest() {
        synchronized (resultLock) {
            if (finished) {
                Log.e(TAG, "cannot cancel test: it's already over");
                return;
            }
            elapsedMs = getElapsedTime();
            error = new TestCancelledException();
            error.setDetails(elapsedMs);
            finished = true;
            Log.i(TAG, "test cancelled:\n- error = " + error + "\n- elapsed ms = " + getElapsedTime());
        }

        callback.onFinished(pass);
    }

    public void startTest() {
        testStartedAt = SystemClock.elapsedRealtime();
        Log.v(TAG, "test starting");
    }

    /**
     * <p>
     *     sets the final result of the test to be an error, handing it to the callback.
     *     the test is marked as "finished" and thus subsequent calls are ignored and logged.
     * </p>
     * <p>
     *     this does not imply that the feature doesn't work, but rather that the test wasn't able
     *     to determine whether the feature works.
     * </p>
     * @param t the error (for debugging). should not be null, but a failsafe exists
     */
    protected void setError(Throwable t) {
        if (t == null) {    // soft failure
            Log.wtf(TAG, "null test error result set!", new Throwable());
            t = new Exception("no error provided");
        }

        synchronized (resultLock) {
            if (finished) {
                Log.e(TAG, "test error result came after test already over");
                return;
            }

            elapsedMs = getElapsedTime();
            if (t instanceof TestException te) {
                error = te;
            } else {
                error = new TestException(t);
            }
            error.setDetails(elapsedMs);
            finished = true;

            Log.i(TAG, "test error:\n- error = " + error + "\n- elapsed ms = " + getElapsedTime());
        }

        callback.onUnsuccessful(error);
    }

    /**
     * <p>
     *     sets the final result of the test, handing it to the callback.
     *     the test is marked as "finished" and thus subsequent calls are ignored and logged.
     * </p>
     * <p>
     *     be careful: if the result is set to false it will be assumed that the feature being
     *     tested doesn't work. if the test failed to initialize use {@link #setError(Throwable)}
     * </p>
     * @param pass true if the test passed, false if failed
     */
    protected void setResult(boolean pass) {
        synchronized (resultLock) {
            if (finished) {
                Log.e(TAG, "test result came after test already over");
                return;
            }

            elapsedMs = getElapsedTime();
            this.pass = pass;
            finished = true;

            Log.i(TAG, "test completion:\n- pass = " + pass + "\n- elapsed ms = " + getElapsedTime());
        }

        callback.onFinished(pass);
    }

    protected long getElapsedTime() {
        if (finished) return elapsedMs;
        return SystemClock.elapsedRealtime() - testStartedAt;
    }

    protected abstract String getLogTag();

    protected boolean isPass() {
        return pass;
    }

    protected TestException getError() {
        return error;
    }

    public interface Callback {
        void onFinished(boolean pass);
        void onUnsuccessful(TestException t);
    }
}
