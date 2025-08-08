package io.benwiegand.atvremote.receiver.ui.test;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.widget.TextViewCompat;

import java.util.Optional;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.stuff.makeshiftbind.MakeshiftServiceConnection;
import io.benwiegand.atvremote.receiver.util.UiUtil;

public class CompatibilityTestResultsActivity extends Activity {
    private final static String TAG = CompatibilityTestResultsActivity.class.getSimpleName();

    private final AutoDetectServiceConnection autoDetectServiceConnection = new AutoDetectServiceConnection();
    private CompatibilityAutoDetectService.ServiceBinder autoDetectServiceBinder = null;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_compatibility_test_results);

        UiUtil.inflateDropdown(
                findViewById(R.id.advanced_info_dropdown),
                findViewById(R.id.advanced_info),
                R.string.test_results_expand_text,
                R.string.test_results_collapse_text);

        boolean bindResult = bindService(new Intent(this, CompatibilityAutoDetectService.class), autoDetectServiceConnection, BIND_IMPORTANT);
        assert bindResult;
    }

    private void setTestSummary(CompatibilityTestSummary summary) {
        TextView summaryText = findViewById(R.id.summary_text);
        TextView verdictText = findViewById(R.id.compatibility_verdict_text);
        summaryText.setText(summary.compatibilityTestSummary());
        verdictText.setText(summary.compatibilityVerdict());
        verdictText.setCompoundDrawablesRelativeWithIntrinsicBounds(AppCompatResources.getDrawable(this, summary.compatibilityVerdictIcon()), null, null, null);
        TextViewCompat.setCompoundDrawableTintList(verdictText,
                new ColorStateList(
                        new int[][] {new int[0]},
                        new int[] {getColor(summary.compatibilityVerdictColor())}));
    }

    private View inflateAdvancedTestResult(ViewGroup root, CompatibilityAutoDetectService.TestCompletionRecord<?> testResult) {
        View view = getLayoutInflater().inflate(R.layout.layout_advanced_test_result, root, false);

        Object output = testResult.result();

        TextView testNameText = view.findViewById(R.id.test_name_text);
        TextView testTypeText = view.findViewById(R.id.test_type_text);
        TextView detailText = view.findViewById(R.id.detail_text);
        TextView testStatusText = view.findViewById(R.id.test_status_text);
        ImageView testStatusIcon = view.findViewById(R.id.test_status_icon);

        testNameText.setText(testResult.name());
        testTypeText.setText(testResult.type().name());

        if (output instanceof Throwable t) {
            detailText.setText(t.toString());
            detailText.setVisibility(View.VISIBLE);
            testStatusIcon.setImageDrawable(AppCompatResources.getDrawable(this, R.drawable.denied));
            testStatusIcon.setColorFilter(getColor(R.color.bad_thing));
        } else if (output instanceof Boolean b) {
            if (b) {
                testStatusText.setText(R.string.input_compatibility_test_pass_text);
                testStatusText.setTextColor(getColor(R.color.good_thing));
                testStatusIcon.setImageDrawable(AppCompatResources.getDrawable(this, R.drawable.accepted));
                testStatusIcon.setColorFilter(getColor(R.color.good_thing));
            } else {
                testStatusText.setText(R.string.input_compatibility_test_fail_text);
                testStatusText.setTextColor(getColor(R.color.bad_thing));
                testStatusIcon.setImageDrawable(AppCompatResources.getDrawable(this, R.drawable.denied));
                testStatusIcon.setColorFilter(getColor(R.color.bad_thing));
            }
        } else {
            Log.e(TAG, "unknown type of test result for test: " + testResult.name());
            Log.e(TAG, "type: " + testResult.type());
            Log.e(TAG, "result: " + output);
            testStatusText.setText("?");
            detailText.setText(String.valueOf(output));
            detailText.setVisibility(View.VISIBLE);
            assert false;
        }

        return view;
    }

    private Optional<CompatibilityAutoDetectService.ServiceBinder> getAutoDetectServiceBinder() {
        return Optional.ofNullable(autoDetectServiceBinder);
    }

    public class AutoDetectServiceConnection extends MakeshiftServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "compatibility auto detect service connected");
            autoDetectServiceBinder = (CompatibilityAutoDetectService.ServiceBinder) service;

            setTestSummary(autoDetectServiceBinder.getCompatibilityTestSummary());

            LinearLayout advancedInfo = findViewById(R.id.advanced_info);
            for (CompatibilityAutoDetectService.TestCompletionRecord<Throwable> testResult : autoDetectServiceBinder.getFailedTests()) {
                advancedInfo.addView(inflateAdvancedTestResult(advancedInfo, testResult));
            }
            for (CompatibilityAutoDetectService.TestCompletionRecord<?> testResult : autoDetectServiceBinder.getTestResults()) {
                advancedInfo.addView(inflateAdvancedTestResult(advancedInfo, testResult));
            }

            //todo
            findViewById(R.id.save_button);
            findViewById(R.id.cancel_button);

            findViewById(R.id.loading_spinner).setVisibility(View.GONE);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "compatibility auto detect service disconnected");
            autoDetectServiceBinder = null;
        }
    }
}