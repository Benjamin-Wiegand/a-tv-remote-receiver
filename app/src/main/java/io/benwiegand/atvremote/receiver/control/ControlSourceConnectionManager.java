package io.benwiegand.atvremote.receiver.control;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.StringRes;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.control.input.ActivityLauncherInput;
import io.benwiegand.atvremote.receiver.control.input.BackNavigationInput;
import io.benwiegand.atvremote.receiver.control.input.CursorInput;
import io.benwiegand.atvremote.receiver.control.input.DirectionalPadInput;
import io.benwiegand.atvremote.receiver.control.input.KeyboardInput;
import io.benwiegand.atvremote.receiver.control.input.MediaInput;
import io.benwiegand.atvremote.receiver.control.input.FullNavigationInput;
import io.benwiegand.atvremote.receiver.control.input.PowerInput;
import io.benwiegand.atvremote.receiver.control.input.VolumeInput;
import io.benwiegand.atvremote.receiver.control.output.OverlayOutput;
import io.benwiegand.atvremote.receiver.control.output.PairingOverlayOutput;
import io.benwiegand.atvremote.receiver.control.output.PermissionRequestOutput;
import io.benwiegand.atvremote.receiver.stuff.Destroyable;
import io.benwiegand.atvremote.receiver.ui.PermissionRequestOverlay;

/**
 * connects to ControlHandlers and exposes a ControlScheme which returns one of each type (if one
 * exists) with respect to the configured input priorities. If there is no available ControlHandler
 * of that type, an exception is thrown and/or a permission rationale is displayed.
 */
public class ControlSourceConnectionManager implements Destroyable {
    private static final String TAG = ControlSourceConnectionManager.class.getSimpleName();

    private static final String CONTROL_PRIORITY_AUTO = "auto";
    private static final String CONTROL_PRIORITY_IDENTIFIER_IME = "ime";
    private static final String CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY = "accessibility";
    private static final String CONTROL_PRIORITY_IDENTIFIER_NOTIFICATION_LISTENER = "notification";
    private static final String CONTROL_PRIORITY_IDENTIFIER_ASSISTED_IME_DPAD = "ime_assist";

    private final ControlSourceConnector controlSourceConnector;
    private final ControlScheme controlScheme;
    private final Context context;

    public ControlSourceConnectionManager(Context context, Consumer<IBinder> onBind) {
        this.context = context;
        controlSourceConnector = new ControlSourceConnector(context, onBind);
        controlScheme = generateControlScheme();
    }

    public void destroy() {
        controlSourceConnector.destroy();
    }

    public ControlScheme getControlScheme() {
        return controlScheme;
    }

    public void regenerateControlScheme() {
        ControlScheme newControlScheme = generateControlScheme();
        controlScheme.update(newControlScheme);
    }

    private boolean showRationale(PermissionRequestOverlay.PermissionRequestSpec spec) {
        return getControlScheme().getPermissionRequestOutputOptional()
                .map(output -> output.showPermissionDialog(spec))
                .orElse(false);
    }

    private record ControlHandlerInfo<T extends ControlHandler>(
            ControlHandlerSupplier<T> supplier,
            Runnable handleMissing) {

        ControlHandlerInfo(Context context, ControlHandlerSupplier<T> supplier, Runnable showPermissionRationale, @StringRes Supplier<Integer> getExceptionStringRes) {
            this(supplier, () -> {
                showPermissionRationale.run();
                throw new ControlNotInitializedException(context.getString(getExceptionStringRes.get()));
            });
        }

        ControlHandlerInfo(Context context, ControlHandlerSupplier<T> supplier, @StringRes Supplier<Integer> getExceptionStringRes) {
            this(supplier, () -> {
                throw new ControlNotInitializedException(context.getString(getExceptionStringRes.get()));
            });
        }
    }

    /**
     * generates a supplier that carries out the priority defined by the priority value
     * @param controlHandlerClass class of the control handler (mainly just for debug logs)
     * @param priorityString comma separated list of identifiers in order of priority
     * @param identifierMap map to map aforementioned identifiers to ControlHandlerInfo records
     * @return a supplier to provide the desired control handler, ready to pass to a control scheme object
     * @param <T> the control handler type
     */
    private <T extends ControlHandler> ControlHandlerSupplier<T> generateControlHandlerSupplier(Class<T> controlHandlerClass, String priorityString, Map<String, ControlHandlerInfo<T>> identifierMap) {
        String[] priorities = priorityString.split(",");
        Log.i(TAG, controlHandlerClass.getSimpleName() + " priority: " + priorityString);

        if (priorities.length == 0) {
            Log.wtf(TAG, controlHandlerClass.getSimpleName() + " has no defined priority");
            assert false;
            return () -> {throw new AssertionError("priority not defined‽");};
        }

        ArrayList<ControlHandlerSupplier<T>> suppliers = new ArrayList<>(identifierMap.size());
        Runnable handleAllMissing = null;
        for (String identifier : priorities) {
            ControlHandlerInfo<T> controlHandlerInfo = identifierMap.get(identifier);
            if (controlHandlerInfo == null) {
                Log.wtf(TAG, controlHandlerClass.getSimpleName() + " control handler identifier not defined: " + identifier);
                assert false;
                continue;
            }

            suppliers.add(controlHandlerInfo.supplier());

            // the error should ask for the first and most preferred control handler
            if (handleAllMissing == null)
                handleAllMissing = controlHandlerInfo.handleMissing();
        }

        // don't crash release builds, but still throw a fit
        if (handleAllMissing == null) {
            Log.wtf(TAG, controlHandlerClass.getSimpleName() + " could not map any control handlers");
            assert false;
            return () -> {throw new AssertionError("no mapped control handlers‽");};
        }

        Runnable finalHandleAllMissing = handleAllMissing;
        return () -> {
            for (ControlHandlerSupplier<T> supplier : suppliers) {
                T controlHandler = supplier.get();
                if (controlHandler != null) return controlHandler;
            }

            finalHandleAllMissing.run();
            return null;
        };

    }

    private ControlScheme generateControlScheme() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean manualPriorityMode = sharedPreferences.getBoolean(context.getString(R.string.input_method_preferences_custom_priority_key), false);

        // todo: the auto string will be fetched from shared prefs too eventually
        BiFunction<Integer, String, String> getPriority = (manualRes, autoString) -> {
            if (!manualPriorityMode) return autoString;
            String manualString = sharedPreferences.getString(context.getString(manualRes), null);
            return manualString == null || manualString.equals(CONTROL_PRIORITY_AUTO) ? autoString : manualString;
        };

        Runnable showAccessibilityRationale = () -> showRationale(AccessibilityInputService.getPermissionRequestSpec(context));
        Runnable showImeRationale = () -> showRationale(IMEInputService.getPermissionRequestSpec(context));
        Runnable showNotificationListenerRationale = () -> showRationale(NotificationInputService.getPermissionRequestSpec(context));

        Supplier<Integer> getAccessibilityExceptionStringRes = () -> R.string.control_source_not_loaded_accessibility;
        Supplier<Integer> getImeExceptionStringRes = () -> {
            boolean imeEnabled = false;
            try {
                imeEnabled = IMEInputService.isEnabled(context);
            } catch (Throwable t) {
                Log.e(TAG, "failed to determine if input method is enabled", t);
            }

            return imeEnabled ? R.string.control_source_not_loaded_switch_to_ime : R.string.control_source_not_loaded_enable_ime;
        };
        Supplier<Integer> getNotificationListenerExceptionStringRes = () -> R.string.control_source_not_loaded_notification_listener;

        // todo: replace these exception messages when the ui is finished
        // using generateControlHandlerSupplier() ensures all the preference parsing only happens once
        // this of course means the control scheme needs to be regenerated if the preferences change
        return new ControlScheme(
                generateControlHandlerSupplier(ActivityLauncherInput.class,
                        getPriority.apply(R.string.input_method_preferences_activity_launcher_priority_key, CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY),
                        Map.of(
                                CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY, new ControlHandlerInfo<>(context,
                                        controlSourceConnector::getAccessibilityActivityLauncherInput,
                                        showAccessibilityRationale,
                                        getAccessibilityExceptionStringRes)
                        )),

                generateControlHandlerSupplier(CursorInput.class,
                        getPriority.apply(R.string.input_method_preferences_mouse_cursor_priority_key, CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY),
                        Map.of(
                                CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY, new ControlHandlerInfo<>(context,
                                        controlSourceConnector::getAccessibilityFakeCursorInput,
                                        showAccessibilityRationale,
                                        getAccessibilityExceptionStringRes)
                        )),

                generateControlHandlerSupplier(DirectionalPadInput.class,
                        getPriority.apply(
                                R.string.input_method_preferences_dpad_priority_key,
                                String.join(",",
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? new String[] {
                                                CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY,
                                                CONTROL_PRIORITY_IDENTIFIER_ASSISTED_IME_DPAD,
                                                CONTROL_PRIORITY_IDENTIFIER_IME,
                                        } : new String[] {
                                                CONTROL_PRIORITY_IDENTIFIER_ASSISTED_IME_DPAD,
                                                CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY,
                                                CONTROL_PRIORITY_IDENTIFIER_IME,
                                        }
                                    )),
                        Map.of(
                                CONTROL_PRIORITY_IDENTIFIER_ASSISTED_IME_DPAD, new ControlHandlerInfo<>(context,
                                        controlSourceConnector::getAccessibilityAssistedImeDirectionalPadInput,
                                        showAccessibilityRationale,
                                        getAccessibilityExceptionStringRes),
                                CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY, new ControlHandlerInfo<>(context,
                                        controlSourceConnector::getAccessibilityDirectionalPadInput,
                                        showAccessibilityRationale,
                                        getAccessibilityExceptionStringRes),
                                CONTROL_PRIORITY_IDENTIFIER_IME, new ControlHandlerInfo<>(context,
                                        controlSourceConnector::getImeDirectionalPadInput,
                                        showImeRationale,
                                        getImeExceptionStringRes)
                        )),

                generateControlHandlerSupplier(KeyboardInput.class,
                        getPriority.apply(
                                R.string.input_method_preferences_keyboard_priority_key,
                                String.join(",",
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? new String[] {
                                                CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY,
                                                CONTROL_PRIORITY_IDENTIFIER_IME,
                                        } : new String[] {
                                                CONTROL_PRIORITY_IDENTIFIER_IME,
                                                CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY,
                                        }
                                )),
                        Map.of(
                                CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY, new ControlHandlerInfo<>(context,
                                        controlSourceConnector::getAccessibilityKeyboardInput,
                                        showAccessibilityRationale,
                                        getAccessibilityExceptionStringRes),
                                CONTROL_PRIORITY_IDENTIFIER_IME, new ControlHandlerInfo<>(context,
                                        controlSourceConnector::getImeKeyboardInput,
                                        showImeRationale,
                                        getImeExceptionStringRes)
                        )),

                generateControlHandlerSupplier(MediaInput.class,
                        getPriority.apply(
                                R.string.input_method_preferences_media_priority_key,
                                String.join(",",
                                        CONTROL_PRIORITY_IDENTIFIER_NOTIFICATION_LISTENER,
                                        CONTROL_PRIORITY_IDENTIFIER_IME)),
                        Map.of(
                                CONTROL_PRIORITY_IDENTIFIER_NOTIFICATION_LISTENER, new ControlHandlerInfo<>(context,
                                        controlSourceConnector::getNotificationListenerMediaInput,
                                        showNotificationListenerRationale,
                                        getNotificationListenerExceptionStringRes),
                                CONTROL_PRIORITY_IDENTIFIER_IME, new ControlHandlerInfo<>(context,
                                        controlSourceConnector::getImeMediaInput,
                                        showImeRationale,
                                        getImeExceptionStringRes)
                        )),

                generateControlHandlerSupplier(FullNavigationInput.class,
                        getPriority.apply(R.string.input_method_preferences_full_navigation_priority_key, CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY),
                        Map.of(
                                CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY, new ControlHandlerInfo<>(context,
                                        controlSourceConnector::getAccessibilityFullNavigationInput,
                                        showAccessibilityRationale,
                                        getAccessibilityExceptionStringRes)
                        )),

                generateControlHandlerSupplier(BackNavigationInput.class,
                        getPriority.apply(
                                R.string.input_method_preferences_back_navigation_priority_key,
                                String.join(",",
                                        CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY,
                                        CONTROL_PRIORITY_IDENTIFIER_IME)),
                        Map.of(
                                CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY, new ControlHandlerInfo<>(context,
                                        controlSourceConnector::getAccessibilityFullNavigationInput,
                                        showAccessibilityRationale,
                                        getAccessibilityExceptionStringRes),
                                CONTROL_PRIORITY_IDENTIFIER_IME, new ControlHandlerInfo<>(context,
                                        controlSourceConnector::getImeBackNavigationInput,
                                        showImeRationale,
                                        getImeExceptionStringRes)
                        )),

                () -> {
                    throw new ControlNotInitializedException("not implemented");
                },

                generateControlHandlerSupplier(VolumeInput.class,
                        getPriority.apply(
                                R.string.input_method_preferences_volume_priority_key,
                                String.join(",",
                                        CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY,
                                        CONTROL_PRIORITY_IDENTIFIER_IME)),
                        Map.of(
                                CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY, new ControlHandlerInfo<>(context,
                                        controlSourceConnector::getAccessibilityVolumeInput,
                                        showAccessibilityRationale,
                                        getAccessibilityExceptionStringRes),
                                CONTROL_PRIORITY_IDENTIFIER_IME, new ControlHandlerInfo<>(context,
                                        controlSourceConnector::getImeVolumeInput,
                                        showImeRationale,
                                        getImeExceptionStringRes)
                        )),

                generateControlHandlerSupplier(PowerInput.class,
                        getPriority.apply(R.string.input_method_preferences_power_priority_key, CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY),
                        Map.of(
                                CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY, new ControlHandlerInfo<>(context,
                                        controlSourceConnector::getAccessibilityPowerInput,
                                        showAccessibilityRationale,
                                        getAccessibilityExceptionStringRes)
                        )),

                () -> {
                    OverlayOutput accessibilityOverlayOutput = controlSourceConnector.getAccessibilityOverlayOutput();
                    if (accessibilityOverlayOutput != null) return accessibilityOverlayOutput;

                    // don't show a rationale for every notification that would be annoying
                    throw new ControlNotInitializedException(context.getString(R.string.control_source_not_loaded_accessibility));
                },
                () -> {
                    PermissionRequestOutput applicationOverlayOutput = controlSourceConnector.getApplicationOverlayOutput();
                    if (applicationOverlayOutput != null) return applicationOverlayOutput;

                    throw new ControlNotInitializedException(context.getString(R.string.control_source_not_loaded_application_overlay));
                },
                () -> {
                    PairingOverlayOutput applicationOverlayOutput = controlSourceConnector.getApplicationOverlayOutput();
                    if (applicationOverlayOutput != null) return applicationOverlayOutput;

                    throw new ControlNotInitializedException(context.getString(R.string.control_source_not_loaded_application_overlay));
                }
        );
    }

}
