package io.benwiegand.atvremote.receiver.ui.test.navigation;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import io.benwiegand.atvremote.receiver.ui.test.CompatibilityTest;
import io.benwiegand.atvremote.receiver.ui.test.exception.TestInitFailureException;

public abstract class ViewNavigationCompatibilityTest extends CompatibilityTest {
    private final String TAG = getLogTag();

    protected static final int[] PRESSED_STATE_SET = new int[] {android.R.attr.state_pressed};
    protected static final int[] FOCUSED_STATE_SET = new int[] {android.R.attr.state_focused};
    protected static final int[] ENABLED_STATE_SET = new int[] {android.R.attr.state_enabled};

    protected static final int PRESSED_COLOR = 0xFFCC33CC;
    protected static final int FOCUSED_COLOR = 0xFF3333CC;
    protected static final int ENABLED_COLOR = 0xFF333333;

    protected static final ColorStateList COLOR_STATE_LIST = new ColorStateList(
            new int[][] {
                    PRESSED_STATE_SET,
                    FOCUSED_STATE_SET,
                    ENABLED_STATE_SET,
            },
            new int[] {
                    PRESSED_COLOR,
                    FOCUSED_COLOR,
                    ENABLED_COLOR,
            });

    private static final long INIT_FOCUS_RETRY_INTERVAL = 100;
    private static final int INIT_FOCUS_MAX_ATTEMPTS = (int) (2000 / INIT_FOCUS_RETRY_INTERVAL); // try for 2 seconds

    protected final Handler handler = new Handler(Looper.getMainLooper());
    protected final ViewGroup root;
    protected final ViewGroup.LayoutParams rootLayoutParams;

    private int initFocusAttempts = 0;

    public ViewNavigationCompatibilityTest(ViewGroup root, ViewGroup.LayoutParams rootLayoutParams, Callback callback) {
        super(callback);
        this.root = root;
        this.rootLayoutParams = rootLayoutParams;
    }

    @Override
    public void startTest() {
        super.startTest();

        Log.i(TAG, "running view setup");
        setupViews();
        Log.i(TAG, "initializing focus");
        tryInitFocus();
    }

    private void tryInitFocus() {
        if (initFocusAttempts++ >= INIT_FOCUS_MAX_ATTEMPTS) {
            Log.e(TAG, "focus init failed, retry attempts exhausted");
            setError(new TestInitFailureException("focus init failed with " + initFocusAttempts + " attempts"));
            return;
        }

        if (!initFocus()) {
            Log.w(TAG, "focus init failed, retrying in " + INIT_FOCUS_RETRY_INTERVAL + " ms");
            handler.postDelayed(this::tryInitFocus, INIT_FOCUS_RETRY_INTERVAL);
            return;
        }

        Log.d(TAG, "focus initialized successfully");
        begin();
    }

    private void begin() {
        Log.i(TAG, "starting test");
        beginTest();
    }

    protected abstract void setupViews();

    protected abstract boolean initFocus();

    protected abstract void beginTest();


    protected void addToRoot(View view) {
        root.addView(view, rootLayoutParams);
    }

    protected Context getContext() {
        return root.getContext();
    }

}
