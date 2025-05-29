package io.benwiegand.atvremote.receiver.protocol.json;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import java.util.HashSet;

import io.benwiegand.atvremote.receiver.control.ControlScheme;

public record ReceiverCapabilities(HashSet<String> supportedFeatures) {

    public static ReceiverCapabilities getCapabilities(Context context, ControlScheme scheme) {
        HashSet<String> features = new HashSet<>();

        // for now always assume support for these
        features.add(SUPPORTED_FEATURE_APP_SWITCHER);
        features.add(SUPPORTED_FEATURE_QUICK_SETTINGS);
        features.add(SUPPORTED_FEATURE_MEDIA_SESSIONS);
        features.add(SUPPORTED_FEATURE_MEDIA_CONTROLS);

        if (supportsDashboardButton(context)) features.add(SUPPORTED_FEATURE_DASHBOARD_BUTTON);

        return new ReceiverCapabilities(features);
    }

    private static boolean supportsDashboardButton(Context context) {
        ComponentName componentName = new ComponentName("com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.launcherx.dashboard.DashboardHandler");
        try {
            context.getPackageManager().getActivityInfo(componentName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // non-TV builds
    public static final String SUPPORTED_FEATURE_APP_SWITCHER = "APP_SWITCHER";
    public static final String SUPPORTED_FEATURE_QUICK_SETTINGS = "QUICK_SETTINGS";

    // google tv
    public static final String SUPPORTED_FEATURE_DASHBOARD_BUTTON = "DASHBOARD_BUTTON";

    // advanced inputs
    public static final String SUPPORTED_FEATURE_MEDIA_CONTROLS = "MEDIA_CONTROLS";
    public static final String SUPPORTED_FEATURE_MEDIA_SESSIONS = "MEDIA_SESSIONS";
}
