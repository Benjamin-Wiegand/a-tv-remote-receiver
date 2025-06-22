package io.benwiegand.atvremote.receiver.control;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.RequiresApi;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.control.cursor.AccessibilityGestureCursor;
import io.benwiegand.atvremote.receiver.control.cursor.FakeCursor;
import io.benwiegand.atvremote.receiver.control.input.ActivityLauncherInput;
import io.benwiegand.atvremote.receiver.control.input.CursorInput;
import io.benwiegand.atvremote.receiver.control.input.DirectionalPadInput;
import io.benwiegand.atvremote.receiver.control.input.NavigationInput;
import io.benwiegand.atvremote.receiver.control.input.VolumeInput;
import io.benwiegand.atvremote.receiver.control.output.OverlayOutput;
import io.benwiegand.atvremote.receiver.protocol.KeyEventType;
import io.benwiegand.atvremote.receiver.protocol.PairingCallback;
import io.benwiegand.atvremote.receiver.stuff.makeshiftbind.MakeshiftBind;
import io.benwiegand.atvremote.receiver.stuff.makeshiftbind.MakeshiftBindCallback;
import io.benwiegand.atvremote.receiver.stuff.makeshiftbind.MakeshiftServiceConnection;
import io.benwiegand.atvremote.receiver.ui.DebugOverlay;
import io.benwiegand.atvremote.receiver.ui.NotificationOverlay;
import io.benwiegand.atvremote.receiver.ui.PairingDialog;

public class AccessibilityInputService extends AccessibilityService implements MakeshiftBindCallback {
    private static final String TAG = AccessibilityInputService.class.getSimpleName();

    /**
     * fake dpad is necessary because:
     * <ul>
     *     <li>there is no GLOBAL_ACTION_DPAD_(whatever) before api 33</li>
     *     <li>there is no way to switch IME without user confirmation before api 30</li>
     * </ul>
     */
    public static final boolean USES_FAKE_DPAD = false;

    /**
     * fake dpad can be avoided on api 30, 31, and 32 by leveraging the IME input and switching
     * between it and the user configured on-screen keyboard.
     * fake dpad will still have to be used to control the on-screen keyboard.
     */
    public static final boolean USES_IME_DPAD_ASSIST = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !USES_FAKE_DPAD;

    private static final String DEBUG_OVERLAY_KEYBOARD_DETECTION = "keyboard";
    private static final int DEBUG_OVERLAY_KEYBOARD_DETECTION_COLOR = 0xFFFF0000; // red
    private static final String DEBUG_OVERLAY_KEYBOARD_FOCUSABLE = "keyboardFocusable";
    private static final int DEBUG_OVERLAY_KEYBOARD_FOCUSABLE_COLOR = 0xFFFFAAAA; // light red
    private static final String DEBUG_OVERLAY_NEW_FOCUS = "newFocus";
    private static final int DEBUG_OVERLAY_NEW_FOCUS_COLOR = 0xFF00FF00; // green
    private static final String DEBUG_OVERLAY_OLD_FOCUS = "oldFocus";
    private static final int DEBUG_OVERLAY_OLD_FOCUS_COLOR = 0xFFFFAA00; // orange
    private static final String DEBUG_OVERLAY_FOCUSABLE_WINDOWS = "focusableWindows";
    private static final int DEBUG_OVERLAY_FOCUSABLE_WINDOWS_COLOR = 0xFFFF00FF; // magenta
    private static final String DEBUG_OVERLAY_ACTIVE_WINDOW = "activeWindow";
    private static final int DEBUG_OVERLAY_ACTIVE_WINDOW_COLOR = 0xFF0000FF; // blue
    private static final String DEBUG_OVERLAY_SHOW_MATCHING_NODES = "matchingNodes";
    private static final int DEBUG_OVERLAY_SHOW_MATCHING_NODES_COLOR = 0xFF00FFFF; // cyan

    private final AccessibilityInputHandler binder = new AccessibilityInputHandler();
    private MakeshiftBind makeshiftBind = null;

    private FakeCursor cursorInput = null;
    private final DirectionalPadInput directionalPadInput = new DirectionalPadInputHandler();
    private final NavigationInput navigationInput = new NavigationInputHandler();
    private final VolumeInput volumeInput = new VolumeInputHandler();
    private final ActivityLauncherInput activityLauncherInput = new ActivityLauncherInputHandler();
    private final OverlayOutput overlayOutput = new OverlayOutputHandler();

    private NotificationOverlay notificationOverlay = null;

    private DebugOverlay debugOverlay = null;
    private NodeCondition debugShowMatchingNodesCondition = null;

    // ime dpad assist
    // for api 30, 31, and 32
    private MakeshiftServiceConnection imeInputServiceConnection = new IMEInputServiceConnection();
    private DirectionalPadInput imeDirectionalPadInput = null;

    private boolean softKeyboardOpen = false;
    private AccessibilityWindowInfo softKeyboardWindow = null;


    @SuppressLint("InlinedApi")
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "accessibility service connected");

        makeshiftBind = new MakeshiftBind(this, new ComponentName(this, AccessibilityInputService.class), this);

        cursorInput = new AccessibilityGestureCursor(this);
        notificationOverlay = new NotificationOverlay(this);
        notificationOverlay.start();

        if (USES_IME_DPAD_ASSIST) {
            MakeshiftServiceConnection.bindService(this, new ComponentName(this, IMEInputService.class), imeInputServiceConnection);
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "onInterrupt()");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "accessibility service disconnected");
        if (cursorInput != null) cursorInput.destroy();
        makeshiftBind.destroy();
        imeInputServiceConnection.destroy();
        return super.onUnbind(intent);
    }

    @Override
    public IBinder onMakeshiftBind(Intent intent) {
        return binder;
    }

    private void checkSoftKeyboard(AccessibilityEvent event) {
        // optional filter
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if ((event.getWindowChanges() & (AccessibilityEvent.WINDOWS_CHANGE_ADDED | AccessibilityEvent.WINDOWS_CHANGE_REMOVED)) == 0)
                return;
        }

        AccessibilityWindowInfo currentSoftKeyboardWindow = null;
        List<AccessibilityWindowInfo> windows = getWindows();
        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                currentSoftKeyboardWindow = window;
                break;
            }
        }

        boolean currentlySoftKeyboardOpen = currentSoftKeyboardWindow != null;
        if (currentlySoftKeyboardOpen == softKeyboardOpen) return;
        softKeyboardOpen = currentlySoftKeyboardOpen;
        softKeyboardWindow = currentSoftKeyboardWindow;

        if (softKeyboardOpen) {
            // set things up so fakeDpad() can use the keyboard
            Log.v(TAG, "soft keyboard opened");

            // debug box to show keyboard recognition
            if (isDebugOverlayEnabled()) {
                AccessibilityNodeInfo node = findFirstFocusableNode(softKeyboardWindow);
                debugDrawRect(DEBUG_OVERLAY_KEYBOARD_DETECTION, node, DEBUG_OVERLAY_KEYBOARD_DETECTION_COLOR);

                // also show focusable nodes (to gauge usability)
                List<AccessibilityNodeInfo> nodes = new LinkedList<>();
                traverseNodeChildren(node, child -> {
                    if (child.isFocusable())
                        nodes.add(child);
                    return false;
                });

                debugDrawNodeRectGroup(DEBUG_OVERLAY_KEYBOARD_FOCUSABLE, nodes.stream().toArray(AccessibilityNodeInfo[]::new), DEBUG_OVERLAY_KEYBOARD_FOCUSABLE_COLOR);
            }
        } else {
            Log.v(TAG, "soft keyboard closed");
            debugRemoveRect(DEBUG_OVERLAY_KEYBOARD_DETECTION);
            debugRemoveRect(DEBUG_OVERLAY_KEYBOARD_FOCUSABLE);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            Log.d(TAG, "windows changed event");
            checkSoftKeyboard(event);
        }
    }

    private boolean isDebugOverlayEnabled() {
        return debugOverlay != null;
    }

    private void debugDrawRect(String key, Rect rect, int color) {
        if (!isDebugOverlayEnabled()) return;
        debugOverlay.drawRect(key, rect, color);
    }

    private void debugDrawRect(String key, AccessibilityNodeInfo node, int color) {
        if (!isDebugOverlayEnabled()) return;
        if (node == null) {
            debugRemoveRect(key);
            return;
        }
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        debugDrawRect(key, rect, color);
    }

    private void debugDrawRect(String key, AccessibilityWindowInfo window, int color) {
        if (!isDebugOverlayEnabled()) return;
        if (window == null) {
            debugRemoveRect(key);
            return;
        }
        Rect rect = new Rect();
        window.getBoundsInScreen(rect);
        debugDrawRect(key, rect, color);
    }

    private void debugDrawRectGroup(String key, List<Rect> rects, int color) {
        if (!isDebugOverlayEnabled()) return;
        debugOverlay.drawRectGroup(key, rects, color);
    }

    private void debugDrawNodeRectGroup(String key, AccessibilityNodeInfo[] windows, int color) {
        if (!isDebugOverlayEnabled()) return;
        Rect[] rects = Arrays.stream(windows)
                .map(node -> {
                    Rect rect = new Rect();
                    node.getBoundsInScreen(rect);
                    return rect;
                })
                .toArray(Rect[]::new);

        debugDrawRectGroup(key, List.of(rects), color);
    }

    private void debugRemoveRect(String key) {
        if (!isDebugOverlayEnabled()) return;
        debugOverlay.removeRect(key);
    }

    private void debugShowMatchingNodes() {
        if (!isDebugOverlayEnabled() || debugShowMatchingNodesCondition == null) return;
        Function<AccessibilityNodeInfo, Boolean> criteria = debugShowMatchingNodesCondition.createCriteria();

        List<AccessibilityNodeInfo> matches = new LinkedList<>();

        for (AccessibilityWindowInfo window : getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;

            if (criteria.apply(root)) matches.add(root);
            traverseNodeChildren(window.getRoot(), child -> {
                if (criteria.apply(child)) matches.add(child);
                return false;
            });
        }

        AccessibilityNodeInfo[] nodes = matches.stream().toArray(AccessibilityNodeInfo[]::new);
        debugDrawNodeRectGroup(DEBUG_OVERLAY_SHOW_MATCHING_NODES, nodes, DEBUG_OVERLAY_SHOW_MATCHING_NODES_COLOR);
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
            if (childNode == null) continue;

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
        if (isSoftKeyboardUsable()) return findFocusedNode(softKeyboardWindow);
        return findFocusedNode();
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

    private boolean isSoftKeyboardUsable() {
        return softKeyboardOpen && softKeyboardWindow != null;
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
        AccessibilityNodeInfo node = findFocusedNodeIncludingKeyboard();

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
        debugDrawRect(DEBUG_OVERLAY_OLD_FOCUS, node, DEBUG_OVERLAY_OLD_FOCUS_COLOR);
        debugDrawRect(DEBUG_OVERLAY_NEW_FOCUS, newNode, DEBUG_OVERLAY_NEW_FOCUS_COLOR);
        if (node != null) debugDrawRect(DEBUG_OVERLAY_ACTIVE_WINDOW, node.getWindow(), DEBUG_OVERLAY_ACTIVE_WINDOW_COLOR);
        if (isDebugOverlayEnabled()) {
            // first focusable node in every window
            AccessibilityNodeInfo[] nodes = getWindows().stream()
                    .map(window -> {
                        AccessibilityNodeInfo root = window.getRoot();
                        if (root == null) return null;
                        return findFirstFocusableNode(window);
                    })
                    .filter(Objects::nonNull)
                    .toArray(AccessibilityNodeInfo[]::new);

            debugDrawNodeRectGroup(DEBUG_OVERLAY_FOCUSABLE_WINDOWS, nodes, DEBUG_OVERLAY_FOCUSABLE_WINDOWS_COLOR);
        }
        debugShowMatchingNodes();

        if (newNode == null) {
            Log.i(TAG, "no node found");
            return;
        }

        if (!tryFocusNode(newNode))
            Log.w(TAG, "focus action failed");
    }

    private Optional<DirectionalPadInput> getImeDpad() {
        DirectionalPadInput directionalPadInput = imeDirectionalPadInput;
        if (directionalPadInput != null) return Optional.of(directionalPadInput);

        String imeId = IMEInputService.getInputMethodId(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            boolean switchResult = getSoftKeyboardController().switchToInputMethod(imeId);
            if (switchResult)
                return Optional.empty(); //todo: try to figrue something out to avoid dropping a dpad input

            InputMethodManager imm = getSystemService(InputMethodManager.class);
            assert imm.getInputMethodList().stream()
                    .map(InputMethodInfo::getId)
                    .anyMatch(imeId::equals);

            boolean isEnabled = imm.getEnabledInputMethodList().stream()
                    .map(InputMethodInfo::getId)
                    .anyMatch(imeId::equals);

            if (isEnabled) {
                Log.wtf(TAG, "input method is enabled, but the switch failed");
                return Optional.empty();
            }

            // todo: ask user to enable input method
            Log.e(TAG, "input method not enabled");
        } else {
            Log.d(TAG, "input method not selected");
        }


        return Optional.empty();

    }

    public class DirectionalPadInputHandler implements DirectionalPadInput {
        private Semaphore imeDpadAssistLimiter = new Semaphore(1);

        private boolean shouldUseFakeDpad() {
            return USES_FAKE_DPAD ||
                    (USES_IME_DPAD_ASSIST && isSoftKeyboardUsable());
        }

        private void fakeDpadSelect() {
            AccessibilityNodeInfo node = findFocusedNodeIncludingKeyboard();
            if (node != null) node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }

        private void imeDpadAssist(KeyEventType type, BiConsumer<DirectionalPadInput, KeyEventType> imeDpadOperation, Runnable fakeDpadOperation) {
            getImeDpad().ifPresentOrElse(
                    input -> {
                        // only one at a time
                        boolean allowFallback = type != KeyEventType.UP && imeDpadAssistLimiter.tryAcquire();
                        if (!allowFallback) {
                            imeDpadOperation.accept(input, type);
                            return;
                        }

                        try {
                            AccessibilityNodeInfo initialFocus = findFocusedNode();
                            imeDpadOperation.accept(input, type);
                            AccessibilityNodeInfo newFocus = findFocusedNode();

                            if (!Objects.equals(initialFocus, newFocus)) return;
                            Log.v(TAG, "ime breakage? falling back to fakeDpad()");
                            fakeDpadOperation.run();
                        } finally {
                            imeDpadAssistLimiter.release();
                        }
                    },
                    () -> {
                        if (type == KeyEventType.UP) return;
                        fakeDpadOperation.run();
                    });
        }

        @Override
        public void dpadDown(KeyEventType type) {
            if (shouldUseFakeDpad()) {
                if (type == KeyEventType.UP) return;
                fakeDpad(View.FOCUS_DOWN);
            } else if (USES_IME_DPAD_ASSIST) {
                imeDpadAssist(type, DirectionalPadInput::dpadDown, () -> fakeDpad(View.FOCUS_DOWN));
            } else {
                if (type == KeyEventType.UP) return;
                performGlobalAction(GLOBAL_ACTION_DPAD_DOWN);
            }
        }

        @Override
        public void dpadUp(KeyEventType type) {
            if (shouldUseFakeDpad()) {
                if (type == KeyEventType.UP) return;
                fakeDpad(View.FOCUS_UP);
            } else if (USES_IME_DPAD_ASSIST) {
                imeDpadAssist(type, DirectionalPadInput::dpadUp, () -> fakeDpad(View.FOCUS_UP));
            } else {
                if (type == KeyEventType.UP) return;
                performGlobalAction(GLOBAL_ACTION_DPAD_UP);
            }
        }

        @Override
        public void dpadLeft(KeyEventType type) {
            if (shouldUseFakeDpad()) {
                if (type == KeyEventType.UP) return;
                fakeDpad(View.FOCUS_LEFT);
            } else if (USES_IME_DPAD_ASSIST) {
                imeDpadAssist(type, DirectionalPadInput::dpadLeft, () -> fakeDpad(View.FOCUS_LEFT));
            } else {
                if (type == KeyEventType.UP) return;
                performGlobalAction(GLOBAL_ACTION_DPAD_LEFT);
            }
        }

        @Override
        public void dpadRight(KeyEventType type) {
            if (shouldUseFakeDpad()) {
                if (type == KeyEventType.UP) return;
                fakeDpad(View.FOCUS_RIGHT);
            } else if (USES_IME_DPAD_ASSIST) {
                imeDpadAssist(type, DirectionalPadInput::dpadRight, () -> fakeDpad(View.FOCUS_RIGHT));
            } else {
                if (type == KeyEventType.UP) return;
                performGlobalAction(GLOBAL_ACTION_DPAD_RIGHT);
            }
        }

        @Override
        public void dpadSelect(KeyEventType type) {
            if (shouldUseFakeDpad()) {
                if (type == KeyEventType.UP) return;
                fakeDpadSelect();
            } else if (USES_IME_DPAD_ASSIST) {
                getImeDpad().ifPresentOrElse(
                        input -> input.dpadSelect(type),
                        () -> {
                            if (type == KeyEventType.UP) return;
                            fakeDpadSelect();
                        });
            } else {
                if (type == KeyEventType.UP) return;
                performGlobalAction(GLOBAL_ACTION_DPAD_CENTER);
            }
        }

        @Override
        public void dpadLongPress() {
            AccessibilityNodeInfo node = findFocusedNodeIncludingKeyboard();
            boolean successful = node != null && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);

            if (!successful && USES_IME_DPAD_ASSIST) {
                Log.v(TAG, "failed to long press node, falling back to ime");
                getImeDpad().ifPresent(DirectionalPadInput::dpadLongPress);
            }
        }
    }

    public class NavigationInputHandler implements NavigationInput {

        @Override
        public void navHome(KeyEventType type) {
            if (type == KeyEventType.UP) return;
            performGlobalAction(GLOBAL_ACTION_HOME);
        }

        @Override
        public void navBack(KeyEventType type) {
            if (type == KeyEventType.UP) return;
            performGlobalAction(GLOBAL_ACTION_BACK);
        }

        @Override
        public void navRecent(KeyEventType type) {
            if (type == KeyEventType.UP) return;
            performGlobalAction(GLOBAL_ACTION_RECENTS);
        }

        @Override
        public void navNotifications(KeyEventType type) {
            if (type == KeyEventType.UP) return;
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
        }

        @Override
        public void navQuickSettings() {
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
        }
    }

    public class VolumeInputHandler implements VolumeInput {

        private void sendVolumeAdjustment(int type) {
            AudioManager audioManager = getSystemService(AudioManager.class);
            audioManager.adjustVolume(type, AudioManager.FLAG_SHOW_UI);
        }

        @Override
        public void volumeUp(KeyEventType type) {
            if (type == KeyEventType.UP) return;
            sendVolumeAdjustment(AudioManager.ADJUST_RAISE);
        }

        @Override
        public void volumeDown(KeyEventType type) {
            if (type == KeyEventType.UP) return;
            sendVolumeAdjustment(AudioManager.ADJUST_LOWER);
        }

        @Override
        public void mute() {
            sendVolumeAdjustment(AudioManager.ADJUST_MUTE);
        }

        @Override
        public void unmute() {
            sendVolumeAdjustment(AudioManager.ADJUST_UNMUTE);
        }

        @Override
        public void toggleMute(KeyEventType type) {
            if (type == KeyEventType.UP) return;
            sendVolumeAdjustment(AudioManager.ADJUST_TOGGLE_MUTE);
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
    }

    public class IMEInputServiceConnection extends MakeshiftServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.v(TAG, "IMEInputService service connected");
            IMEInputService.ServiceBinder binder = (IMEInputService.ServiceBinder) service;
            imeDirectionalPadInput = binder.getDirectionalPadInput();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.v(TAG, "IMEInputService service disconnected");
            imeDirectionalPadInput = null;
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

        @RequiresApi(api = Build.VERSION_CODES.R)
        public void switchToIme() {
            if (imeDirectionalPadInput != null) Log.d(TAG, "ime dpad present");
            getImeDpad();
        }

        public void showDebugOverlay() {
            if (debugOverlay != null) return;
            debugOverlay = new DebugOverlay(AccessibilityInputService.this);
            debugOverlay.start();
        }

        public void setShowMatchingDebugNodesCondition(NodeCondition condition) {
            Log.i(TAG, "debug overlay showing nodes matching criteria: " + condition);
            debugShowMatchingNodesCondition = condition;
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

    public enum NodeCondition {
        CLICKABLE,
        FOCUSABLE,
        ENABLED,
        CHECKABLE,
        CONTEXT_CLICKABLE,
        EDITABLE,
        LONG_CLICKABLE,
        DISMISSABLE;

        private Function<AccessibilityNodeInfo, Boolean> createCriteria() {
            return switch (this) {
                case CLICKABLE -> AccessibilityNodeInfo::isClickable;
                case FOCUSABLE -> AccessibilityNodeInfo::isFocusable;
                case ENABLED -> AccessibilityNodeInfo::isEnabled;
                case CHECKABLE -> AccessibilityNodeInfo::isCheckable;
                case CONTEXT_CLICKABLE -> AccessibilityNodeInfo::isContextClickable;
                case EDITABLE -> AccessibilityNodeInfo::isEditable;
                case LONG_CLICKABLE -> AccessibilityNodeInfo::isLongClickable;
                case DISMISSABLE -> AccessibilityNodeInfo::isDismissable;
            };
        }

    }
}
