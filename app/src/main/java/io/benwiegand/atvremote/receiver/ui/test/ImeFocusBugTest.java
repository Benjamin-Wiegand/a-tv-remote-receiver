package io.benwiegand.atvremote.receiver.ui.test;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidedAction;

import org.jspecify.annotations.NonNull;

import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.benwiegand.atvremote.receiver.async.PendingSec;
import io.benwiegand.atvremote.receiver.async.Sec;
import io.benwiegand.atvremote.receiver.async.SecAdapter;
import io.benwiegand.atvremote.receiver.control.input.DirectionalPadInput;
import io.benwiegand.atvremote.receiver.protocol.KeyEventType;

/**
 * <p>
 *     the ime dpad has a bug on some devices where it doesn't transfer focus correctly in situations
 *     like switching fragments.
 * </p>
 * <p>
 *     passing this test does not necessarily mean the ime dpad doesn't encounter the bug.
 * </p>
 * <p>
 *     this basic version of the test *usually* fails on devices with android 12 and below.
 *     it seems to also correlate with the ability to navigate settings fragments, but I'm unsure.
 *     more nuanced cases can be tested by extending this class and overriding createTestFragment()
 *     and/or createInitialFragment()
 * </p>
 */
public class ImeFocusBugTest extends ViewNavigationCompatibilityTest {
    private static final String TAG = ImeFocusBugTest.class.getSimpleName();

    protected static final int BUTTON_COUNT = 5;
    private static final int CLICK_COUNT = 3;

    private static final long FOCUS_BUG_TEST_TIMEOUT = 700;

    // the accessibility assisted ime dpad needs time to flush its state before it can perform accurately for this test
    // it's an unfortunate limitation of the workaround used
    private static final long TEST_DELAY_AFTER_FRAGMENT_SWITCH = 700;

    private final FragmentManager fragmentManager;
    private final DirectionalPadInput directionalPadInput;

    private final Fragment initialFragment = createInitialFragment();
    private final Fragment testFragment = createTestFragment();
    private final ViewList viewList = (ViewList) testFragment;

    private int fragmentViewId;

    public ImeFocusBugTest(ViewGroup root, ViewGroup.LayoutParams rootLayoutParams, Callback callback, FragmentManager fragmentManager, DirectionalPadInput directionalPadInput) {
        super(root, rootLayoutParams, callback);
        this.fragmentManager = fragmentManager;
        this.directionalPadInput = directionalPadInput;
    }

    public interface ViewList {
        View[] getListViews();
    }

    public static class TestFragment extends Fragment implements ViewList {
        private final View[] views = new View[BUTTON_COUNT];
        private boolean init = false;

        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            LinearLayout linearLayout = new LinearLayout(inflater.getContext());
            linearLayout.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < views.length; i++) {
                Button button = new Button(inflater.getContext());
                button.setBackgroundTintList(COLOR_STATE_LIST);
                views[i] = button;
                linearLayout.addView(button);
            }

            init = true;

            return linearLayout;
        }

        @Override
        public View[] getListViews() {
            if (!init) return null;
            return views;
        }
    }

    public static class InitialFragment extends GuidedStepSupportFragment {
        @Override
        public void onCreateActions(@NonNull List<GuidedAction> actions, @org.jspecify.annotations.Nullable Bundle savedInstanceState) {
            super.onCreateActions(actions, savedInstanceState);
            actions.add(new GuidedAction.Builder(getContext())
                    .title("")
                    .build());
            actions.add(new GuidedAction.Builder(getContext())
                    .title("")
                    .build());
        }

    }

    protected Fragment createInitialFragment() {
        return new InitialFragment();
    }

    protected TestFragment createTestFragment() {
        return new TestFragment();
    }

    @Override
    protected void setupViews() {
        fragmentViewId = new Random().nextInt(1000) + 1000;

        FrameLayout fragmentPlaceholder = new FrameLayout(getContext());
        fragmentPlaceholder.setId(fragmentViewId);
        addToRoot(fragmentPlaceholder);
    }

    @Override
    protected boolean initFocus() {
        return true;
    }

    @Override
    protected void beginTest() {

        List<PendingSec<Boolean>> tests = List.of(
                switchFragment(initialFragment),

                switchFragment(testFragment),
                PendingSec.createDelay(TEST_DELAY_AFTER_FRAGMENT_SWITCH).mapSec(v -> true),
                testForFocusBug()
        );
        handler.post(() -> {

            // run all the tests in series
            Sec<Boolean> sec = tests.get(0).start();
            for (int i = 1; i < tests.size(); i++) {
                PendingSec<Boolean> next = tests.get(i);
                sec = sec.flatMap(r -> {
                    if (!r) return Sec.premeditated(false);
                    return next.start();
                });
            }

            sec.doOnResult(this::setResult)
                    .doOnError(t -> {
                        Log.e(TAG, "test failed: exception", t);
                        setResult(false);
                    })
                    .callMeWhenDone();
        });

    }

    private PendingSec<Boolean> switchFragment(Fragment fragment) {
        return SecAdapter.createSimple(handler, () -> {
            fragmentManager.beginTransaction()
                    .replace(fragmentViewId, fragment)
                    .commitNow();
            return true;
        });
    }

    private PendingSec<Boolean> testForFocusBug() {
        return SecAdapter.create(handler, secAdapter -> {
            try {
                AtomicBoolean active = new AtomicBoolean(true);
                LinkedList<Integer> focusOrder = new LinkedList<>();

                View[] views = viewList.getListViews();
                assert views != null;
                for (int i = 0; i < views.length; i++) {
                    int finalIndex = i;
                    views[i].setOnFocusChangeListener((v, f) -> {
                        if (!active.get()) return;
                        if (f) {
                            Log.d(TAG, "view " + finalIndex + " gained focus");
                            focusOrder.add(finalIndex);
                        } else {
                            Log.d(TAG, "view " + finalIndex + " lost focus");
                        }
                    });
                }

                new Thread(() -> {
                    for (int i = 0; i < CLICK_COUNT; i++) {
                        directionalPadInput.dpadDown(KeyEventType.CLICK);
                    }
                }).start();

                boolean started = handler.postDelayed(() -> {
                    active.set(false);

                    if (focusOrder.isEmpty()) {
                        Log.e(TAG, "FAIL: focus never changed");
                        secAdapter.provideResult(false);
                        return;
                    }

                    Log.i(TAG, "PASS condition met: a view within the fragment gained focus");

                    boolean pass = true;
                    int prevFocus = focusOrder.pop(), nextFocus;
                    Log.d(TAG, "initial focus gained for view index: " + prevFocus);
                    while (!focusOrder.isEmpty()) {
                        nextFocus = focusOrder.pop();
                        if (nextFocus != prevFocus + 1) {
                            Log.e(TAG, "FAIL: focus unexpectedly jumped from view at index " + prevFocus + " to " + nextFocus);
                            pass = false;
                        }
                        prevFocus = nextFocus;
                    }

                    secAdapter.provideResult(pass);
                }, FOCUS_BUG_TEST_TIMEOUT);
                if (!started) throw new RejectedExecutionException("handler is dead");

            } catch (RuntimeException e) {
                secAdapter.throwError(e);
            }
        });
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }
}
