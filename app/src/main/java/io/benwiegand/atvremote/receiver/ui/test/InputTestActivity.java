package io.benwiegand.atvremote.receiver.ui.test;


import static io.benwiegand.atvremote.receiver.util.UiUtil.FRAME_LAYOUT_MATCH_PARENT;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import java.util.Optional;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.stuff.makeshiftbind.MakeshiftServiceConnection;

public class InputTestActivity extends FragmentActivity {
    private static final String TAG = InputTestActivity.class.getSimpleName();

    /**
     *  sits over text layer
     */
    private static final float TEST_CONTAINER_ALPHA = 0.3f;

    private static final float TEST_IN_PROGRESS_NOTICE_SIZE = 42;

    private FrameLayout root;
    private FrameLayout testContainer;
    private LinearLayout textContainer;

    private final AutoDetectServiceConnection autoDetectServiceConnection = new AutoDetectServiceConnection();
    private CompatibilityAutoDetectService.ServiceBinder autoDetectServiceBinder = null;

    private final Object lock = new Object();
    private boolean readyForNextTest = false;

    private boolean paused = false;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Log.i(TAG, "onCreate()");
        super.onCreate(savedInstanceState);

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

        readyForNextTest = true;

        boolean bindResult = bindService(new Intent(this, CompatibilityAutoDetectService.class), autoDetectServiceConnection, BIND_IMPORTANT);
        assert bindResult;
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "onDestroy()");
        super.onDestroy();

        try {
            unbindService(autoDetectServiceConnection);
        } catch (Throwable t) {
            Log.wtf(TAG, "exception while unbinding compatibility auto detect service", t);
        }
    }

    @Override
    protected void onResume() {
        Log.i(TAG, "onResume()");
        super.onResume();
        paused = false;
        startNextTestIfReady();
    }

    @Override
    protected void onPause() {
        Log.i(TAG, "onPause()");
        super.onPause();
        paused = true;
    }

    private boolean isForeground() {
        return !paused && !isDestroyed() && !isFinishing();
    }

    public void resetForNextTest() {
        runOnUiThread(() -> {
            testContainer.removeAllViews();
            readyForNextTest = true;

            if (!isForeground()) {
                startActivity(new Intent(getApplicationContext(), InputTestActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            }

            startNextTestIfReady();
        });
    }

    public void startNextTestIfReady() {
        synchronized (lock) {
            if (readyForNextTest && isForeground()) {
                getAutoDetectServiceBinder().ifPresent(
                        binder -> {
                            readyForNextTest = false;
                            binder.onInputTestActivityReady(InputTestActivity.this);
                        });
            }
        }
    }

    public FrameLayout getTestContainer() {
        return testContainer;
    }

    public void logTestResultOnscreen(String testName, boolean pass) {
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

    private Optional<CompatibilityAutoDetectService.ServiceBinder> getAutoDetectServiceBinder() {
        return Optional.ofNullable(autoDetectServiceBinder);
    }

    public class AutoDetectServiceConnection extends MakeshiftServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "compatibility auto detect service connected");
            autoDetectServiceBinder = (CompatibilityAutoDetectService.ServiceBinder) service;

            startNextTestIfReady();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "compatibility auto detect service disconnected");
            autoDetectServiceBinder = null;
        }
    }
}
