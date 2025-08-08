package io.benwiegand.atvremote.receiver.ui.test;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import io.benwiegand.atvremote.receiver.R;

public record CompatibilityTestSummary(
        @StringRes
        int compatibilityVerdict,
        @DrawableRes
        int compatibilityVerdictIcon,
        @ColorRes
        int compatibilityVerdictColor,
        @StringRes
        int compatibilityTestSummary
) {

    public static CompatibilityTestSummary getTestsFailedToRun() {
        return new CompatibilityTestSummary(
                R.string.compatibility_test_results_verdict_tests_failed_to_run,
                R.drawable.denied,
                R.color.bad_thing,
                R.string.compatibility_test_results_summary_fail
        );
    }

    public static CompatibilityTestSummary getNavigationIssues() {
        return new CompatibilityTestSummary(
                R.string.compatibility_test_results_verdict_navigation_issues,
                R.drawable.accepted,
                R.color.kinda_bad_thing,
                R.string.compatibility_test_results_summary_success
        );
    }

    public static CompatibilityTestSummary getNoSignificantProblems() {
        return new CompatibilityTestSummary(
                R.string.compatibility_test_results_verdict_no_significant_problems,
                R.drawable.accepted,
                R.color.good_thing,
                R.string.compatibility_test_results_summary_success
        );
    }

}
