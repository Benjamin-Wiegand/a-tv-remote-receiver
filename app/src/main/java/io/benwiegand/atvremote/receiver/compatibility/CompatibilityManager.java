package io.benwiegand.atvremote.receiver.compatibility;

import android.content.Context;
import android.content.SharedPreferences;
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

    private static final String KEY_CONTROL_PRIORITY_DPAD = "control_priority_dpad";

    public static final String CONTROL_PRIORITY_IDENTIFIER_IME = "ime";
    public static final String CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY = "accessibility";
    public static final String CONTROL_PRIORITY_IDENTIFIER_NOTIFICATION_LISTENER = "notification";
    public static final String CONTROL_PRIORITY_IDENTIFIER_ASSISTED_IME_DPAD = "ime_assist";
    public static final String CONTROL_PRIORITY_IDENTIFIER_FAKE_DPAD = "fake_dpad";

    private static final String DEFAULT_INPUT_METHOD_PRIORITY_DPAD = "accessibility,ime_assist,ime";


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

        boolean commitResult = editor.commit();
        Log.d(TAG, "capability update commit: " + commitResult);
        return commitResult;
    }

    public boolean setDpadControlPriority(String priority) {
        return context.getSharedPreferences(KEY_PREF_COMPATIBILITY, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CONTROL_PRIORITY_DPAD, priority)
                .commit();
    }

    public String getDpadControlPriority() {
        SharedPreferences prefs = context.getSharedPreferences(KEY_PREF_COMPATIBILITY, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CONTROL_PRIORITY_DPAD, DEFAULT_INPUT_METHOD_PRIORITY_DPAD);
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
}
