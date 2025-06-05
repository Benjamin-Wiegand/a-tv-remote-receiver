package io.benwiegand.atvremote.receiver.control;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.function.Consumer;

import io.benwiegand.atvremote.receiver.R;

public class ControlSourceConnectionManager {
    private static final String TAG = ControlSourceConnectionManager.class.getSimpleName();

    private final BroadcastReceiver accessibilityServiceBinderReceiver = new AccessibilityServiceBinderReceiver();
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
        ControlSourceErrors controlSourceErrors = new ControlSourceErrors(
                accessibilityServiceException,
                accessibilityServiceException,
                accessibilityServiceException,
                "not implemented",
                notificationServiceException,
                accessibilityServiceException,
                "not implemented",
                accessibilityServiceException,
                accessibilityServiceException
        );

        controlScheme = new ControlScheme(controlSourceErrors);

        // "bind" accessibility service
        IntentFilter filter = new IntentFilter();
        filter.addAction(AccessibilityInputService.INTENT_ACCESSIBILITY_INPUT_BINDER_INSTANCE);
        LocalBroadcastManager.getInstance(context).registerReceiver(accessibilityServiceBinderReceiver, filter);
        requestAccessibilityBinder();

        // bind notification listener service
        Intent notificationInputServiceIntent = new Intent(context, NotificationInputService.class);
        boolean bindResult = context.bindService(notificationInputServiceIntent, notificationInputServiceConnection, 0);
        assert bindResult;
    }

    public void destroy() {
        synchronized (deathLock) {
            dead = true;
        }
        LocalBroadcastManager.getInstance(context).unregisterReceiver(accessibilityServiceBinderReceiver);

        context.unbindService(notificationInputServiceConnection);
    }

    public ControlScheme getControlScheme() {
        return controlScheme;
    }

    private void requestAccessibilityBinder() {
        Log.v(TAG, "requesting accessibility service binder");
        Intent intent = new Intent(AccessibilityInputService.INTENT_ACCESSIBILITY_INPUT_BINDER_REQUEST);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    public class AccessibilityServiceBinderReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "got accessibility binder instance intent");
            Bundle extras = intent.getExtras();
            assert extras != null; // this intent should always have an extra

            AccessibilityInputService.AccessibilityInputHandler binder = (AccessibilityInputService.AccessibilityInputHandler) extras.getBinder(AccessibilityInputService.EXTRA_BINDER_INSTANCE);
            Log.i(TAG, "accessibility binder instance: " + binder);
            assert binder != null; // this extra should never be null

            // set accessibility control methods
            controlScheme.setDirectionalPadInput(binder.getDirectionalPadInput());
            controlScheme.setNavigationInput(binder.getNavigationInput());
            controlScheme.setCursorInput(binder.getCursorInput());
            controlScheme.setVolumeInput(binder.getVolumeInput());
            controlScheme.setActivityLauncherInput(binder.getActivityLauncherInput());

            controlScheme.setOverlayOutput(binder.getOverlayOutput());

            onBind.accept(binder);
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
