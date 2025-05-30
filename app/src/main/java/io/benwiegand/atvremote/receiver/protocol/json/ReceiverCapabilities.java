package io.benwiegand.atvremote.receiver.protocol.json;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import java.util.HashSet;

import io.benwiegand.atvremote.receiver.control.ControlScheme;

import static io.benwiegand.atvremote.receiver.control.IntentConstants.GOOGLE_TV_DASHBOARD_ACTIVITY;
import static io.benwiegand.atvremote.receiver.control.IntentConstants.LINEAGE_SYSTEM_OPTIONS_ACTIVITY;

public record ReceiverCapabilities(
        HashSet<String> supportedFeatures,
        HashSet<String> extraButtons
) {

    public static ReceiverCapabilities getCapabilities(Context context, ControlScheme scheme) {
        PackageManager pm = context.getPackageManager();

        HashSet<String> features = new HashSet<>();
        HashSet<String> buttons = new HashSet<>();

        // for now always assume support for these
        features.add(SUPPORTED_FEATURE_APP_SWITCHER);
        features.add(SUPPORTED_FEATURE_QUICK_SETTINGS);
        features.add(SUPPORTED_FEATURE_MEDIA_SESSIONS);
        features.add(SUPPORTED_FEATURE_MEDIA_CONTROLS);

        if (supportsDashboardButton(pm)) buttons.add(EXTRA_BUTTON_GTV_DASHBOARD);
        if (supportsLineageSystemOptionsButton(pm)) buttons.add(EXTRA_BUTTON_LINEAGE_SYSTEM_OPTIONS);

        return new ReceiverCapabilities(features, buttons);
    }

    private static boolean checkForActivity(PackageManager pm, ComponentName componentName) {
        try {
            pm.getActivityInfo(componentName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static boolean supportsDashboardButton(PackageManager pm) {
        return checkForActivity(pm, GOOGLE_TV_DASHBOARD_ACTIVITY);
    }

    private static boolean supportsLineageSystemOptionsButton(PackageManager pm) {
        return checkForActivity(pm, LINEAGE_SYSTEM_OPTIONS_ACTIVITY);
    }

    // non-TV builds
    public static final String SUPPORTED_FEATURE_APP_SWITCHER = "APP_SWITCHER";
    public static final String SUPPORTED_FEATURE_QUICK_SETTINGS = "QUICK_SETTINGS";

    // advanced inputs
    public static final String SUPPORTED_FEATURE_MEDIA_CONTROLS = "MEDIA_CONTROLS";
    public static final String SUPPORTED_FEATURE_MEDIA_SESSIONS = "MEDIA_SESSIONS";

    // google tv
    public static final String EXTRA_BUTTON_GTV_DASHBOARD = "DASHBOARD_BUTTON";

    // lineage os
    public static final String EXTRA_BUTTON_LINEAGE_SYSTEM_OPTIONS = "LINEAGE_SYSTEM_OPTIONS_BUTTON";
}
