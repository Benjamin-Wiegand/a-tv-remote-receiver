package io.benwiegand.atvremote.receiver.control;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.InputMethod;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.SurroundingText;

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
import io.benwiegand.atvremote.receiver.control.input.KeyboardInput;
import io.benwiegand.atvremote.receiver.control.input.FullNavigationInput;
import io.benwiegand.atvremote.receiver.control.input.VolumeInput;
import io.benwiegand.atvremote.receiver.control.output.OverlayOutput;
import io.benwiegand.atvremote.receiver.protocol.KeyEventType;
import io.benwiegand.atvremote.receiver.protocol.PairingCallback;
import io.benwiegand.atvremote.receiver.protocol.json.SurroundingTextResponse;
import io.benwiegand.atvremote.receiver.stuff.FakeKeyDownUpHandler;
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

    /**
     * milliseconds to wait for ime service when started
     */
    private static final long IME_SERVICE_WAIT_TIMEOUT = 500;

    /**
     * milliseconds to wait for ime input to propagate before falling back to fake dpad
     */
    private static final long IME_ASSIST_WAIT_TIMEOUT = 100;

    private static final int FOCUS_HORIZONTAL_MASK = 0x50;

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
    private static final String DEBUG_OVERLAY_FAKE_FOCUS_CANDIDATES = "fakeFocusTargets";
    private static final int DEBUG_OVERLAY_FAKE_FOCUS_CANDIDATES_COLOR = 0xFFFF0000; // red
    private static final String DEBUG_OVERLAY_FAKE_FOCUS_CANDIDATE_POSITIONS = "fakeFocusPos";
    private static final int DEBUG_OVERLAY_FAKE_FOCUS_CANDIDATE_POSITIONS_COLOR = 0xFFFF0000; // red
    private static final String DEBUG_OVERLAY_FAKE_FOCUS_FIRST_CANDIDATE = "fakeFocusFirst";
    private static final int DEBUG_OVERLAY_FAKE_FOCUS_FIRST_CANDIDATE_COLOR = 0xFF0000FF; // blue

    private final AccessibilityInputHandler binder = new AccessibilityInputHandler();
    private MakeshiftBind makeshiftBind = null;

    private FakeCursor cursorInput = null;
    private final DirectionalPadInput directionalPadInput = new DirectionalPadInputHandler();
    private final DirectionalPadInput assistedImeDirectionalPadInput = new AssistedImeDirectionalPadInputHandler();
    private final FullNavigationInput fullNavigationInput = new FullNavigationInputHandler();
    private final VolumeInput volumeInput = new VolumeInputHandler();
    private final ActivityLauncherInput activityLauncherInput = new ActivityLauncherInputHandler();
    private final KeyboardInput keyboardInput = new KeyboardInputHandler();
    private final OverlayOutput overlayOutput = new OverlayOutputHandler();

    private NotificationOverlay notificationOverlay = null;

    private DebugOverlay debugOverlay = null;
    private NodeCondition debugShowMatchingNodesCondition = null;

    // ime dpad assist - for api 30, 31, and 32
    // ime keyboard - automatically switch for api >=30
    private final MakeshiftServiceConnection imeInputServiceConnection = new IMEInputServiceConnection();
    private final Object imeInputLock = new Object();
    private DirectionalPadInput imeDirectionalPadInput = null;
    private KeyboardInput imeKeyboardInput = null;

    private boolean softKeyboardOpen = false;
    private AccessibilityWindowInfo softKeyboardWindow = null;

    private final Semaphore imeDpadAssistLimiter = new Semaphore(1);

    private final FakeKeyDownUpHandler fakeSelectButtonHandler = new FakeKeyDownUpHandler(
            () -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    fakeDpadSelect();
                } else {
                    performGlobalAction(GLOBAL_ACTION_DPAD_CENTER);
                }
            },
            directionalPadInput::dpadLongPress
    );

    private final Object cacheLock = new Object();
    private AccessibilityNodeInfo cachedInputFocus = null;
    private AccessibilityNodeInfo fakeDpadCachedKeyboardFocus = null;
    private AccessibilityNodeInfo fakeDpadFakeFocus = null;


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
        // soft keyboard must be open, not null, and not the one from this app
        return softKeyboardOpen && softKeyboardWindow != null && imeKeyboardInput == null;
    }

    /**
     * @see #getOrFindFocusedNodeLocked()
     */
    private AccessibilityNodeInfo getOrFindFocusedNodeLocked() {
        AccessibilityNodeInfo node = cachedInputFocus;
        if (node != null && node.refresh() && node.isFocused())
            return node;

        // cache miss, focus search
        cachedInputFocus = findFocusedNode();
        cacheLock.notifyAll();
        return cachedInputFocus;
    }

    /**
     * gets the focused node from the cache.
     * if that isn't present/focused anymore it performs a focus search.
     * @return the focused node or null if there is none
     */
    private AccessibilityNodeInfo getOrFindFocusedNode() {
        synchronized (cacheLock) {
            return getOrFindFocusedNodeLocked();
        }
    }

    /**
     * @see #getOrFindFakeDpadFocus()
     */
    private AccessibilityNodeInfo getOrFindFakeDpadFocusLocked() {
        AccessibilityNodeInfo node = fakeDpadFakeFocus;
        if (node != null && node.refresh()) {
            Log.i(TAG, "FAKE HIT");
            return node;
        }

        boolean keyboardFocus = isSoftKeyboardUsable();
        if (!keyboardFocus) return getOrFindFocusedNodeLocked();

        node = fakeDpadCachedKeyboardFocus;
        if (node != null && node.refresh() && node.isFocused()) {
            return node;
        }

        // cache miss, focus search
        fakeDpadCachedKeyboardFocus = findFocusedNode(softKeyboardWindow);
        cacheLock.notifyAll();
        return fakeDpadCachedKeyboardFocus;
    }

    /**
     * searches for nodes in a specified direction which are either focusable or clickable by traversing
     * the view hierarchy.
     * it is not very good, but it allows navigation of some areas that were previously unreachable
     * by fake dpad, so it's better than nothing.
     * part of the reason why it sucks is that when scroll views are scrolled via accessibility nodes,
     * everything explodes. I have no mitigation for that yet, apart from just not letting scrolling happen.
     * @param fromNode the originating node
     * @param direction the direction to look in
     * @return the closest node in that direction, or null if none
     */
    private AccessibilityNodeInfo fakeFocusSearch(AccessibilityNodeInfo fromNode, int direction) {
        fromNode.refresh();
        Rect nodeBounds = new Rect(), siblingBounds = new Rect();
        AccessibilityNodeInfo sibling;
        AccessibilityNodeInfo node = fromNode;
        AccessibilityNodeInfo parent = fromNode.getParent();

        boolean forward = (direction & View.FOCUS_FORWARD) != 0;
        boolean horizontal = (direction & FOCUS_HORIZONTAL_MASK) != 0;

        record NodeRanking(int movementAxisPosition, int boundAxisPosition, AccessibilityNodeInfo node) {}

        while (parent != null) {    // stop at base of tree
            node.getBoundsInScreen(nodeBounds);

            // node movement axis: the axis being moved on (used to find distance)
            // node bound axis: the axis perpendicular to that (used to exclude nodes not in that immediate direction)
            int nodeBoundStart = horizontal ? nodeBounds.top : nodeBounds.left;
            int nodeBoundEnd = horizontal ? nodeBounds.bottom : nodeBounds.right;
            int nodeBoundAxisPosition = horizontal ? nodeBounds.centerY() : nodeBounds.centerX();
            int nodeMovementAxisPosition = horizontal ? nodeBounds.centerX() : nodeBounds.centerY();

            NodeRanking nodeRanking = new NodeRanking(nodeMovementAxisPosition, nodeBoundAxisPosition, node);

            List<NodeRanking> siblingRankings = new LinkedList<>();
            for (int i = 0; i < parent.getChildCount(); i++) {
                sibling = parent.getChild(i);
                if (sibling == null) continue;
                if (sibling.equals(node)) continue;

                sibling.getBoundsInScreen(siblingBounds);

                int siblingBoundStart = horizontal ? siblingBounds.top : siblingBounds.left;
                int siblingBoundEnd = horizontal ? siblingBounds.bottom : siblingBounds.right;
                int siblingBoundAxisPosition = horizontal ? siblingBounds.centerY() : siblingBounds.centerX();
                int siblingMovementAxisPosition = horizontal ? siblingBounds.centerX() : siblingBounds.centerY();

                // rule out nodes not in a direct line in the desired direction
                if (forward && siblingMovementAxisPosition <= nodeMovementAxisPosition) continue;
                else if (!forward && siblingMovementAxisPosition >= nodeMovementAxisPosition) continue;
                else if (siblingBoundStart > nodeBoundEnd) continue;
                else if (siblingBoundEnd < nodeBoundStart) continue;

                NodeRanking siblingRanking = new NodeRanking(siblingMovementAxisPosition, siblingBoundAxisPosition, sibling);
                siblingRankings.add(siblingRanking);

            }

            // rank nodes by distance first, then alignment with the origin
            siblingRankings.sort((s1, s2) -> {
                if (forward) {
                    if (s1.movementAxisPosition() > s2.movementAxisPosition()) return 1;
                    else if (s1.movementAxisPosition() < s2.movementAxisPosition()) return -1;
                } else {
                    if (s1.movementAxisPosition() < s2.movementAxisPosition()) return 1;
                    else if (s1.movementAxisPosition() > s2.movementAxisPosition()) return -1;
                }
                return Math.abs(s1.boundAxisPosition() - nodeRanking.boundAxisPosition()) - Math.abs(s2.boundAxisPosition() - nodeRanking.boundAxisPosition());
            });

            // debug laser beams
            debugDrawNodeRectGroup(DEBUG_OVERLAY_FAKE_FOCUS_CANDIDATES, siblingRankings.stream().map(NodeRanking::node).toArray(AccessibilityNodeInfo[]::new), DEBUG_OVERLAY_FAKE_FOCUS_CANDIDATES_COLOR);
            debugDrawRectGroup(DEBUG_OVERLAY_FAKE_FOCUS_CANDIDATE_POSITIONS, List.of(siblingRankings.stream()
                    .map(nr -> {
                        Rect rect = new Rect();
                        if (horizontal) {
                            rect.set(nr.movementAxisPosition() - 2, 0, nr.movementAxisPosition() + 2, 9999);
                        } else {
                            rect.set(0, nr.movementAxisPosition() - 2, 9999, nr.movementAxisPosition() + 2);
                        }
                        return rect;
                    })
                    .toArray(Rect[]::new)), DEBUG_OVERLAY_FAKE_FOCUS_CANDIDATE_POSITIONS_COLOR);
            if (!siblingRankings.isEmpty()) {
                NodeRanking nr = siblingRankings.get(0);
                Rect rect = new Rect();
                if (horizontal) {
                    rect.set(nr.movementAxisPosition() - 5, 0, nr.movementAxisPosition() + 5, 9999);
                } else {
                    rect.set(0, nr.movementAxisPosition() - 5, 9999, nr.movementAxisPosition() + 5);
                }
                debugDrawRect(DEBUG_OVERLAY_FAKE_FOCUS_FIRST_CANDIDATE, rect, DEBUG_OVERLAY_FAKE_FOCUS_FIRST_CANDIDATE_COLOR);
            }

            // find the first matching node
            for (NodeRanking siblingRanking : siblingRankings) {
                sibling = siblingRanking.node();
                if (sibling.isFocusable()) return sibling;
                else if (sibling.isClickable()) return sibling;
                // could be improved to also get the closest matching node within the sibling, right now it just gets the first matching one.
                // the areas where this method is useful are already tiny, and none would benefit from doing so.
                AccessibilityNodeInfo target = traverseNodeChildren(sibling, child -> child.isFocusable() || child.isClickable());
                if (target != null) return target;
            }

            // ascend
            node = parent;
            parent = parent.getParent();
        }

        return null;
    }

    /**
     * gets the focus of the fake dpad from the cache.
     * if that isn't present/focused anymore it performs a focus search.
     * @return the focused node of the fake dpad, or null if there is none
     */
    private AccessibilityNodeInfo getOrFindFakeDpadFocus() {
        synchronized (cacheLock) {
            return getOrFindFakeDpadFocusLocked();
        }
    }

    /**
     * tries to fake the behavior of a dpad using accessibility nodes.
     * finds the focused node and searches in the given direction (with a cache).
     * if there is no node in that direction, it focuses any focusable child node it can find.
     * if there is no focused node, it finds the first focusable node and focuses that.
     * if that too fails, nothing happens.
     * @param direction the direction, using constants like View.FOCUS_[direction]
     */
    private void fakeDpad(int direction) {
        // this still sucks, but less now
        Log.v(TAG, "faking dpad in direction " + direction);

        AccessibilityNodeInfo oldNode, newNode;
        synchronized (cacheLock) {
            oldNode = getOrFindFakeDpadFocusLocked();
            boolean onKeyboard = oldNode == fakeDpadCachedKeyboardFocus;
            boolean fakeFocus = fakeDpadFakeFocus != null;

            if (oldNode == null) {
                Log.v(TAG, "no focused node, finding one");
                newNode = findFirstFocusableNode();
            } else if (fakeFocus) {
                newNode = fakeFocusSearch(oldNode, direction);
                fakeDpadFakeFocus = newNode;
            } else {
                newNode = oldNode.focusSearch(direction);
                if (newNode == null) {
                    Log.i(TAG, "search returned no node, checking children");
                    newNode = findFirstFocusableChild(oldNode);
                    if (newNode == null) {
                        Log.i(TAG, "trying fake focus");
                        newNode = traverseNodeChildren(oldNode, AccessibilityNodeInfo::isClickable);
                        if (newNode != null) {
                            Log.i(TAG, "fake focus acquired");
                            Log.i(TAG, "clear old focus: " + oldNode.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS));
                            fakeDpadFakeFocus = newNode;
                        }
                    }
                }
            }

            if (newNode == null) {
                Log.i(TAG, "no node found");
            } else if (tryFocusNode(newNode)) {
                if (!onKeyboard) {
                    cachedInputFocus = newNode;
                } else {
                    fakeDpadCachedKeyboardFocus = newNode;
                }
                fakeDpadFakeFocus = null;
                cacheLock.notifyAll();
            } else {
                Log.w(TAG, "focus action failed");
            }
        }

        // debug rectangles
        if (oldNode != null) debugDrawRect(DEBUG_OVERLAY_ACTIVE_WINDOW, oldNode.getWindow(), DEBUG_OVERLAY_ACTIVE_WINDOW_COLOR);
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
        debugDrawRect(DEBUG_OVERLAY_OLD_FOCUS, oldNode, DEBUG_OVERLAY_OLD_FOCUS_COLOR);
        debugDrawRect(DEBUG_OVERLAY_NEW_FOCUS, newNode, DEBUG_OVERLAY_NEW_FOCUS_COLOR);
    }

    private void fakeDpadSelect() {
        AccessibilityNodeInfo node = getOrFindFakeDpadFocus();
        if (node != null) node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private boolean fakeDpadLongPress() {
        AccessibilityNodeInfo node = getOrFindFakeDpadFocus();
        return node != null && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
    }

    private boolean switchToIme(boolean promptIfNeeded) {
        String imeId = IMEInputService.getInputMethodId(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            boolean switchResult = getSoftKeyboardController().switchToInputMethod(imeId);
            if (switchResult) return true;

            if (!IMEInputService.isEnabled(this)) {
                // todo: ask user to enable input method
                Log.e(TAG, "input method not enabled");
                return false;
            }

            Log.wtf(TAG, "input method is enabled, but the switch failed");
        } else {
            Log.d(TAG, "input method not selected");
        }

        if (!promptIfNeeded) return false;
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        imm.showInputMethodPicker();
        return false;
    }

    private Optional<DirectionalPadInput> getImeDpad() {
        synchronized (imeInputLock) {
            if (imeDirectionalPadInput != null) return Optional.of(imeDirectionalPadInput);
            if (!switchToIme(false)) return Optional.empty();
            try {
                imeInputLock.wait(IME_SERVICE_WAIT_TIMEOUT);
            } catch (InterruptedException ignored) {}

            return Optional.ofNullable(imeDirectionalPadInput);
        }
    }

    private Optional<KeyboardInput> getImeKeyboard() {
        synchronized (imeInputLock) {
            if (imeKeyboardInput != null) return Optional.of(imeKeyboardInput);
            if (!switchToIme(true)) return Optional.empty();
            try {
                imeInputLock.wait(IME_SERVICE_WAIT_TIMEOUT);
            } catch (InterruptedException ignored) {}

            return Optional.ofNullable(imeKeyboardInput);
        }
    }

    private boolean tryImeDpad(KeyEventType type, BiConsumer<DirectionalPadInput, KeyEventType> imeDpadOperation) {
        return getImeDpad().map(
                        input -> {
                            // only one at a time
                            boolean allowFallback = type != KeyEventType.UP && imeDpadAssistLimiter.tryAcquire();
                            if (!allowFallback) {
                                imeDpadOperation.accept(input, type);
                                return true;
                            }

                            try {
                                AccessibilityNodeInfo initialFocus = getOrFindFocusedNode();
                                imeDpadOperation.accept(input, type);
                                synchronized (cacheLock) {
                                    AccessibilityNodeInfo newFocus = getOrFindFocusedNodeLocked();
                                    if (!Objects.equals(initialFocus, newFocus)) return true;
                                    // the new caching system is so fast it gets here before the key event
                                    // has had time to propagate. I would really like to find a better way
                                    // to do this fallback.
                                    cacheLock.wait(IME_ASSIST_WAIT_TIMEOUT);
                                    newFocus = getOrFindFocusedNodeLocked();
                                    if (!Objects.equals(initialFocus, newFocus)) return true;
                                }

                                Log.e(TAG, "ime breakage? falling back");
                                return false;
                            } catch (InterruptedException ignored) {
                                return true;
                            } finally {
                                imeDpadAssistLimiter.release();
                            }
                        })
                .orElse(false);
    }

    public class DirectionalPadInputHandler implements DirectionalPadInput {


        @Override
        public void dpadDown(KeyEventType type) {
            if (type == KeyEventType.UP) return;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                fakeDpad(View.FOCUS_DOWN);
            } else {
                performGlobalAction(GLOBAL_ACTION_DPAD_DOWN);
            }
        }

        @Override
        public void dpadUp(KeyEventType type) {
            if (type == KeyEventType.UP) return;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                fakeDpad(View.FOCUS_UP);
            } else {
                performGlobalAction(GLOBAL_ACTION_DPAD_UP);
            }
        }

        @Override
        public void dpadLeft(KeyEventType type) {
            if (type == KeyEventType.UP) return;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                fakeDpad(View.FOCUS_LEFT);
            } else {
                performGlobalAction(GLOBAL_ACTION_DPAD_LEFT);
            }
        }

        @Override
        public void dpadRight(KeyEventType type) {
            if (type == KeyEventType.UP) return;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                fakeDpad(View.FOCUS_RIGHT);
            } else {
                performGlobalAction(GLOBAL_ACTION_DPAD_RIGHT);
            }
        }

        @Override
        public void dpadSelect(KeyEventType type) {
            fakeSelectButtonHandler.onKeyEvent(type);
        }

        @Override
        public void dpadLongPress() {
            AccessibilityNodeInfo node = getOrFindFakeDpadFocus();
            if (node != null) node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
        }
    }

    public class AssistedImeDirectionalPadInputHandler implements DirectionalPadInput {

        private boolean shouldUseImeDpad() {
            return USES_IME_DPAD_ASSIST && !isSoftKeyboardUsable();
        }

        @Override
        public void dpadDown(KeyEventType type) {
            if (shouldUseImeDpad() && tryImeDpad(type, DirectionalPadInput::dpadDown)) return;
            directionalPadInput.dpadDown(type);
        }

        @Override
        public void dpadUp(KeyEventType type) {
            if (shouldUseImeDpad() && tryImeDpad(type, DirectionalPadInput::dpadUp)) return;
            directionalPadInput.dpadUp(type);
        }

        @Override
        public void dpadLeft(KeyEventType type) {
            if (shouldUseImeDpad() && tryImeDpad(type, DirectionalPadInput::dpadLeft)) return;
            directionalPadInput.dpadLeft(type);
        }

        @Override
        public void dpadRight(KeyEventType type) {
            if (shouldUseImeDpad() && tryImeDpad(type, DirectionalPadInput::dpadRight)) return;
            directionalPadInput.dpadRight(type);
        }

        @Override
        public void dpadSelect(KeyEventType type) {
            if (shouldUseImeDpad() && !fakeSelectButtonHandler.isHandlingKeyPress() && (fakeDpadFakeFocus == null || !fakeDpadFakeFocus.refresh())) {
                boolean sent = getImeDpad().map(input -> {
                    input.dpadSelect(type);
                    return true;
                }).orElse(false);
                if (sent) return;
            }

            fakeSelectButtonHandler.onKeyEvent(type);
        }

        @Override
        public void dpadLongPress() {
            // the ime is slower to long press, so prefer accessibility implementation if possible
            if (fakeDpadLongPress()) return;
            Log.v(TAG, "failed to long press node, falling back to ime");
            getImeDpad().ifPresent(DirectionalPadInput::dpadLongPress);
        }
    }

    public class FullNavigationInputHandler implements FullNavigationInput {
        private final FakeKeyDownUpHandler fakeHomeButtonHandler = new FakeKeyDownUpHandler(
                () -> performGlobalAction(GLOBAL_ACTION_HOME),
                () -> navNotifications(KeyEventType.CLICK) /* todo */);
        private final FakeKeyDownUpHandler fakeNotificationsButtonHandler = new FakeKeyDownUpHandler(
                () -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS),
                true);

        @Override
        public void navHome(KeyEventType type) {
            fakeHomeButtonHandler.onKeyEvent(type);
        }

        @Override
        public void navBack(KeyEventType type) {
            if (type == KeyEventType.DOWN) return;
            performGlobalAction(GLOBAL_ACTION_BACK);
        }

        @Override
        public void navRecent(KeyEventType type) {
            if (type == KeyEventType.DOWN) return;
            performGlobalAction(GLOBAL_ACTION_RECENTS);
        }

        @Override
        public void navNotifications(KeyEventType type) {
            fakeNotificationsButtonHandler.onKeyEvent(type);
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
            if (type == KeyEventType.DOWN) return;
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

    public class KeyboardInputHandler implements KeyboardInput {
        private Optional<InputMethod> getOptionalInputMethod() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Optional.empty();
            return Optional.ofNullable(getInputMethod());
        }

        private Optional<InputMethod.AccessibilityInputConnection> getOptionalInputConnection() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Optional.empty();
            return getOptionalInputMethod()
                    .map(InputMethod::getCurrentInputConnection);
        }

        private <T> Optional<T> tryOnImeKeyboard(Function<KeyboardInput, T> f) {
            return getImeKeyboard().map(f);
        }

        @Override
        public void setSoftKeyboardEnabled(boolean enabled) {

        }

        @Override
        public boolean commitText(String input, int newCursorPosition) {
            return getOptionalInputConnection().map(inputConnection -> {
                inputConnection.commitText(input, newCursorPosition, null);
                return true;
            })
                    .or(() -> tryOnImeKeyboard(i -> i.commitText(input, newCursorPosition)))
                    .orElse(false);
        }

        @Override
        public SurroundingTextResponse getSurroundingText(int beforeLength, int afterLength) {
            return getOptionalInputConnection().map(inputConnection -> {
                SurroundingText surroundingText = inputConnection.getSurroundingText(beforeLength, afterLength, 0);
                if (surroundingText == null) return null;
                return new SurroundingTextResponse(surroundingText);
            })
                    .or(() -> tryOnImeKeyboard(i -> i.getSurroundingText(beforeLength, afterLength)))
                    .orElse(null);
        }

        @Override
        public boolean setSelection(int start, int end) {
            return getOptionalInputConnection().map(inputConnection -> {
                inputConnection.setSelection(start, end);
                return true;
            })
                    .or(() -> tryOnImeKeyboard(i -> i.setSelection(start, end)))
                    .orElse(false);
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            return getOptionalInputConnection().map(inputConnection -> {
                inputConnection.deleteSurroundingText(beforeLength, afterLength);
                return true;
            })
                    .or(() -> tryOnImeKeyboard(i -> i.deleteSurroundingText(beforeLength, afterLength)))
                    .orElse(false);
        }

        @Override
        public boolean performContextMenuAction(int id) {
            return getOptionalInputConnection().map(inputConnection -> {
                inputConnection.performContextMenuAction(id);
                return true;
            })
                    .or(() -> tryOnImeKeyboard(i -> i.performContextMenuAction(id)))
                    .orElse(false);
        }

        @Override
        public boolean performEditorAction(int id) {
            return getOptionalInputConnection().map(inputConnection -> {
                inputConnection.performEditorAction(id);
                return true;
            })
                    .or(() -> tryOnImeKeyboard(i -> i.performEditorAction(id)))
                    .orElse(false);
        }

        @Override
        public boolean sendKeyEvent(int keyCode, KeyEventType type) {
            return getOptionalInputConnection().map(inputConnection -> {
                if (type == KeyEventType.CLICK || type == KeyEventType.DOWN)
                    inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));

                if (type == KeyEventType.CLICK || type == KeyEventType.UP)
                    inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));

                return true;
            })
                    .or(() -> tryOnImeKeyboard(i -> i.sendKeyEvent(keyCode, type)))
                    .orElse(false);
        }

        @Override
        public boolean performDefaultEditorAction() {
            return getOptionalInputMethod().map(inputMethod -> {
                InputMethod.AccessibilityInputConnection inputConnection = inputMethod.getCurrentInputConnection();
                EditorInfo editorInfo = inputMethod.getCurrentInputEditorInfo();
                if (inputConnection == null || editorInfo == null) return false;

                int action = editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
                if (action == EditorInfo.IME_ACTION_NONE) return false;
                if (action == EditorInfo.IME_ACTION_UNSPECIFIED) {
                    Log.d(TAG, "editor action unspecified");
                    return false;
                }

                inputConnection.performEditorAction(action);
                return true;
            })
                    .or(() -> tryOnImeKeyboard(KeyboardInput::performDefaultEditorAction))
                    .orElse(false);
        }
    }

    // todo: scroll

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
            synchronized (imeInputLock) {
                imeDirectionalPadInput = binder.getDirectionalPadInput();
                imeKeyboardInput = binder.getKeyboardInput();

                imeInputLock.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.v(TAG, "IMEInputService service disconnected");
            synchronized (imeInputLock) {
                imeDirectionalPadInput = null;
                imeKeyboardInput = null;
            }
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

        public DirectionalPadInput getAssistedImeDirectionalPadInput() {
            return assistedImeDirectionalPadInput;
        }

        public FullNavigationInput getFullNavigationInput() {
            return fullNavigationInput;
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

        public KeyboardInput getKeyboardInput() {
            return keyboardInput;
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
