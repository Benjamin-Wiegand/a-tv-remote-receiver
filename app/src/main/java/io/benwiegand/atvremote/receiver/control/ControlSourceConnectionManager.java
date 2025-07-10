package io.benwiegand.atvremote.receiver.control;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.StringRes;

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

public class ControlSourceConnectionManager {
    private static final String TAG = ControlSourceConnectionManager.class.getSimpleName();

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

        // todo: replace these exception messages when the ui is finished
        controlScheme = new ControlScheme(
                () -> lockForControls(() -> accessibilityActivityLauncherInput, R.string.control_source_not_loaded_accessibility),
                () -> lockForControls(() -> accessibilityFakeCursorInput, R.string.control_source_not_loaded_accessibility),
                () -> {
                    synchronized (inputLock) {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            if (accessibilityAssistedImeDirectionalPadInput != null) return accessibilityAssistedImeDirectionalPadInput;
                            if (accessibilityDirectionalPadInput != null) return accessibilityDirectionalPadInput;
                        } else {
                            if (accessibilityDirectionalPadInput != null) return accessibilityDirectionalPadInput;
                            if (accessibilityAssistedImeDirectionalPadInput != null) return accessibilityAssistedImeDirectionalPadInput;
                        }
                        if (imeDirectionalPadInput != null) return imeDirectionalPadInput;
                    }
                    throw new ControlNotInitializedException(context.getString(R.string.control_source_not_loaded_accessibility));
                },
                () -> {
                    synchronized (inputLock) {
                        if (imeKeyboardInput != null) return imeKeyboardInput;
                        if (accessibilityKeyboardInput != null) return accessibilityKeyboardInput;
                    }
                    throw new ControlNotInitializedException(getImeServiceExceptionText());
                },
                () -> {
                    synchronized (inputLock) {
                        if (imeMediaInput != null) return imeMediaInput;
                        if (notificationListenerMediaInput != null) return notificationListenerMediaInput;
                    }
                    throw new ControlNotInitializedException(getImeServiceExceptionText());
                },
                () -> lockForControls(() -> accessibilityFullNavigationInput, R.string.control_source_not_loaded_accessibility),
                () -> {
                    synchronized (inputLock) {
                        if (accessibilityFullNavigationInput != null) return accessibilityFullNavigationInput;
                        if (imeBackNavigationInput != null) return imeBackNavigationInput;
                    }
                    throw new ControlNotInitializedException(context.getString(R.string.control_source_not_loaded_accessibility));
                },
                () -> {
                    throw new ControlNotInitializedException("not implemented");
                },
                () -> {
                    synchronized (inputLock) {
                        if (accessibilityVolumeInput != null) return accessibilityVolumeInput;
                        if (imeVolumeInput != null) return imeVolumeInput;
                    }
                    throw new ControlNotInitializedException(context.getString(R.string.control_source_not_loaded_accessibility));
                },
                () -> lockForControls(() -> accessibilityOverlayOutput, R.string.control_source_not_loaded_accessibility),
                () -> applicationOverlayOutput,
                () -> applicationOverlayOutput
        );

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

    private <T extends ControlHandler> T lockForControls(Supplier<T> supplier, @StringRes int exceptionMessage) {
        T controlHandler;
        synchronized (inputLock) {
            controlHandler = supplier.get();
        }
        if (controlHandler == null)
            throw new ControlNotInitializedException(context.getString(exceptionMessage));
        return controlHandler;
    }

    private String getImeServiceExceptionText() {
        String imeDisabledServiceException = context.getString(R.string.control_source_not_loaded_enable_ime);
        String imeInactiveServiceException = context.getString(R.string.control_source_not_loaded_switch_to_ime);

        boolean imeEnabled = false;
        try {
            imeEnabled = IMEInputService.isEnabled(context);
        } catch (Throwable t) {
            Log.e(TAG, "failed to determine if input method is enabled", t);
        }

        return imeEnabled ? imeInactiveServiceException : imeDisabledServiceException;
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
