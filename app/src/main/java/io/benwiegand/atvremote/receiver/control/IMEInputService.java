package io.benwiegand.atvremote.receiver.control;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.SurroundingText;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.control.input.BackNavigationInput;
import io.benwiegand.atvremote.receiver.control.input.DirectionalPadInput;
import io.benwiegand.atvremote.receiver.control.input.KeyboardInput;
import io.benwiegand.atvremote.receiver.control.input.MediaInput;
import io.benwiegand.atvremote.receiver.control.input.VolumeInput;
import io.benwiegand.atvremote.receiver.protocol.KeyEventType;
import io.benwiegand.atvremote.receiver.protocol.json.SurroundingTextResponse;
import io.benwiegand.atvremote.receiver.stuff.makeshiftbind.MakeshiftBind;
import io.benwiegand.atvremote.receiver.stuff.makeshiftbind.MakeshiftBindCallback;
import io.benwiegand.atvremote.receiver.ui.PermissionRequestOverlay;
import io.benwiegand.atvremote.receiver.util.UiUtil;

public class IMEInputService extends InputMethodService implements MakeshiftBindCallback {
    private static final String TAG = IMEInputService.class.getSimpleName();
    private static final boolean DEBUG_LOGS = true;

    private static final int KEY_EVENT_SOURCE = InputDevice.SOURCE_DPAD | InputDevice.SOURCE_KEYBOARD;
    private static final int KEY_EVENT_DEVICE_ID = KeyCharacterMap.VIRTUAL_KEYBOARD;
    private static final long LONG_PRESS_DURATION = 1500;

    private MakeshiftBind makeshiftBind = null;
    private final IBinder binder = new ServiceBinder();

    private final DirectionalPadInput directionalPadInput = new DirectionalPadInputHandler();
    private final BackNavigationInput backNavigationInput = new BackNavigationInputHandler();
    private final VolumeInput volumeInput = new VolumeInputHandler();
    private final KeyboardInput keyboardInput = new KeyboardInputHandler();
    private final MediaInput mediaInput = new MediaInputHandler();

    private record KeyState(long downTime, int repeatCount) {
        KeyState repeat() {
            return new KeyState(downTime, repeatCount + 1);
        }
    }

    private final Map<Integer, KeyState> keyCodeStateMap = new HashMap<>();

    private final Handler handler = new Handler(Looper.getMainLooper());

    private View view = null;

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
        EditorInfo editorInfo = getCurrentInputEditorInfo();
        Log.v(TAG, "input bound: " + (editorInfo != null ? editorInfo.packageName : "null?"));
        super.onBindInput();
    }

    @Override
    public void onUnbindInput() {
        Log.v(TAG, "input unbound");
        super.onUnbindInput();
    }

    @Override
    public boolean onShowInputRequested(int flags, boolean configChange) {
        return true;
    }

    private Optional<InputConnection> getOptionalInputConnection() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) Log.e(TAG, "IME not connected");
        return Optional.ofNullable(inputConnection);
    }

    private boolean sendKeyEvent(InputConnection inputConnection, long downTime, long eventTime, int action, int keyCode, int repeat, int flags) {
        return inputConnection.sendKeyEvent(new KeyEvent(downTime, eventTime, action, keyCode,
                repeat, 0, KEY_EVENT_DEVICE_ID, 0, flags, KEY_EVENT_SOURCE));
    }

    public boolean simulateKeystroke(KeyEventType type, int keyCode) {
        if (DEBUG_LOGS) {
            EditorInfo ei = getCurrentInputEditorInfo();
            Log.v(TAG, "simulating keystroke: " + type + " " + keyCode + (ei == null ? "" : " " + ei.packageName));
        }

        return getOptionalInputConnection().map(inputConnection -> {
            long eventTime = SystemClock.uptimeMillis();

            // no FLAG_SOFT_KEYBOARD, apps will ignore dpad inputs if that's set
            switch (type) {
                case CLICK -> {
                    if (!sendKeyEvent(inputConnection, eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0, 0)) {
                        Log.e(TAG, "failed to send key down event for click");
                        return false;
                    }
                    if (!sendKeyEvent(inputConnection, eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0, 0)) {
                        Log.e(TAG, "failed to send key up event for click");
                        return false;
                    }
                }
                case DOWN -> {
                    long downTime;
                    int repeat;
                    int flags = 0;
                    synchronized (keyCodeStateMap) {
                        KeyState state = keyCodeStateMap.compute(keyCode,
                                (c, s) -> s == null ? new KeyState(eventTime, 0) : s.repeat());
                        downTime = state.downTime();
                        repeat = state.repeatCount();
                        if (state.repeatCount() == 1) flags |= KeyEvent.FLAG_LONG_PRESS;
                    }

                    if (!sendKeyEvent(inputConnection, downTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, repeat, flags)) {
                        Log.e(TAG, "failed to send key down event");
                        return false;
                    }
                }
                case UP -> {
                    long downTime;
                    synchronized (keyCodeStateMap) {
                        KeyState state = keyCodeStateMap.remove(keyCode);
                        downTime = state != null ? state.downTime : eventTime;
                    }

                    if (!sendKeyEvent(inputConnection, downTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0, 0)) {
                        Log.e(TAG, "failed to send key up event");
                        return false;
                    }
                }
            }

            return true;
        }).orElse(false);
    }

    private boolean simulateKeystrokeLongPress(int keyCode) {
        boolean down = simulateKeystroke(KeyEventType.DOWN, keyCode);
        handler.postDelayed(() -> {
            simulateKeystroke(KeyEventType.DOWN, keyCode);
            simulateKeystroke(KeyEventType.UP, keyCode);
        }, LONG_PRESS_DURATION);
        return down;
    }

    private void switchToSoftKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (switchToPreviousInputMethod()) return;
            Log.v(TAG, "unable to switch to previous ime");
        }

        InputMethodManager imm = getSystemService(InputMethodManager.class);
        imm.showInputMethodPicker();
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        super.onEvaluateFullscreenMode();
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        View inputView = view;
        if (inputView == null) return super.onKeyDown(keyCode, event);

        View button = view.findViewById(R.id.revert_soft_keyboard_button);

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN,
                 KeyEvent.KEYCODE_DPAD_UP,
                 KeyEvent.KEYCODE_DPAD_LEFT,
                 KeyEvent.KEYCODE_DPAD_RIGHT,
                 KeyEvent.KEYCODE_DPAD_DOWN_LEFT,
                 KeyEvent.KEYCODE_DPAD_DOWN_RIGHT,
                 KeyEvent.KEYCODE_DPAD_UP_LEFT,
                 KeyEvent.KEYCODE_DPAD_UP_RIGHT -> {

                button.requestFocus();
                return true;
            }

            case KeyEvent.KEYCODE_DPAD_CENTER,
                 KeyEvent.KEYCODE_ENTER,
                 KeyEvent.KEYCODE_NUMPAD_ENTER -> {

                if (button.hasFocus() && button.performClick()) return true;
            }
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public View onCreateInputView() {
        // show a menu that offers to send you back
        view = getLayoutInflater().inflate(R.layout.layout_ime_input_view, null);
        view.findViewById(R.id.revert_soft_keyboard_button).setOnClickListener(v -> switchToSoftKeyboard());
        view.requestFocus();
        return view;
    }

    @Override
    public void onStartInputView(EditorInfo editorInfo, boolean restarting) {
        super.onStartInputView(editorInfo, restarting);

        if (view != null) view.requestFocus();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
    }

    public class DirectionalPadInputHandler implements DirectionalPadInput {
        @Override
        public void dpadDown(KeyEventType type) {
            simulateKeystroke(type, KeyEvent.KEYCODE_DPAD_DOWN);
        }

        @Override
        public void dpadUp(KeyEventType type) {
            simulateKeystroke(type, KeyEvent.KEYCODE_DPAD_UP);
        }

        @Override
        public void dpadLeft(KeyEventType type) {
            simulateKeystroke(type, KeyEvent.KEYCODE_DPAD_LEFT);
        }

        @Override
        public void dpadRight(KeyEventType type) {
            simulateKeystroke(type, KeyEvent.KEYCODE_DPAD_RIGHT);
        }

        @Override
        public void dpadSelect(KeyEventType type) {
            simulateKeystroke(type, KeyEvent.KEYCODE_DPAD_CENTER);
        }

        @Override
        public void dpadLongPress() {
            simulateKeystrokeLongPress(KeyEvent.KEYCODE_DPAD_CENTER);
        }
    }

    public class BackNavigationInputHandler implements BackNavigationInput {
        @Override
        public void navBack(KeyEventType type) {
            simulateKeystroke(type, KeyEvent.KEYCODE_BACK);
        }
    }

    public class VolumeInputHandler implements VolumeInput {
        @Override
        public void volumeUp(KeyEventType type) {
            simulateKeystroke(type, KeyEvent.KEYCODE_VOLUME_UP);
        }

        @Override
        public void volumeDown(KeyEventType type) {
            simulateKeystroke(type, KeyEvent.KEYCODE_VOLUME_DOWN);
        }

        @Override
        public void mute() {
            toggleMute(KeyEventType.CLICK); // unsupported
        }

        @Override
        public void unmute() {
            toggleMute(KeyEventType.CLICK); // unsupported
        }

        @Override
        public void toggleMute(KeyEventType type) {
            simulateKeystroke(type, KeyEvent.KEYCODE_VOLUME_MUTE);
        }
    }

    public class KeyboardInputHandler implements KeyboardInput {
        @Override
        public void setSoftKeyboardEnabled(boolean enabled) {
            // todo: remove this
        }

        @Override
        public boolean commitText(String input, int newCursorPosition) {
            return getOptionalInputConnection()
                    .map(inputConnection -> {
                        // do it character by character because some apps (like youtube) don't like it all at once
                        if (newCursorPosition > 0) {
                            for (char c : input.toCharArray()) {
                                if (!inputConnection.commitText(String.valueOf(c), 1)) return false;
                            }
                            if (newCursorPosition == 1) return true;
                        } else {
                            char[] chars = input.toCharArray();
                            for (int i = input.length() - 1; i >= 0; i--) {
                                if (!inputConnection.commitText(String.valueOf(chars[i]), 0)) return false;
                            }
                            if (newCursorPosition == 0) return true;
                        }

                        return inputConnection.commitText("", newCursorPosition);
                    })
                    .orElse(false);
        }

        @Override
        public SurroundingTextResponse getSurroundingText(int beforeLength, int afterLength) {
            return getOptionalInputConnection()
                    .map(inputConnection -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            SurroundingText surroundingText = inputConnection.getSurroundingText(beforeLength, afterLength, 0);
                            if (surroundingText == null) return null;
                            return new SurroundingTextResponse(surroundingText);
                        }

                        CharSequence textBefore = inputConnection.getTextBeforeCursor(beforeLength, 0);
                        if (textBefore == null) return null;
                        CharSequence textAfter = inputConnection.getTextAfterCursor(afterLength, 0);
                        if (textAfter == null) return null;
                        CharSequence selectedText = inputConnection.getSelectedText(0);
                        if (selectedText == null) selectedText = "";
                        String text = textBefore.toString() + selectedText + textAfter;

                        return new SurroundingTextResponse(textBefore.length(), textBefore.length() + selectedText.length(), -1, text);
                    })
                    .orElse(null);
        }

        @Override
        public boolean setSelection(int start, int end) {
            return getOptionalInputConnection()
                    .map(inputConnection -> inputConnection.setSelection(start, end))
                    .orElse(false);
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            return getOptionalInputConnection()
                    .map(inputConnection -> inputConnection.deleteSurroundingText(beforeLength, afterLength))
                    .orElse(false);
        }

        @Override
        public boolean performContextMenuAction(int id) {
            return getOptionalInputConnection()
                    .map(inputConnection -> inputConnection.performContextMenuAction(id))
                    .orElse(false);
        }

        @Override
        public boolean performEditorAction(int id) {
            return getOptionalInputConnection()
                    .map(inputConnection -> inputConnection.performEditorAction(id))
                    .orElse(false);
        }

        @Override
        public boolean sendKeyEvent(int keyCode, KeyEventType type) {
            return simulateKeystroke(type, keyCode);
        }

        @Override
        public boolean performDefaultEditorAction() {
            return getOptionalInputConnection().map(inputConnection -> {
                EditorInfo editorInfo = getCurrentInputEditorInfo();
                if (editorInfo == null) {
                    Log.w(TAG, "no editor info, falling back to sendDefaultEditorAction()");
                    return sendDefaultEditorAction(false);
                }

                int action = editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
                if (action == EditorInfo.IME_ACTION_NONE) return false;
                if (action == EditorInfo.IME_ACTION_UNSPECIFIED) {
                    Log.d(TAG, "editor action unspecified");
                    return false;
                }

                return inputConnection.performEditorAction(action);
            }).orElse(false);
        }
    }

    public class MediaInputHandler implements MediaInput {
        @Override
        public void playPause(KeyEventType type) {
            simulateKeystroke(type, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }

        @Override
        public void pause(KeyEventType type) {
            simulateKeystroke(type, KeyEvent.KEYCODE_MEDIA_PAUSE);
        }

        @Override
        public void play(KeyEventType type) {
            simulateKeystroke(type, KeyEvent.KEYCODE_MEDIA_PLAY);
        }

        @Override
        public void nextTrack(KeyEventType type) {
            simulateKeystroke(type, KeyEvent.KEYCODE_MEDIA_NEXT);
        }

        @Override
        public void prevTrack(KeyEventType type) {
            simulateKeystroke(type, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }

        @Override
        public void skipBackward(KeyEventType type) {
            simulateKeystroke(type, KeyEvent.KEYCODE_MEDIA_REWIND);
        }

        @Override
        public void skipForward(KeyEventType type) {
            simulateKeystroke(type, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
        }
    }

    public class ServiceBinder extends Binder {
        public DirectionalPadInput getDirectionalPadInput() {
            return directionalPadInput;
        }

        public BackNavigationInput getBackNavigationInput() {
            return backNavigationInput;
        }

        public VolumeInput getVolumeInput() {
            return volumeInput;
        }

        public KeyboardInput getKeyboardInput() {
            return keyboardInput;
        }

        public MediaInput getMediaInput() {
            return mediaInput;
        }

        public boolean isConnected() {
            return getOptionalInputConnection().isPresent();
        }
    }

    public static String getInputMethodId(Context context) {
        return new ComponentName(context, IMEInputService.class).flattenToShortString();
    }

    public static boolean isEnabled(Context context, InputMethodManager imm) {
        String imeId = getInputMethodId(context);
        assert imm.getInputMethodList().stream()
                .map(InputMethodInfo::getId)
                .anyMatch(imeId::equals);

        return imm.getEnabledInputMethodList().stream()
                .map(InputMethodInfo::getId)
                .anyMatch(imeId::equals);

    }

    public static boolean isEnabled(Context context) {
        return isEnabled(context, context.getSystemService(InputMethodManager.class));
    }

    private static final int[] FEATURE_STRINGS;

    static {
        ArrayList<Integer> featureStrings = new ArrayList<>(6);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            featureStrings.add(R.string.feature_text_input_alt);
        } else {
            featureStrings.add(R.string.feature_text_input);
            featureStrings.add(R.string.feature_ime_dpad_assist);
        }

        featureStrings.add(R.string.feature_dpad_alt);
        featureStrings.add(R.string.feature_system_wide_media_control);
        featureStrings.add(R.string.feature_volume_control_alt);

        FEATURE_STRINGS = new int[featureStrings.size()];
        for (int i = 0; i < featureStrings.size(); i++) {
            FEATURE_STRINGS[i] = featureStrings.get(i);
        }
    }

    public static PermissionRequestOverlay.PermissionRequestSpec getEnableRequestSpec(Context context) {
        return new PermissionRequestOverlay.PermissionRequestSpec(
                R.string.permission_request_title_enable_keyboard,
                R.string.permission_request_subtitle_keyboard,
                FEATURE_STRINGS,
                R.string.permission_request_instructions_header_settings_location,
                R.string.permission_request_instructions_details_enable_keyboard,
                new UiUtil.ButtonPreset(R.string.permission_request_grant_button_settings, v -> {
                    boolean opened = UiUtil.tryActivityIntents(context,
                            new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    if (opened) return;
                    Toast.makeText(context, R.string.permission_request_instructions_settings_not_found_error, Toast.LENGTH_LONG).show();
                }),
                () -> {});
    }

    public static PermissionRequestOverlay.PermissionRequestSpec getSwitchRequestSpec(Context context) {
        return new PermissionRequestOverlay.PermissionRequestSpec(
                R.string.permission_request_title_switch_keyboard,
                R.string.permission_request_subtitle_keyboard,
                FEATURE_STRINGS,
                R.string.permission_request_instructions_header_switch_keyboard,
                R.string.permission_request_instructions_details_switch_keyboard,
                new UiUtil.ButtonPreset(R.string.permission_request_grant_button_switch_keyboard, v -> {
                    InputMethodManager imm = context.getSystemService(InputMethodManager.class);
                    imm.showInputMethodPicker();
                }),
                () -> {});
    }

    public static PermissionRequestOverlay.PermissionRequestSpec getPermissionRequestSpec(Context context) {
        return isEnabled(context) ? getSwitchRequestSpec(context) : getEnableRequestSpec(context);
    }

}
