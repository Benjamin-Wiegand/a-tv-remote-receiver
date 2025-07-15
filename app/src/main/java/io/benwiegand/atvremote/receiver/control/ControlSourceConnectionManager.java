package io.benwiegand.atvremote.receiver.control;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
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
import io.benwiegand.atvremote.receiver.control.input.VolumeInput;
import io.benwiegand.atvremote.receiver.control.output.OverlayOutput;
import io.benwiegand.atvremote.receiver.stuff.makeshiftbind.MakeshiftServiceConnection;
import io.benwiegand.atvremote.receiver.ui.PermissionRequestOverlay;

public class ControlSourceConnectionManager {
    private static final String TAG = ControlSourceConnectionManager.class.getSimpleName();

    private static final String CONTROL_PRIORITY_AUTO = "auto";
    private static final String CONTROL_PRIORITY_IDENTIFIER_IME = "ime";
    private static final String CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY = "accessibility";
    private static final String CONTROL_PRIORITY_IDENTIFIER_NOTIFICATION_LISTENER = "notification";
    private static final String CONTROL_PRIORITY_IDENTIFIER_ASSISTED_IME_DPAD = "ime_assist";

    private final MakeshiftServiceConnection accessibilityInputServiceConnection = new AccessibilityInputServiceConnection();
    private final MakeshiftServiceConnection imeInputServiceConnection = new IMEInputServiceConnection();
    private ServiceConnection notificationInputServiceConnection = new NotificationInputServiceConnection();

    private final ControlScheme controlScheme;
    private final Context context;

    private final Consumer<IBinder> onBind;

    private final Object deathLock = new Object();
    private boolean dead = false;

    private final Object inputLock = new Object();

    private ActivityLauncherInput accessibilityActivityLauncherInput = null;
    private CursorInput accessibilityFakeCursorInput = null;
    private DirectionalPadInput accessibilityDirectionalPadInput = null;
    private DirectionalPadInput accessibilityAssistedImeDirectionalPadInput = null;
    private KeyboardInput accessibilityKeyboardInput = null;
    private FullNavigationInput accessibilityFullNavigationInput = null;
    private VolumeInput accessibilityVolumeInput = null;
    private OverlayOutput accessibilityOverlayOutput = null;

    private MediaInput notificationListenerMediaInput = null;

    private DirectionalPadInput imeDirectionalPadInput = null;
    private BackNavigationInput imeBackNavigationInput = null;
    private KeyboardInput imeKeyboardInput = null;
    private MediaInput imeMediaInput = null;
    private VolumeInput imeVolumeInput = null;

    private final ApplicationOverlayOutputHandler applicationOverlayOutput;

    public ControlSourceConnectionManager(Context context, Consumer<IBinder> onBind) {
        this.context = context;
        this.onBind = onBind;

        applicationOverlayOutput = new ApplicationOverlayOutputHandler(context);

        controlScheme = generateControlScheme();

        // "bind" accessibility service
        MakeshiftServiceConnection.bindService(context, new ComponentName(context, AccessibilityInputService.class), accessibilityInputServiceConnection);
        MakeshiftServiceConnection.bindService(context, new ComponentName(context, IMEInputService.class), imeInputServiceConnection);

        // bind notification listener service
        Intent notificationInputServiceIntent = new Intent(context, NotificationInputService.class);
        boolean bindResult = context.bindService(notificationInputServiceIntent, notificationInputServiceConnection, 0);
        assert bindResult;
    }

    public void destroy() {
        synchronized (deathLock) {
            dead = true;
        }

        accessibilityInputServiceConnection.destroy();
        imeInputServiceConnection.destroy();
        applicationOverlayOutput.destroy();

        context.unbindService(notificationInputServiceConnection);
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
            synchronized (inputLock) {
                for (ControlHandlerSupplier<T> supplier : suppliers) {
                    T controlHandler = supplier.get();
                    if (controlHandler != null) return controlHandler;
                }
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
                                        () -> accessibilityActivityLauncherInput,
                                        showAccessibilityRationale,
                                        getAccessibilityExceptionStringRes)
                        )),

                generateControlHandlerSupplier(CursorInput.class,
                        getPriority.apply(R.string.input_method_preferences_mouse_cursor_priority_key, CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY),
                        Map.of(
                                CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY, new ControlHandlerInfo<>(context,
                                        () -> accessibilityFakeCursorInput,
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
                                        () -> accessibilityAssistedImeDirectionalPadInput,
                                        showAccessibilityRationale,
                                        getAccessibilityExceptionStringRes),
                                CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY, new ControlHandlerInfo<>(context,
                                        () -> accessibilityDirectionalPadInput,
                                        showAccessibilityRationale,
                                        getAccessibilityExceptionStringRes),
                                CONTROL_PRIORITY_IDENTIFIER_IME, new ControlHandlerInfo<>(context,
                                        () -> imeDirectionalPadInput,
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
                                        () -> accessibilityKeyboardInput,
                                        showAccessibilityRationale,
                                        getAccessibilityExceptionStringRes),
                                CONTROL_PRIORITY_IDENTIFIER_IME, new ControlHandlerInfo<>(context,
                                        () -> imeKeyboardInput,
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
                                        () -> notificationListenerMediaInput,
                                        showNotificationListenerRationale,
                                        getNotificationListenerExceptionStringRes),
                                CONTROL_PRIORITY_IDENTIFIER_IME, new ControlHandlerInfo<>(context,
                                        () -> imeMediaInput,
                                        showImeRationale,
                                        getImeExceptionStringRes)
                        )),

                generateControlHandlerSupplier(FullNavigationInput.class,
                        getPriority.apply(R.string.input_method_preferences_full_navigation_priority_key, CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY),
                        Map.of(
                                CONTROL_PRIORITY_IDENTIFIER_ACCESSIBILITY, new ControlHandlerInfo<>(context,
                                        () -> accessibilityFullNavigationInput,
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
                                        () -> accessibilityFullNavigationInput,
                                        showAccessibilityRationale,
                                        getAccessibilityExceptionStringRes),
                                CONTROL_PRIORITY_IDENTIFIER_IME, new ControlHandlerInfo<>(context,
                                        () -> imeBackNavigationInput,
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
                                        () -> accessibilityVolumeInput,
                                        showAccessibilityRationale,
                                        getAccessibilityExceptionStringRes),
                                CONTROL_PRIORITY_IDENTIFIER_IME, new ControlHandlerInfo<>(context,
                                        () -> imeVolumeInput,
                                        showImeRationale,
                                        getImeExceptionStringRes)
                        )),

                () -> {
                    synchronized (inputLock) {
                        if (accessibilityOverlayOutput != null) return accessibilityOverlayOutput;
                    }
                    // don't show a rationale for every notification that would be annoying
                    throw new ControlNotInitializedException(context.getString(R.string.control_source_not_loaded_accessibility));
                },
                () -> {
                    if (!applicationOverlayOutput.checkPermission())
                        throw new ControlNotInitializedException(context.getString(R.string.control_source_not_loaded_application_overlay));
                    return applicationOverlayOutput;
                },
                () -> {
                    if (!applicationOverlayOutput.checkPermission())
                        throw new ControlNotInitializedException(context.getString(R.string.control_source_not_loaded_application_overlay));
                    return applicationOverlayOutput;
                }
        );
    }

    private class AccessibilityInputServiceConnection extends MakeshiftServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "AccessibilityInputService connected");

            AccessibilityInputService.AccessibilityInputHandler binder = (AccessibilityInputService.AccessibilityInputHandler) service;

            // set accessibility control methods
            synchronized (inputLock) {
                accessibilityDirectionalPadInput = binder.getDirectionalPadInput();
                accessibilityFullNavigationInput = binder.getFullNavigationInput();
                accessibilityAssistedImeDirectionalPadInput = binder.getAssistedImeDirectionalPadInput();
                accessibilityFakeCursorInput = binder.getCursorInput();
                accessibilityVolumeInput = binder.getVolumeInput();
                accessibilityActivityLauncherInput = binder.getActivityLauncherInput();
                accessibilityKeyboardInput = binder.getKeyboardInput();

                accessibilityOverlayOutput = binder.getOverlayOutput();
            }

            onBind.accept(binder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "AccessibilityInputService disconnected");

            synchronized (inputLock) {
                accessibilityDirectionalPadInput = null;
                accessibilityFullNavigationInput = null;
                accessibilityAssistedImeDirectionalPadInput = null;
                accessibilityFakeCursorInput = null;
                accessibilityVolumeInput = null;
                accessibilityActivityLauncherInput = null;
                accessibilityKeyboardInput = null;
                accessibilityOverlayOutput = null;
            }
        }
    }

    private class IMEInputServiceConnection extends MakeshiftServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "IMEInputService connected");

            IMEInputService.ServiceBinder binder = (IMEInputService.ServiceBinder) service;

            // set control methods
            synchronized (inputLock) {
                imeDirectionalPadInput = binder.getDirectionalPadInput();
                imeBackNavigationInput = binder.getBackNavigationInput();
                imeVolumeInput = binder.getVolumeInput();
                imeKeyboardInput = binder.getKeyboardInput();
                imeMediaInput = binder.getMediaInput();
            }

            onBind.accept(binder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "IMEInputService disconnected");

            synchronized (inputLock) {
                imeDirectionalPadInput = null;
                imeBackNavigationInput = null;
                imeVolumeInput = null;
                imeKeyboardInput = null;
                imeMediaInput = null;
            }
        }
    }

    private void refreshNotificationInputServiceConnectionLocked() {
        Log.v(TAG, "recreating NotificationInputService connection");

        try {
            context.unbindService(notificationInputServiceConnection);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "failed to unbind NotificationInputService connection", e);
            assert false;
        }

        notificationInputServiceConnection = new NotificationInputServiceConnection();

        Intent intent = new Intent(context, NotificationInputService.class);
        boolean bindResult = context.bindService(intent, notificationInputServiceConnection, 0);
        assert bindResult;
    }

    private class NotificationInputServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "NotificationInputService connected");
            NotificationInputService.ServiceBinder binder = (NotificationInputService.ServiceBinder) service;

            synchronized (inputLock) {
                notificationListenerMediaInput = binder.getMediaInput();
            }

            onBind.accept(binder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "NotificationInputService disconnected");

            synchronized (inputLock) {
                notificationListenerMediaInput = null;
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.v(TAG, "NotificationInputService binding deceased");
            ServiceConnection.super.onBindingDied(name);
            synchronized (deathLock) {
                if (dead) return;   // from destroy()

                // when the service is toggled (on -> off) in settings it will kill this connection, which must be recreated
                refreshNotificationInputServiceConnectionLocked();
            }
        }
    }
}
