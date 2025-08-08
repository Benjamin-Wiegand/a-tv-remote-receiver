package io.benwiegand.atvremote.receiver.compatibility;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

import io.benwiegand.atvremote.receiver.protocol.json.ReceiverCapabilities;

public class CompatibilityManager {
    private static final String TAG = CompatibilityManager.class.getSimpleName();

    private static final String KEY_PREF_COMPATIBILITY = "compatibility";
    private static final String KEY_FEATURES = "features";
    private static final String KEY_EXTRA_BUTTONS = "extra_buttons";

    private static final String KEY_ACCESSIBILITY_DPAD_TEXT_TRAP_BUG = "accessibility_dpad_text_trap_bug";

    private static final String KEY_CONTROL_PRIORITY_ACTIVITY_LAUNCHER = "control_priority_activity_launcher";
    private static final String KEY_CONTROL_PRIORITY_CURSOR = "control_priority_cursor";
    private static final String KEY_CONTROL_PRIORITY_DPAD = "control_priority_dpad";
    private static final String KEY_CONTROL_PRIORITY_KEYBOARD = "control_priority_keyboard";
    private static final String KEY_CONTROL_PRIORITY_MEDIA = "control_priority_media";
    private static final String KEY_CONTROL_PRIORITY_FULL_NAVIGATION = "control_priority_full_navigation";
    private static final String KEY_CONTROL_PRIORITY_BACK_NAVIGATION = "control_priority_back_navigation";
    private static final String KEY_CONTROL_PRIORITY_VOLUME = "control_priority_volume";
    private static final String KEY_CONTROL_PRIORITY_POWER = "control_priority_power";

    public static final String CONTROL_PRIORITY_IDENTIFIER_IME = "ime";
    public static final String CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY = "accessibility";
    public static final String CONTROL_PRIORITY_IDENTIFIER_NOTIFICATION_LISTENER = "notification";
    public static final String CONTROL_PRIORITY_IDENTIFIER_ASSISTED_IME_DPAD = "ime_assist";
    public static final String CONTROL_PRIORITY_IDENTIFIER_FAKE_DPAD = "fake_dpad";

    private static final String DEFAULT_INPUT_METHOD_PRIORITY_ACTIVITY_LAUNCHER = CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY;
    private static final String DEFAULT_INPUT_METHOD_PRIORITY_CURSOR = CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY;
    private static final String DEFAULT_INPUT_METHOD_PRIORITY_DPAD = String.join(",",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? new String[] {
                    CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY,
                    CONTROL_PRIORITY_IDENTIFIER_ASSISTED_IME_DPAD,
                    CONTROL_PRIORITY_IDENTIFIER_IME,
            } : new String[] {
                    CONTROL_PRIORITY_IDENTIFIER_ASSISTED_IME_DPAD,
                    CONTROL_PRIORITY_IDENTIFIER_IME,
            });
    private static final String DEFAULT_INPUT_METHOD_PRIORITY_KEYBOARD = String.join(",",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? new String[] {
                    CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY,
                    CONTROL_PRIORITY_IDENTIFIER_IME,
            } : new String[] {
                    CONTROL_PRIORITY_IDENTIFIER_IME,
                    CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY,
            });
    private static final String DEFAULT_INPUT_METHOD_PRIORITY_MEDIA = String.join(",",
            CONTROL_PRIORITY_IDENTIFIER_NOTIFICATION_LISTENER,
            CONTROL_PRIORITY_IDENTIFIER_IME);
    private static final String DEFAULT_INPUT_METHOD_PRIORITY_FULL_NAVIGATION = CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY;
    private static final String DEFAULT_INPUT_METHOD_PRIORITY_BACK_NAVIGATION = String.join(",",
            CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY,
            CONTROL_PRIORITY_IDENTIFIER_IME);
    private static final String DEFAULT_INPUT_METHOD_PRIORITY_VOLUME = String.join(",",
            CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY,
            CONTROL_PRIORITY_IDENTIFIER_IME);
    private static final String DEFAULT_INPUT_METHOD_PRIORITY_POWER = CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY;


    private final Context context;

    public CompatibilityManager(Context context) {
        this.context = context;
    }

    public ReceiverCapabilities generateCapabilities() {
        SharedPreferences prefs = context.getSharedPreferences(KEY_PREF_COMPATIBILITY, Context.MODE_PRIVATE);
        return new ReceiverCapabilities(
                new HashSet<>(prefs.getStringSet(KEY_FEATURES, ReceiverCapabilities.DEFAULT_SUPPORTED_FEATURES)),
                new HashSet<>(prefs.getStringSet(KEY_EXTRA_BUTTONS, ReceiverCapabilities.DEFAULT_EXTRA_BUTTONS))
        );
    }

    public boolean updateCapabilities(Set<String> supportedFeatures, Set<String> unsupportedFeatures, Set<String> supportedExtraButtons, Set<String> unsupportedExtraButtons) {
        Log.d(TAG, "updating capabilities");
        SharedPreferences prefs = context.getSharedPreferences(KEY_PREF_COMPATIBILITY, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        Set<String> features = new HashSet<>(prefs.getStringSet(KEY_FEATURES, ReceiverCapabilities.DEFAULT_SUPPORTED_FEATURES));
        Set<String> extraButtons = new HashSet<>(prefs.getStringSet(KEY_EXTRA_BUTTONS, ReceiverCapabilities.DEFAULT_EXTRA_BUTTONS));

        for (String feature : supportedFeatures) {
            if (features.add(feature)) Log.d(TAG, "+ feature " + feature);
        }
        for (String feature : unsupportedFeatures) {
            if (features.remove(feature)) Log.d(TAG, "- feature " + feature);
        }
        for (String extraButton : supportedExtraButtons) {
            if (extraButtons.add(extraButton)) Log.d(TAG, "+ extraButton " + extraButton);
        }
        for (String extraButton : unsupportedExtraButtons) {
            if (extraButtons.remove(extraButton)) Log.d(TAG, "- extraButton " + extraButton);
        }

        boolean commitResult = editor
                .putStringSet(KEY_FEATURES, features)
                .putStringSet(KEY_EXTRA_BUTTONS, extraButtons)
                .commit();

        Log.d(TAG, "capability update commit: " + commitResult);
        return commitResult;
    }

    public boolean setAccessibilityDpadTextTrapBug(boolean present) {
        return context.getSharedPreferences(KEY_PREF_COMPATIBILITY, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ACCESSIBILITY_DPAD_TEXT_TRAP_BUG, present)
                .commit();
    }

    public boolean getAccessibilityDpadTextTrapBug() {
        SharedPreferences prefs = context.getSharedPreferences(KEY_PREF_COMPATIBILITY, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_ACCESSIBILITY_DPAD_TEXT_TRAP_BUG, false);
    }

    public boolean setDpadControlPriority(String priority) {
        return context.getSharedPreferences(KEY_PREF_COMPATIBILITY, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CONTROL_PRIORITY_DPAD, priority)
                .commit();
    }

    private String getInputControlPriority(String key, String defaultPriority){
        SharedPreferences prefs = context.getSharedPreferences(KEY_PREF_COMPATIBILITY, Context.MODE_PRIVATE);
        return prefs.getString(key, defaultPriority);
    }

    public String getActivityLauncherControlPriority() {
        return getInputControlPriority(KEY_CONTROL_PRIORITY_ACTIVITY_LAUNCHER, DEFAULT_INPUT_METHOD_PRIORITY_ACTIVITY_LAUNCHER);
    }

    public String getCursorControlPriority() {
        return getInputControlPriority(KEY_CONTROL_PRIORITY_CURSOR, DEFAULT_INPUT_METHOD_PRIORITY_CURSOR);
    }

    public String getDpadControlPriority() {
        return getInputControlPriority(KEY_CONTROL_PRIORITY_DPAD, DEFAULT_INPUT_METHOD_PRIORITY_DPAD);
    }

    public String getKeyboardControlPriority() {
        return getInputControlPriority(KEY_CONTROL_PRIORITY_KEYBOARD, DEFAULT_INPUT_METHOD_PRIORITY_KEYBOARD);
    }

    public String getMediaControlPriority() {
        return getInputControlPriority(KEY_CONTROL_PRIORITY_MEDIA, DEFAULT_INPUT_METHOD_PRIORITY_MEDIA);
    }

    public String getFullNavigationControlPriority() {
        return getInputControlPriority(KEY_CONTROL_PRIORITY_FULL_NAVIGATION, DEFAULT_INPUT_METHOD_PRIORITY_FULL_NAVIGATION);
    }

    public String getBackNavigationControlPriority() {
        return getInputControlPriority(KEY_CONTROL_PRIORITY_BACK_NAVIGATION, DEFAULT_INPUT_METHOD_PRIORITY_BACK_NAVIGATION);
    }

    public String getVolumeControlPriority() {
        return getInputControlPriority(KEY_CONTROL_PRIORITY_VOLUME, DEFAULT_INPUT_METHOD_PRIORITY_VOLUME);
    }

    public String getPowerControlPriority() {
        return getInputControlPriority(KEY_CONTROL_PRIORITY_POWER, DEFAULT_INPUT_METHOD_PRIORITY_POWER);
    }
}
