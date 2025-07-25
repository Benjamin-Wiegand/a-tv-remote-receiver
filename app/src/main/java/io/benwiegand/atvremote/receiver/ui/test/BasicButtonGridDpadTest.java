package io.benwiegand.atvremote.receiver.ui.test;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import io.benwiegand.atvremote.receiver.async.PendingSec;
import io.benwiegand.atvremote.receiver.async.Sec;
import io.benwiegand.atvremote.receiver.async.SecAdapter;
import io.benwiegand.atvremote.receiver.control.input.DirectionalPadInput;
import io.benwiegand.atvremote.receiver.protocol.KeyEventType;

/**
 * tests the most basic of dpad functionality (i.e: does it even work?)
 */
public class BasicButtonGridDpadTest extends ViewNavigationCompatibilityTest {
    private static final String TAG = BasicButtonGridDpadTest.class.getSimpleName();

    /**
     * minimum milliseconds to wait before considering a navigation result valid.
     * this is more important for inputs like accessibility assisted dpad, as it waits (currently
     * 500 milliseconds) before triggering a fake dpad fallback. such a mechanism may cause a double
     * input if it breaks.
     */
    private static final long NAVIGATION_VALID_AFTER = 700;

    /**
     * workaround for fake dpad cache being slightly behind when rapidly going through the test,
     * causing it to focus the wrong view sometimes. it only seems to happen on some devices.
     * this is the number of milliseconds to wait after resetting the focus and before performing inputs
     */
    private static final long FOCUS_RESET_DELAY = 300;

    private ViewGrid viewGrid = null;
    private final DirectionalPadInput directionalPadInput;

    public BasicButtonGridDpadTest(ViewGroup root, ViewGroup.LayoutParams rootLayoutParams, Callback callback, DirectionalPadInput directionalPadInput) {
        super(root, rootLayoutParams, callback);
        this.directionalPadInput = directionalPadInput;
    }

    protected int gridHeight() {
        return 7;
    }

    protected int gridWidth() {
        return 7;
    }

    protected ViewGrid.Entry getStartingFocusView() {
        return viewGrid.getCenter();
    }

    protected ViewGrid getViewGrid() {
        return viewGrid;
    }

    @Override
    protected void setupViews() {
        int rows = gridHeight(), cols = gridWidth();

        LinearLayout gridRoot = new LinearLayout(getContext());
        gridRoot.setOrientation(LinearLayout.VERTICAL);

        View[][] gridArray = new View[rows][cols];

        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);

            for (int c = 0; c < cols; c++) {
                Button button = new Button(getContext());
                button.setBackgroundTintList(COLOR_STATE_LIST);

                gridArray[r][c] = button;
                row.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }

            gridRoot.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }

        addToRoot(gridRoot);
        viewGrid = new ViewGrid(gridRoot, gridArray);
    }

    @Override
    protected boolean initFocus() {
        View view = getStartingFocusView().view();
        if (root.isInTouchMode()) {
            Log.d(TAG, "touch mode active!");
            directionalPadInput.dpadDown(KeyEventType.CLICK);
        }
        return view.requestFocus() && view.hasFocus();
    }

    protected List<PendingSec<Boolean>> getTestList() {
        return List.of(
                testDirection(viewGrid.getCenter(), DpadTestUtil.DpadDirection.DPAD_RIGHT, 2, true),
                testDirection(viewGrid.getCenter(), DpadTestUtil.DpadDirection.DPAD_LEFT, 1, true),
                testDirection(viewGrid.getCenter(), DpadTestUtil.DpadDirection.DPAD_UP, 2, false),
                testDirection(viewGrid.getCenter(), DpadTestUtil.DpadDirection.DPAD_DOWN, 1, false),
                testClick(viewGrid.getCenter(), false, true),
                testClick(viewGrid.getCenter(), true, true),
                testClick(viewGrid.getCenter(), false, false),
                testClick(viewGrid.getCenter(), true, false)
        );
    }

    @Override
    protected void beginTest() {
        List<PendingSec<Boolean>> tests = getTestList();

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

    private void resetFocus(ViewGrid.Entry focusTarget) {
        View view = focusTarget.view();
        if (!view.hasFocus() && !view.requestFocus()) {
            Log.wtf(TAG,"focus initialization failed!");
            throw new RuntimeException("failed to focus starting view. was the screen touched?");
        }
    }

    protected PendingSec<Boolean> testDirection(ViewGrid.Entry startViewEntry, DpadTestUtil.DpadDirection direction, int amount, boolean withDownUp) {
        return SecAdapter.create(handler, secAdapter -> {
            try {
                AtomicBoolean failCondition = new AtomicBoolean(false);
                AtomicBoolean passCondition = new AtomicBoolean(false);
                AtomicBoolean active = new AtomicBoolean(true);
                View[] targetViews = new View[amount];
                boolean[] targetViewFocusStates = new boolean[amount];

                Log.v(TAG, "testing " + direction + " x" + amount + " " + (withDownUp ? "(full DOWN/UP)" : "(CLICK)"));

                resetFocus(startViewEntry);

                // determine path to be navigated
                for (int i = 0; i < amount; i++) {
                    targetViews[i] = viewGrid.getOffsetFromEntry(startViewEntry, direction, i + 1).view();
                }

                // fail by default
                viewGrid.forEach(entry -> {
                    entry.view().setOnClickListener(v -> {
                        if (!active.get()) return;
                        Log.e(TAG, "FAIL: view at col = " + entry.col() + ", row = " + entry.row() + " clicked, but it shouldn't have been");
                        failCondition.set(true);
                    });
                    entry.view().setOnFocusChangeListener((v, f) -> {
                        if (!active.get()) return;
                        if (f) {
                            Log.e(TAG, "FAIL: view at col = " + entry.col() + ", row = " + entry.row() + " gained focus, but it shouldn't have");
                            failCondition.set(true);
                        }
                    });
                });

                // validate navigated path
                for (int i = 0; i < targetViews.length; i++) {
                    int finalIndex = i;
                    targetViews[i].setOnFocusChangeListener((v, f) -> {
                        if (!active.get()) return;
                        if (f) {
                            // ensure views are all focused in order
                            targetViewFocusStates[finalIndex] = true;
                            for (int j = 0; j < finalIndex; j++) {
                                if (targetViewFocusStates[j]) continue;
                                Log.e(TAG, "FAIL: view at index " + finalIndex + " in sequence focused before view at index " + j);
                                failCondition.set(true);
                            }

                            // require the last view gain focus
                            if (finalIndex + 1 == targetViews.length) {
                                Log.d(TAG, "PASS condition met: final target view focused");
                                passCondition.set(true);
                            }
                        } else {
                            // ensure the last view doesn't lose focus
                            if (finalIndex + 1 == targetViews.length) {
                                Log.e(TAG, "FAIL: the last target view in sequence lost focus, but it shouldn't have");
                                failCondition.set(true);
                            }
                        }
                    });
                }

                // inputs
                Consumer<KeyEventType> dpadMethod = direction.getMethodCall(directionalPadInput);
                new Thread(() -> {
                    try {
                        Thread.sleep(FOCUS_RESET_DELAY);
                    } catch (InterruptedException e) {
                        Log.d(TAG, "interrupted", e);
                        return;
                    }

                    for (int i = 0; i < amount; i++) {
                        dpadMethod.accept(withDownUp ? KeyEventType.DOWN : KeyEventType.CLICK);
                    }
                    if (withDownUp) dpadMethod.accept(KeyEventType.UP);
                }).start();

                // wait until everything has settled
                boolean started = handler.postDelayed(() -> {
                    active.set(false);
                    boolean result = passCondition.get() && !failCondition.get();
                    secAdapter.provideResult(result);
                }, NAVIGATION_VALID_AFTER + FOCUS_RESET_DELAY);
                if (!started) throw new RejectedExecutionException("handler is dead");

            } catch (RuntimeException e) {
                secAdapter.throwError(e);
            }
        });

    }

    protected PendingSec<Boolean> testClick(ViewGrid.Entry targetViewEntry, boolean longPress, boolean withDownUp) {
        return SecAdapter.create(handler, secAdapter -> {
            try {
                AtomicBoolean failCondition = new AtomicBoolean(false);
                AtomicBoolean passCondition = new AtomicBoolean(false);
                AtomicBoolean active = new AtomicBoolean(true);

                View targetView = targetViewEntry.view();

                Log.v(TAG, "testing dpad select " + (withDownUp ? "(full DOWN/UP)" : "(CLICK)"));

                resetFocus(targetViewEntry);

                // fail by default
                viewGrid.forEach(entry -> {
                    entry.view().setOnClickListener(v -> {
                        if (!active.get()) return;
                        Log.e(TAG, "FAIL: view at col = " + entry.col() + ", row = " + entry.row() + " clicked, but it shouldn't have been");
                        failCondition.set(true);
                    });
                    entry.view().setOnFocusChangeListener((v, f) -> {
                        if (!active.get()) return;
                        if (f) {
                            Log.e(TAG, "FAIL: view at col = " + entry.col() + ", row = " + entry.row() + " gained focus, but it shouldn't have");
                            failCondition.set(true);
                        }
                    });
                    entry.view().setOnLongClickListener(v -> {
                        if (!active.get()) return false;
                        Log.e(TAG, "FAIL: view at col = " + entry.col() + ", row = " + entry.row() + " long clicked, but it shouldn't have been");
                        failCondition.set(true);
                        return true;
                    });
                });

                if (longPress) {
                    targetView.setOnLongClickListener(v -> {
                        if (!active.get()) return false;
                        if (passCondition.get()) {
                            Log.e(TAG, "FAIL: target long pressed more than once");
                            failCondition.set(true);
                            return true;
                        }
                        Log.d(TAG, "PASS condition met: target long pressed");
                        passCondition.set(true);
                        return true;
                    });
                } else {
                    targetView.setOnClickListener(v -> {
                        if (!active.get()) return;
                        if (passCondition.get()) {
                            Log.e(TAG, "FAIL: target clicked more than once");
                            failCondition.set(true);
                            return;
                        }
                        Log.d(TAG, "PASS condition met: target clicked");
                        passCondition.set(true);
                    });
                }

                new Thread(() -> {
                    try {
                        Thread.sleep(FOCUS_RESET_DELAY);
                    } catch (InterruptedException e) {
                        Log.d(TAG, "interrupted", e);
                        return;
                    }

                    if (longPress && withDownUp) {
                        directionalPadInput.dpadSelect(KeyEventType.DOWN);
                        handler.postDelayed(() -> {
                            new Thread(() -> {
                                directionalPadInput.dpadSelect(KeyEventType.DOWN);
                                directionalPadInput.dpadSelect(KeyEventType.UP);
                            }).start();
                        }, 1000);
                    } else if (!longPress && withDownUp) {
                        directionalPadInput.dpadSelect(KeyEventType.DOWN);
                        directionalPadInput.dpadSelect(KeyEventType.UP);
                    } else if (longPress) {
                        directionalPadInput.dpadLongPress();
                    } else {
                        directionalPadInput.dpadSelect(KeyEventType.CLICK);
                    }
                }).start();

                // wait until everything has settled
                long delay = NAVIGATION_VALID_AFTER + FOCUS_RESET_DELAY;
                if (longPress && withDownUp) delay += 1000; // wait for long press too
                boolean started = handler.postDelayed(() -> {
                    active.set(false);
                    boolean result = passCondition.get() && !failCondition.get();
                    secAdapter.provideResult(result);
                }, delay);
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
