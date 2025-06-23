package io.benwiegand.atvremote.receiver.control;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import java.util.function.Consumer;

import io.benwiegand.atvremote.receiver.R;
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

    public ControlSourceConnectionManager(Context context, Consumer<IBinder> onBind) {
        this.context = context;
        this.onBind = onBind;

        String accessibilityServiceException = context.getString(R.string.control_source_not_loaded_accessibility);
        String notificationServiceException = context.getString(R.string.control_source_not_loaded_notification_listener);

        String imeDisabledServiceException = context.getString(R.string.control_source_not_loaded_enable_ime);
        String imeInactiveServiceException = context.getString(R.string.control_source_not_loaded_switch_to_ime);

        // todo
        boolean imeEnabled = false;
        try {
            imeEnabled = IMEInputService.isEnabled(context);
        } catch (Throwable t) {
            Log.e(TAG, "failed to determine if input method is enabled", t);
        }

        String imeServiceException = imeEnabled ? imeInactiveServiceException : imeDisabledServiceException;

        ControlSourceErrors controlSourceErrors = new ControlSourceErrors(
                accessibilityServiceException,
                accessibilityServiceException,
                accessibilityServiceException,
                imeServiceException,
                notificationServiceException,
                accessibilityServiceException,
                "not implemented",
                accessibilityServiceException,
                accessibilityServiceException
        );

        controlScheme = new ControlScheme(controlSourceErrors);

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

        context.unbindService(notificationInputServiceConnection);
    }

    public ControlScheme getControlScheme() {
        return controlScheme;
    }

    private class AccessibilityInputServiceConnection extends MakeshiftServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "AccessibilityInputService connected");

            AccessibilityInputService.AccessibilityInputHandler binder = (AccessibilityInputService.AccessibilityInputHandler) service;

            // set accessibility control methods
            controlScheme.setDirectionalPadInput(binder.getDirectionalPadInput());
            controlScheme.setNavigationInput(binder.getNavigationInput());
            controlScheme.setCursorInput(binder.getCursorInput());
            controlScheme.setVolumeInput(binder.getVolumeInput());
            controlScheme.setActivityLauncherInput(binder.getActivityLauncherInput());

            controlScheme.setOverlayOutput(binder.getOverlayOutput());

            onBind.accept(binder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "AccessibilityInputService disconnected");

            controlScheme.setDirectionalPadInput(null);
            controlScheme.setNavigationInput(null);
            controlScheme.setCursorInput(null);
            controlScheme.setVolumeInput(null);
            controlScheme.setActivityLauncherInput(null);
            controlScheme.setOverlayOutput(null);
        }
    }

    private class IMEInputServiceConnection extends MakeshiftServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "IMEInputService connected");

            IMEInputService.ServiceBinder binder = (IMEInputService.ServiceBinder) service;

            // set control methods
            // todo
//            controlScheme.setDirectionalPadInput(binder.getDirectionalPadInput());
//            controlScheme.setVolumeInput(binder.getVolumeInput());
            controlScheme.setKeyboardInput(binder.getKeyboardInput());

            onBind.accept(binder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "IMEInputService disconnected");

//            controlScheme.setDirectionalPadInput(null);
//            controlScheme.setVolumeInput(null);
            controlScheme.setKeyboardInput(null);
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
            controlScheme.setMediaInput(binder.getMediaInput());
            onBind.accept(binder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "NotificationInputService disconnected");
            controlScheme.setMediaInput(null);
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
