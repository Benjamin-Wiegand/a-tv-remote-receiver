package io.benwiegand.atvremote.receiver.control;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.control.cursor.AccessibilityGestureCursor;
import io.benwiegand.atvremote.receiver.control.input.ActivityLauncherInput;
import io.benwiegand.atvremote.receiver.control.input.CursorInput;
import io.benwiegand.atvremote.receiver.control.input.DirectionalPadInput;
import io.benwiegand.atvremote.receiver.control.input.NavigationInput;
import io.benwiegand.atvremote.receiver.control.input.VolumeInput;
import io.benwiegand.atvremote.receiver.control.output.OverlayOutput;
import io.benwiegand.atvremote.receiver.protocol.PairingCallback;
import io.benwiegand.atvremote.receiver.ui.DebugOverlay;
import io.benwiegand.atvremote.receiver.ui.NotificationOverlay;
import io.benwiegand.atvremote.receiver.ui.PairingDialog;

public class AccessibilityInputService extends AccessibilityService {
    private static final String TAG = AccessibilityInputService.class.getSimpleName();

    public static final String INTENT_ACCESSIBILITY_INPUT_BINDER_REQUEST = "io.benwiegand.atvremote.receiver.control.accessibilityinput.GIVE_ME_BINDER";
    public static final String INTENT_ACCESSIBILITY_INPUT_BINDER_INSTANCE = "io.benwiegand.atvremote.receiver.control.accessibilityinput.BINDER_INSTANCE";
    public static final String EXTRA_BINDER_INSTANCE = "binder";

    /**
     * fake dpad is necessary because there is no GLOBAL_ACTION_DPAD_(whatever) before api 33
     */
    private static final boolean USES_FAKE_DPAD = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU;

    private static final String DEBUG_OVERLAY_KEYBOARD_DETECTION = "keyboard";
    private static final int DEBUG_OVERLAY_KEYBOARD_DETECTION_COLOR = 0xFFFF0000; // red
    private static final String DEBUG_OVERLAY_NEW_FOCUS = "newFocus";
    private static final int DEBUG_OVERLAY_NEW_FOCUS_COLOR = 0xFF00FF00; // green
    private static final String DEBUG_OVERLAY_OLD_FOCUS = "oldFocus";
    private static final int DEBUG_OVERLAY_OLD_FOCUS_COLOR = 0xFFFFAA00; // orange
    private static final String DEBUG_OVERLAY_FOCUSABLE_WINDOWS = "focusableWindows";
    private static final int DEBUG_OVERLAY_FOCUSABLE_WINDOWS_COLOR = 0xFFFF00FF; // magenta
    private static final String DEBUG_OVERLAY_ACTIVE_WINDOW = "activeWindow";
    private static final int DEBUG_OVERLAY_ACTIVE_WINDOW_COLOR = 0xFF0000FF; // blue

    private final AccessibilityInputHandler binder = new AccessibilityInputHandler();
    private final BroadcastReceiver receiver = new Receiver();

    private CursorInput cursorInput = null;
    private final DirectionalPadInput directionalPadInput = new DirectionalPadInputHandler();
    private final NavigationInput navigationInput = new NavigationInputHandler();
    private final VolumeInput volumeInput = new VolumeInputHandler();
    private final ActivityLauncherInput activityLauncherInput = new ActivityLauncherInputHandler();
    private final OverlayOutput overlayOutput = new OverlayOutputHandler();

    private NotificationOverlay notificationOverlay = null;
    private DebugOverlay debugOverlay = null;

    private boolean softKeyboardOpen = false;
    private AccessibilityNodeInfo softKeyboardFocusedNode = null;


    @SuppressLint("InlinedApi")
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "service connected!");

        IntentFilter filter = new IntentFilter();
        filter.addAction(INTENT_ACCESSIBILITY_INPUT_BINDER_REQUEST);
        LocalBroadcastManager
                .getInstance(this)
                .registerReceiver(receiver, filter);

        cursorInput = new AccessibilityGestureCursor(this);
        notificationOverlay = new NotificationOverlay(this);
        notificationOverlay.start();

        debugOverlay = new DebugOverlay(this);
        debugOverlay.start();

        broadcastBinder();
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "onInterrupt()");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "onUnbind()");
        if (cursorInput != null) cursorInput.destroy();
        return super.onUnbind(intent);
    }

    private void broadcastBinder() {
        Log.d(TAG, "sending binder instance broadcast");
        Intent intent = new Intent(INTENT_ACCESSIBILITY_INPUT_BINDER_INSTANCE);
        Bundle extras = new Bundle();
        extras.putBinder(EXTRA_BINDER_INSTANCE,  binder);
        intent.putExtras(extras);

        LocalBroadcastManager
                .getInstance(this)
                .sendBroadcast(intent);
    }

    public class Receiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            // makeshift bind
            Log.d(TAG, "got binder request");
            broadcastBinder();
        }
    }

    private void checkSoftKeyboard(AccessibilityEvent event) {
        // optional filter
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if ((event.getWindowChanges() & (AccessibilityEvent.WINDOWS_CHANGE_ADDED | AccessibilityEvent.WINDOWS_CHANGE_REMOVED)) == 0)
                return;
        }

        AccessibilityWindowInfo softKeyboardWindow = null;
        List<AccessibilityWindowInfo> windows = getWindows();
        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                softKeyboardWindow = window;
                break;
            }
        }

        boolean currentlySoftKeyboardOpen = softKeyboardWindow != null;
        if (currentlySoftKeyboardOpen == softKeyboardOpen) return;
        softKeyboardOpen = currentlySoftKeyboardOpen;

        if (softKeyboardOpen) {
            // set things up so fakeDpad() can use the keyboard
            Log.v(TAG, "soft keyboard opened");

            AccessibilityNodeInfo node = findFocusedNode(softKeyboardWindow);
            if (node == null) {
                // try to find and focus a node
                node = findFirstFocusableNode(softKeyboardWindow);
                if (node == null) {
                    Log.e(TAG, "no focusable node found in keyboard overlay, it will not be usable!");
                    return;
                }
                node = tryFocusNodeOrChild(node);
                if (node == null) {
                    Log.e(TAG, "failed to focus node in keyboard overlay, it will not be usable!");
                    return;
                }
            }

            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            debugOverlay.drawRect(DEBUG_OVERLAY_KEYBOARD_DETECTION, rect, DEBUG_OVERLAY_KEYBOARD_DETECTION_COLOR);

            softKeyboardFocusedNode = node;
        } else {
            Log.v(TAG, "soft keyboard closed");
            debugOverlay.removeRect(DEBUG_OVERLAY_KEYBOARD_DETECTION);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        Log.d(TAG, "onAccessibilityEvent()");

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            Log.d(TAG, "windows changed event");
            checkSoftKeyboard(event);
        }
    }

    /**
     * traverses children until criteria is met
     * @param node node to start at
     * @param criteria function, provided with the node, should return true if accepted
     * @return first node to match the criteria, or null if none do
     */
    private AccessibilityNodeInfo traverseNodeChildren(AccessibilityNodeInfo node, Function<AccessibilityNodeInfo, Boolean> criteria) {
        if (node == null) return null;

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo childNode = node.getChild(i);

            if (criteria.apply(childNode)) {
                return childNode;
            }

            AccessibilityNodeInfo matchInChild = traverseNodeChildren(childNode, criteria);
            if (matchInChild != null) return matchInChild;
        }

        return null;
    }

    /**
     * @return the window currently with focus, the topmost window, or null if there are no windows
     */
    private AccessibilityWindowInfo findFocusedWindow() {
        List<AccessibilityWindowInfo> windows = getWindows();

        if (windows.isEmpty()) {
            Log.w(TAG, "no windows!");
            return null;
        }

        for (AccessibilityWindowInfo window : windows) {
            if (window.isFocused()) return window;
        }

        Log.d(TAG, "no focused window in stack, using topmost window");
        return windows.get(0);
    }

    /**
     * @return the currently focused window root, or null if none
     */
    private AccessibilityNodeInfo findFocusedWindowRoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) return root;

        Log.v(TAG, "getRootInActiveWindow() returned null");
        AccessibilityWindowInfo window = findFocusedWindow();
        if (window == null) return null;

        return window.getRoot();
    }

    /**
     * gets the focused node in the currently active window
     * @return the currently focused node, or null if none
     */
    private AccessibilityNodeInfo findFocusedNode() {
        AccessibilityNodeInfo root = findFocusedWindowRoot();
        if (root == null) return null;

        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
    }

    /**
     * gets the focused node in the provided window
     * @return the currently focused node, or null if none
     */
    private AccessibilityNodeInfo findFocusedNode(AccessibilityWindowInfo window) {
        AccessibilityNodeInfo root = window.getRoot();
        if (root == null) return null;

        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
    }

    /**
     * like findFocusedNode() but gets the focused node on the soft keyboard first if it's open
     * @return the currently focused node, or null if none
     */
    private AccessibilityNodeInfo findFocusedNodeIncludingKeyboard() {
        return isSoftKeyboardUsable() ? softKeyboardFocusedNode : findFocusedNode();
    }

    /**
     * traverses the children of the provided node until it finds a focusable node
     * and then returns it
     * @return first focusable node encountered, or null if none
     */
    private AccessibilityNodeInfo findFirstFocusableChild(AccessibilityNodeInfo node) {
        if (node == null) return null;
        return traverseNodeChildren(node, AccessibilityNodeInfo::isFocusable);
    }

    /**
     * traverses the children of the currently focused window until it finds a focusable node
     * and then returns it
     * @return first focusable node encountered, or null if none
     */
    private AccessibilityNodeInfo findFirstFocusableNode() {
        AccessibilityNodeInfo root = findFocusedWindowRoot();
        if (root == null) return null;
        if (root.isFocusable()) return root;
        return findFirstFocusableChild(root);
    }

    /**
     * traverses the children of the currently focused window until it finds a focusable node
     * and then returns it
     * @return first focusable node encountered, or null if none
     */
    private AccessibilityNodeInfo findFirstFocusableNode(AccessibilityWindowInfo window) {
        if (window == null) return null;
        AccessibilityNodeInfo root = window.getRoot();
        if (root == null) return null;
        if (root.isFocusable()) return root;
        return findFirstFocusableChild(root);
    }

    /**
     * tries to focus the provided node
     * @param node the node
     * @return true if the focus event returned true, false if not
     */
    private boolean tryFocusNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        return node.isFocusable() && node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
    }

    /**
     * tries to focus the provided node. if that fails, it traverses all children trying to focus
     * them until one is focused or there are no more children.
     * @param node the node
     * @return the node that was focused, or null if none
     */
    private AccessibilityNodeInfo tryFocusNodeOrChild(AccessibilityNodeInfo node) {
        if (tryFocusNode(node)) return node;
        return traverseNodeChildren(node, this::tryFocusNode);
    }

    private boolean isSoftKeyboardUsable() {
        return softKeyboardOpen && softKeyboardFocusedNode != null;
    }

    /**
     * tries to fake the behavior of a dpad using accessibility nodes.
     * finds the focused node and searches in the given direction.
     * if there is no node in that direction, it focuses any focusable child node it can find.
     * if there is no focused node, it finds the first focusable node and focuses that.
     * if that too fails, nothing happens.
     * @param direction the direction, using constants like View.FOCUS_[direction]
     */
    private void fakeDpad(int direction) {
        // this still sucks, but less now
        Log.v(TAG, "faking dpad in direction " + direction);

        // get the keyboard node if it's open
        boolean usingKeyboard = isSoftKeyboardUsable();
        AccessibilityNodeInfo node = usingKeyboard ? findFocusedNodeIncludingKeyboard() : findFocusedNode();

        AccessibilityNodeInfo newNode;
        if (node == null) {
            Log.v(TAG, "no focused node, finding one");
            newNode = findFirstFocusableNode();
        } else {
            newNode = node.focusSearch(direction);
            if (newNode == null) {
                Log.i(TAG, "search returned no node, checking children");
                newNode = findFirstFocusableChild(node);
            }
        }

        // debug rectangles
        Rect rect = new Rect();
        if (node != null) {
            node.getBoundsInScreen(rect);
            debugOverlay.drawRect(DEBUG_OVERLAY_OLD_FOCUS, rect, DEBUG_OVERLAY_OLD_FOCUS_COLOR);
        } else {
            debugOverlay.removeRect(DEBUG_OVERLAY_OLD_FOCUS);
        }

        rect.setEmpty();
        if (newNode != null) {
            newNode.getBoundsInScreen(rect);
            debugOverlay.drawRect(DEBUG_OVERLAY_NEW_FOCUS, rect, DEBUG_OVERLAY_NEW_FOCUS_COLOR);
        } else {
            debugOverlay.removeRect(DEBUG_OVERLAY_NEW_FOCUS);
        }

        rect.setEmpty();
        if (node != null) {
            node.getWindow().getBoundsInScreen(rect);
            debugOverlay.drawRect(DEBUG_OVERLAY_ACTIVE_WINDOW, rect, DEBUG_OVERLAY_ACTIVE_WINDOW_COLOR);
        } else {
            debugOverlay.removeRect(DEBUG_OVERLAY_ACTIVE_WINDOW);
        }

        List<AccessibilityWindowInfo> windows = getWindows();
        if (!windows.isEmpty()) {
            List<Rect> rects = new LinkedList<>();

            for (AccessibilityWindowInfo window : windows) {

                Log.d(TAG, "win: " + window);
                AccessibilityNodeInfo firstFocusable = findFirstFocusableNode(window);

                Rect tmpRect = new Rect();
                firstFocusable.getBoundsInScreen(tmpRect);
                rects.add(tmpRect);
            }

            debugOverlay.drawRectGroup(DEBUG_OVERLAY_FOCUSABLE_WINDOWS, rects, DEBUG_OVERLAY_FOCUSABLE_WINDOWS_COLOR);
        } else {
            debugOverlay.removeRect(DEBUG_OVERLAY_FOCUSABLE_WINDOWS);
        }

        if (newNode == null) {
            Log.i(TAG, "no node found");
            return;
        }

        if (tryFocusNode(newNode)) {
            if (usingKeyboard) softKeyboardFocusedNode = newNode;
        } else {
            Log.w(TAG, "focus action failed");
        }
    }

    public class DirectionalPadInputHandler implements DirectionalPadInput {

        @Override
        public void dpadDown() {
            if (USES_FAKE_DPAD) {
                fakeDpad(View.FOCUS_DOWN);
            } else {
                performGlobalAction(GLOBAL_ACTION_DPAD_DOWN);
            }
        }

        @Override
        public void dpadUp() {
            if (USES_FAKE_DPAD) {
                fakeDpad(View.FOCUS_UP);
            } else {
                performGlobalAction(GLOBAL_ACTION_DPAD_UP);
            }
        }

        @Override
        public void dpadLeft() {
            if (USES_FAKE_DPAD) {
                fakeDpad(View.FOCUS_LEFT);
            } else {
                performGlobalAction(GLOBAL_ACTION_DPAD_LEFT);
            }
        }

        @Override
        public void dpadRight() {
            if (USES_FAKE_DPAD) {
                fakeDpad(View.FOCUS_RIGHT);
            } else {
                performGlobalAction(GLOBAL_ACTION_DPAD_RIGHT);
            }
        }

        @Override
        public void dpadSelect() {
            if (USES_FAKE_DPAD) {
                AccessibilityNodeInfo node = findFocusedNodeIncludingKeyboard();
                if (node != null) node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } else {
                performGlobalAction(GLOBAL_ACTION_DPAD_CENTER);
            }
        }

        @Override
        public void dpadLongPress() {
            AccessibilityNodeInfo node = findFocusedNodeIncludingKeyboard();
            if (node != null) node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
        }

        @Override
        public void destroy() {

        }
    }

    public class NavigationInputHandler implements NavigationInput {

        @Override
        public void navHome() {
            performGlobalAction(GLOBAL_ACTION_HOME);
        }

        @Override
        public void navBack() {
            performGlobalAction(GLOBAL_ACTION_BACK);
        }

        @Override
        public void navRecent() {
            performGlobalAction(GLOBAL_ACTION_RECENTS);
        }

        @Override
        public void navApps() {
            // TODO: probably just remove this
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS);
            }
        }

        @Override
        public void navNotifications() {
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
        }

        @Override
        public void navQuickSettings() {
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
        }

        @Override
        public void destroy() {

        }
    }

    public class VolumeInputHandler implements VolumeInput {

        @Override
        public void volumeUp() {
            AudioManager audioManager = getSystemService(AudioManager.class);
            audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
        }

        @Override
        public void volumeDown() {
            AudioManager audioManager = getSystemService(AudioManager.class);
            audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
        }

        @Override
        public Optional<Boolean> getMute() {
            // todo
            return Optional.empty();
        }

        @Override
        public void setMute(boolean muted) {
            int input = muted ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
            AudioManager audioManager = getSystemService(AudioManager.class);
            audioManager.adjustVolume(input, AudioManager.FLAG_SHOW_UI);
        }

        @Override
        public void toggleMute() {
            AudioManager audioManager = getSystemService(AudioManager.class);
            audioManager.adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
        }

        @Override
        public void destroy() {

        }
    }

    public class ActivityLauncherInputHandler implements ActivityLauncherInput {
        @Override
        public void launchActivity(ComponentName activityComponent) {
            Log.d(TAG, "launching activity: " + activityComponent);
            Intent intent = new Intent();
            intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
            intent.setComponent(activityComponent);
            startActivity(intent);
        }

        @Override
        public void destroy() {

        }
    }

    // todo: add keyboard, media, scroll

    public class OverlayOutputHandler implements OverlayOutput {
        @Override
        public PairingDialog createPairingDialog(PairingCallback callback, int pairingCode, byte[] fingerprint) {
            return new PairingDialog(AccessibilityInputService.this, callback, pairingCode, fingerprint);
        }

        @Override
        public void displayNotification(String title, String description, int icon) {
            notificationOverlay.displayNotification(title, description, icon);
        }

        @Override
        public void displayNotification(int title, int description, int icon) {
            notificationOverlay.displayNotification(title, description, icon);
        }

        @Override
        public void displayNotification(String title, int description, int icon) {
            notificationOverlay.displayNotification(title, description, icon);
        }

        @Override
        public void displayNotification(int title, String description, int icon) {
            notificationOverlay.displayNotification(title, description, icon);
        }

        @Override
        public void destroy() {

        }
    }

    public class AccessibilityInputHandler extends Binder {

        public void showTestPairingDialog() {

            AtomicReference<PairingDialog> pd = new AtomicReference<>();

            PairingCallback cb = new PairingCallback() {
                @Override
                public void cancel() {
                    pd.get().destroy();
                }

                @Override
                public void disablePairingForAWhile(TimeUnit timeUnit, long period) {
                    pd.get().destroy();
                }
            };

            pd.set(new PairingDialog(AccessibilityInputService.this, cb, 696969, "deez nuts".getBytes(StandardCharsets.UTF_8)));
            pd.get().start();
        }

        public void showTestNotification() {
            notificationOverlay.displayNotification("Test notification", "this is a test", R.drawable.accepted);

        }

        public DirectionalPadInput getDirectionalPadInput() {
            return directionalPadInput;
        }

        public NavigationInput getNavigationInput() {
            return navigationInput;
        }

        public CursorInput getCursorInput() {
            return cursorInput;
        }

        public VolumeInput getVolumeInput() {
            return volumeInput;
        }

        public ActivityLauncherInput getActivityLauncherInput() {
            return activityLauncherInput;
        }

        public OverlayOutput getOverlayOutput() {
            return overlayOutput;
        }
    }
}
