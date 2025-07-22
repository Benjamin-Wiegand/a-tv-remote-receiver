package io.benwiegand.atvremote.receiver.control;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import java.util.function.Consumer;

import io.benwiegand.atvremote.receiver.control.input.ActivityLauncherInput;
import io.benwiegand.atvremote.receiver.control.input.BackNavigationInput;
import io.benwiegand.atvremote.receiver.control.input.CursorInput;
import io.benwiegand.atvremote.receiver.control.input.DirectionalPadInput;
import io.benwiegand.atvremote.receiver.control.input.FullNavigationInput;
import io.benwiegand.atvremote.receiver.control.input.KeyboardInput;
import io.benwiegand.atvremote.receiver.control.input.MediaInput;
import io.benwiegand.atvremote.receiver.control.input.PowerInput;
import io.benwiegand.atvremote.receiver.control.input.VolumeInput;
import io.benwiegand.atvremote.receiver.control.output.OverlayOutput;
import io.benwiegand.atvremote.receiver.stuff.Destroyable;
import io.benwiegand.atvremote.receiver.stuff.makeshiftbind.MakeshiftServiceConnection;

/**
 * connects to all control sources and provides them
 */
public class ControlSourceConnector implements Destroyable {
    private static final String TAG = ControlSourceConnector.class.getSimpleName();

    private final MakeshiftServiceConnection accessibilityInputServiceConnection = new AccessibilityInputServiceConnection();
    private final MakeshiftServiceConnection imeInputServiceConnection = new IMEInputServiceConnection();
    private ServiceConnection notificationInputServiceConnection = new NotificationInputServiceConnection();

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
    private PowerInput accessibilityPowerInput = null;
    private OverlayOutput accessibilityOverlayOutput = null;

    private MediaInput notificationListenerMediaInput = null;

    private DirectionalPadInput imeDirectionalPadInput = null;
    private BackNavigationInput imeBackNavigationInput = null;
    private KeyboardInput imeKeyboardInput = null;
    private MediaInput imeMediaInput = null;
    private VolumeInput imeVolumeInput = null;

    private final ApplicationOverlayOutputHandler applicationOverlayOutput;

    public ControlSourceConnector(Context context, Consumer<IBinder> onBind) {
        this.context = context;
        this.onBind = onBind;

        applicationOverlayOutput = new ApplicationOverlayOutputHandler(context);

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
                accessibilityPowerInput = binder.getPowerInput();

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
                accessibilityPowerInput = null;
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

    public ActivityLauncherInput getAccessibilityActivityLauncherInput() {
        synchronized (inputLock) {
            return accessibilityActivityLauncherInput;
        }
    }

    public CursorInput getAccessibilityFakeCursorInput() {
        synchronized (inputLock) {
            return accessibilityFakeCursorInput;
        }
    }

    public DirectionalPadInput getAccessibilityDirectionalPadInput() {
        synchronized (inputLock) {
            return accessibilityDirectionalPadInput;
        }
    }

    public DirectionalPadInput getAccessibilityAssistedImeDirectionalPadInput() {
        synchronized (inputLock) {
            return accessibilityAssistedImeDirectionalPadInput;
        }
    }

    public KeyboardInput getAccessibilityKeyboardInput() {
        synchronized (inputLock) {
            return accessibilityKeyboardInput;
        }
    }

    public FullNavigationInput getAccessibilityFullNavigationInput() {
        synchronized (inputLock) {
            return accessibilityFullNavigationInput;
        }
    }

    public VolumeInput getAccessibilityVolumeInput() {
        synchronized (inputLock) {
            return accessibilityVolumeInput;
        }
    }

    public PowerInput getAccessibilityPowerInput() {
        synchronized (inputLock) {
            return accessibilityPowerInput;
        }
    }

    public OverlayOutput getAccessibilityOverlayOutput() {
        synchronized (inputLock) {
            return accessibilityOverlayOutput;
        }
    }

    public MediaInput getNotificationListenerMediaInput() {
        synchronized (inputLock) {
            return notificationListenerMediaInput;
        }
    }

    public DirectionalPadInput getImeDirectionalPadInput() {
        synchronized (inputLock) {
            return imeDirectionalPadInput;
        }
    }

    public BackNavigationInput getImeBackNavigationInput() {
        synchronized (inputLock) {
            return imeBackNavigationInput;
        }
    }

    public KeyboardInput getImeKeyboardInput() {
        synchronized (inputLock) {
            return imeKeyboardInput;
        }
    }

    public MediaInput getImeMediaInput() {
        synchronized (inputLock) {
        return imeMediaInput;
        }
    }

    public VolumeInput getImeVolumeInput() {
        synchronized (inputLock) {
            return imeVolumeInput;
        }
    }

    public ApplicationOverlayOutputHandler getApplicationOverlayOutput() {
        synchronized (inputLock) {
            // this one is only usable if it has permission at runtime.
            // permission does not affect its presence, unlike everything else.
            if (!applicationOverlayOutput.checkPermission()) return null;
            return applicationOverlayOutput;
        }
    }
}
