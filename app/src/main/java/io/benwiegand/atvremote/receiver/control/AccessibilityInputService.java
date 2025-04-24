package io.benwiegand.atvremote.receiver.control;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.control.cursor.AccessibilityGestureCursor;
import io.benwiegand.atvremote.receiver.control.cursor.CursorController;
import io.benwiegand.atvremote.receiver.protocol.PairingCallback;
import io.benwiegand.atvremote.receiver.ui.NotificationOverlay;
import io.benwiegand.atvremote.receiver.ui.PairingDialog;

public class AccessibilityInputService extends AccessibilityService {
    private static final String TAG = AccessibilityInputService.class.getSimpleName();

    public static final String INTENT_ACCESSIBILITY_INPUT_BINDER_REQUEST = "io.benwiegand.atvremote.receiver.control.accessibilityinput.GIVE_ME_BINDER";
    public static final String INTENT_ACCESSIBILITY_INPUT_BINDER_INSTANCE = "io.benwiegand.atvremote.receiver.control.accessibilityinput.BINDER_INSTANCE";
    public static final String EXTRA_BINDER_INSTANCE = "binder";

    private final AccessibilityInputHandler binder = new AccessibilityInputHandler();
    private final BroadcastReceiver receiver = new Receiver();

    private CursorController cursorController = null;
    private NotificationOverlay notificationOverlay = null;


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

        cursorController = new AccessibilityGestureCursor(this);
        notificationOverlay = new NotificationOverlay(this);
        notificationOverlay.start();

        broadcastBinder();
    }

    public NotificationOverlay getNotificationOverlay() {
        return notificationOverlay;
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "onInterrupt()");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "onUnbind()");
        if (cursorController != null) cursorController.destroy();
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        Log.d(TAG, "onAccessibilityEvent()");
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

    private Display getDisplayCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return getDisplay();
        } else {
            DisplayManager dm = getSystemService(DisplayManager.class);
            return dm.getDisplay(Display.DEFAULT_DISPLAY);
        }
    }

    private Point getResolution() {
        Point resolution = new Point();
        getDisplayCompat().getRealSize(resolution);
        return resolution;
    }

    private AccessibilityNodeInfo getFocusedNode() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Log.w(TAG, "window root is null!!!");
            return null;
        }
        AccessibilityNodeInfo node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (node == null) node = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);

        return node;
    }

    private void fakeDpad(int direction) {
        // todo: this shit sucks

        AccessibilityNodeInfo node = getFocusedNode();
        if (node == null) return;//todo: get first focusable

        Log.i(TAG, "faking dpad in direction " + direction);
        AccessibilityNodeInfo newNode;
//        if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT)
//            newNode = traverseHorizontal(node, direction == View.FOCUS_LEFT);
//        else
            newNode = node.focusSearch(direction);      // todo: traverse all children fallback
        if (newNode == null) return;

        Log.i(TAG, "found node");
        newNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS);

    }

    private static final int TRAVERSE_X_DISTANCE_THRESHOLD = 5;
    private AccessibilityNodeInfo traverseHorizontal(AccessibilityNodeInfo node, boolean left) {
        AccessibilityNodeInfo parent = node.getParent();
        if (parent == null) return null;

        Rect nodeBounds = new Rect();
        node.getBoundsInScreen(nodeBounds);

        // rank nodes by distance on x/y
        record NodeRanking(AccessibilityNodeInfo node, int distanceX, int distanceY) {}
        List<NodeRanking> rankings = new LinkedList<>();
        Rect siblingBounds = new Rect();
        for (int i = 0; i < parent.getChildCount(); i++) {
            AccessibilityNodeInfo sibling = parent.getChild(i);
            if (sibling == node) continue;
            sibling.getBoundsInScreen(siblingBounds);

            int distanceX = left ? nodeBounds.left - siblingBounds.right : (siblingBounds.left - nodeBounds.right);
            Log.i(TAG, "dx:" + distanceX);
            if (distanceX < 0) continue;

            int distanceY = Math.abs(nodeBounds.centerY() - siblingBounds.centerY());

            rankings.add(new NodeRanking(sibling, distanceX, distanceY));
        }

        Log.i(TAG, "Found " + rankings.size() + " nodes in direction");

        // search parent if no nodes in direction
        if (rankings.isEmpty()) return traverseHorizontal(parent, left);

        // rank nodes by x axis first
        rankings.sort(Comparator.comparingInt(NodeRanking::distanceX));

        // eliminate nodes outside of a certain threshold from the first node
        NodeRanking closestX = rankings.remove(0);
        List<NodeRanking> newRankings = new LinkedList<>(Collections.singletonList(closestX));
        while (!rankings.isEmpty()) {
            NodeRanking ranking = rankings.remove(0);
            if (closestX.distanceX() >= ranking.distanceX() - TRAVERSE_X_DISTANCE_THRESHOLD) break;
            newRankings.add(ranking);
        }

        Log.i(TAG, "trimmed to " + newRankings.size() + " candidates");

        rankings.clear();
        rankings = newRankings;

        // sort by distance y to get the closest node vertically
        rankings.sort(Comparator.comparingInt(NodeRanking::distanceY));
        assert !rankings.isEmpty();

        return rankings.remove(0).node();
    }

    public class AccessibilityInputHandler extends Binder implements InputHandler {

        public void showPairingDialog() {

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
            notificationOverlay.displayNotification("Test notification", "this is a test", R.drawable.ic_launcher_foreground);

        }

        @Override
        public int getScreenWidth() {
            return getResolution().x;
        }

        @Override
        public int getScreenHeight() {
            return getResolution().y;
        }

        @Override
        public void dpadDown() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                performGlobalAction(GLOBAL_ACTION_DPAD_DOWN);
            } else {
                fakeDpad(View.FOCUS_DOWN);

                // todo: use this if installed as system
//                Instrumentation i = new Instrumentation();
//                i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);

                // todo
//                InputMethod.AccessibilityInputConnection c;
//                c.sendKeyEvent();

            }
        }

        @Override
        public void dpadUp() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                performGlobalAction(GLOBAL_ACTION_DPAD_UP);
            } else {
                fakeDpad(View.FOCUS_UP);
            }
        }

        @Override
        public void dpadLeft() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                performGlobalAction(GLOBAL_ACTION_DPAD_LEFT);
            } else {
                fakeDpad(View.FOCUS_BACKWARD);
            }
        }

        @Override
        public void dpadRight() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                performGlobalAction(GLOBAL_ACTION_DPAD_RIGHT);
            } else {
                fakeDpad(View.FOCUS_FORWARD);
            }
        }

        @Override
        public void dpadSelect() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                performGlobalAction(GLOBAL_ACTION_DPAD_CENTER);
            } else {
                AccessibilityNodeInfo node = getFocusedNode();
                if (node != null) node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }

        @Override
        public void dpadLongPress() {
            AccessibilityNodeInfo node = getFocusedNode();
            if (node != null) node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
        }

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

        // todo: power (sleep and menu)

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
        public void mute() {
            AudioManager audioManager = getSystemService(AudioManager.class);
            audioManager.adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
        }

        @Override
        public void pause() {
            // TODO
        }

        @Override
        public void nextTrack() {

            // TODO
        }

        @Override
        public void prevTrack() {

            // TODO
        }

        @Override
        public void skipBackward() {

            // TODO
        }

        @Override
        public void skipForward() {

            // TODO
        }

        @Override
        public boolean softKeyboardEnabled() {
            // TODO
            return false;
        }

        @Override
        public boolean softKeyboardVisible() {
            // TODO
            return false;
        }

        @Override
        public void showSoftKeyboard() {
            // TODO

        }

        @Override
        public void hideSoftKeyboard() {
            // TODO

        }

        @Override
        public void setSoftKeyboardEnabled(boolean enabled) {
            // TODO

        }

        @Override
        public void keyboardInput(String input) {
            // TODO: this is probably wrong
            AccessibilityNodeInfo node = getFocusedNode();
            if (node == null) return;
            node.setText(node.getText() + input);
        }

        @Override
        public boolean cursorSupported() {
            return cursorController != null;
        }

        @Override
        public void showCursor() {
            if (cursorController == null) return;
            cursorController.showCursor();
        }

        @Override
        public void hideCursor() {
            if (cursorController == null) return;
            cursorController.hideCursor();
        }

        @Override
        public void cursorMove(int x, int y) {
            if (cursorController == null) return;
            cursorController.cursorMove(x, y);
        }

        @Override
        public void cursorDown() {
            if (cursorController == null) return;
            cursorController.cursorDown();
        }

        @Override
        public void cursorUp() {
            if (cursorController == null) return;
            cursorController.cursorUp();
        }

        @Override
        public void cursorContext() {
            // TODO

        }

        @Override
        public void scrollVertical(double trajectory, boolean glide) {
            // TODO

        }

        @Override
        public void scrollHorizontal(double trajectory, boolean glide) {
            // TODO

        }

        public AccessibilityInputService getService() {
            return AccessibilityInputService.this;
        }
    }
}
