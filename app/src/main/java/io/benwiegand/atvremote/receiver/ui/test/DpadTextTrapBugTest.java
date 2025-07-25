package io.benwiegand.atvremote.receiver.ui.test;

import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.List;

import io.benwiegand.atvremote.receiver.async.PendingSec;
import io.benwiegand.atvremote.receiver.control.input.DirectionalPadInput;

/**
 * tests a situation where the accessibility dpad can get stuck inside text editor views (and not
 * allow navigating out) which seems to happen on some devices.
 */
public class DpadTextTrapBugTest extends BasicButtonGridDpadTest {
    private static final String TAG = DpadTextTrapBugTest.class.getSimpleName();

    public DpadTextTrapBugTest(ViewGroup root, ViewGroup.LayoutParams rootLayoutParams, Callback callback, DirectionalPadInput directionalPadInput) {
        super(root, rootLayoutParams, callback, directionalPadInput);
    }


    @Override
    protected int gridHeight() {
        return 5;
    }

    @Override
    protected int gridWidth() {
        return 5;
    }

    @Override
    protected ViewGrid.Entry getStartingFocusView() {
        return getViewGrid().getOffsetFromCenter(0, -1);
    }

    @Override
    protected void setupViews() {
        super.setupViews();

        ViewGrid.Entry centerEntry = getViewGrid().getCenter();
        View centerView = centerEntry.view();
        ViewGroup viewGroup = (LinearLayout) centerView.getParent();

        int centerIndex = viewGroup.indexOfChild(centerView);

        EditText editText = new EditText(getContext());
        editText.setBackgroundTintList(COLOR_STATE_LIST);

        viewGroup.removeView(centerView);
        viewGroup.addView(editText, centerIndex, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        getViewGrid().set(centerEntry.col(), centerEntry.row(), editText);
    }

    @Override
    protected List<PendingSec<Boolean>> getTestList() {
        return List.of(
                testDirection(getViewGrid().getOffsetFromCenter(0, -1), DpadTestUtil.DpadDirection.DPAD_DOWN, 2, false),
                testDirection(getViewGrid().getOffsetFromCenter(0, 1), DpadTestUtil.DpadDirection.DPAD_UP, 2, false),
                testDirection(getViewGrid().getOffsetFromCenter(-1, 0), DpadTestUtil.DpadDirection.DPAD_RIGHT, 2, false),
                testDirection(getViewGrid().getOffsetFromCenter(1, 0), DpadTestUtil.DpadDirection.DPAD_LEFT, 2, false)
        );
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }
}
