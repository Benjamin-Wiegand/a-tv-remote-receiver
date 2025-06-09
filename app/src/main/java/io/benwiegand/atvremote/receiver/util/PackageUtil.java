package io.benwiegand.atvremote.receiver.util;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

public class PackageUtil {
    private static final String TAG = PackageUtil.class.getSimpleName();

    public static String getAppName(Context context, String packageName, String defaultValue) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo packageInfo = pm.getPackageInfo(packageName, 0);
            ApplicationInfo applicationInfo = packageInfo.applicationInfo;
            if (applicationInfo != null)
                return (String) applicationInfo.loadLabel(pm);
        } catch (Throwable t) {
            Log.w(TAG, "unable to resolve the name of package: " + packageName + "\n" + ErrorUtil.getLightStackTrace(t));
        }

        return defaultValue;
    }

    public static String getAppName(Context context, String packageName) {
        return getAppName(context, packageName, null);
    }

}
