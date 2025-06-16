package io.benwiegand.atvremote.receiver.control;

import android.content.ComponentName;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;

import io.benwiegand.atvremote.receiver.control.input.DirectionalPadInput;
import io.benwiegand.atvremote.receiver.stuff.makeshiftbind.MakeshiftBind;
import io.benwiegand.atvremote.receiver.stuff.makeshiftbind.MakeshiftBindCallback;

public class IMEInputService extends InputMethodService implements MakeshiftBindCallback {
    private static final String TAG = IMEInputService.class.getSimpleName();

    private static final long LONG_PRESS_DURATION = 1500;

    private MakeshiftBind makeshiftBind = null;
    private final IBinder binder = new ServiceBinder();
    private final DirectionalPadInput directionalPadInput = new DirectionalPadInputHandler();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate()");
        super.onCreate();

        makeshiftBind = new MakeshiftBind(this, new ComponentName(this, IMEInputService.class), this);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy()");
        super.onDestroy();

        makeshiftBind.destroy();
    }

    @Override
    public IBinder onMakeshiftBind(Intent intent) {
        return binder;
    }

    @Override
    public void onBindInput() {
        Log.v(TAG, "input service ready to input");
        super.onBindInput();
    }

    public boolean simulateKeystroke(int keyCode, boolean longPress) {
        Log.v(TAG, "simulating keystroke: " + keyCode);

        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) {
            Log.e(TAG, "IME not connected");
            return false;
        }

        // no FLAG_SOFT_KEYBOARD, apps will ignore dpad inputs if that's set
        boolean down = inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));

        Runnable doKeyUp = () -> {
            boolean up = inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
            if (down && !up) Log.w(TAG, "ACTION_DOWN sent, but ACTION_UP failed");
        };

        if (longPress) {
            handler.postDelayed(doKeyUp, LONG_PRESS_DURATION);
        } else {
            doKeyUp.run();
        }
        return down;
    }

    public boolean simulateKeystroke(int keyCode) {
        return simulateKeystroke(keyCode, false);
    }

    public class DirectionalPadInputHandler implements DirectionalPadInput {

        @Override
        public void dpadDown() {
            simulateKeystroke(KeyEvent.KEYCODE_DPAD_DOWN);
        }

        @Override
        public void dpadUp() {
            simulateKeystroke(KeyEvent.KEYCODE_DPAD_UP);
        }

        @Override
        public void dpadLeft() {
            simulateKeystroke(KeyEvent.KEYCODE_DPAD_LEFT);
        }

        @Override
        public void dpadRight() {
            simulateKeystroke(KeyEvent.KEYCODE_DPAD_RIGHT);
        }

        @Override
        public void dpadSelect() {
            simulateKeystroke(KeyEvent.KEYCODE_DPAD_CENTER);
        }

        @Override
        public void dpadLongPress() {
            simulateKeystroke(KeyEvent.KEYCODE_DPAD_CENTER, true);
        }

        @Override
        public void destroy() {

        }
    }

    public class ServiceBinder extends Binder {

        public DirectionalPadInput getDirectionalPadInput() {
            return directionalPadInput;
        }

    }

}
