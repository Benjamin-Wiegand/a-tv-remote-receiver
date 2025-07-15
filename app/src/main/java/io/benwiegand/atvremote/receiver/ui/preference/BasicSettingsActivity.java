package io.benwiegand.atvremote.receiver.ui.preference;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.XmlRes;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import io.benwiegand.atvremote.receiver.R;

// even more boilerplate for the preferences api
public class BasicSettingsActivity extends FragmentActivity {
    private static final String TAG = BasicSettingsActivity.class.getSimpleName();

    public static final String EXTRA_PREFERENCE_XML_RESOURCE = "xmlres";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_basic_settings);

        setupPreferenceFragment();
    }

    public static Intent getLaunchIntent(Context context, @XmlRes int preferenceRes) {
        return new Intent(context, BasicSettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_PREFERENCE_XML_RESOURCE, preferenceRes);
    }

    // optionally override one or more of the following three methods for subclasses

    @XmlRes
    protected int getPreferenceResource() {
        int preferenceRes = getIntent().getIntExtra(EXTRA_PREFERENCE_XML_RESOURCE, -1);
        if (preferenceRes == -1) Log.wtf(TAG, "required extra for preference xml resource id not provided!");
        return preferenceRes;
    }

    protected Fragment getPreferenceFragment() {
        int preferenceRes = getPreferenceResource();
        if (preferenceRes == -1) return null;

        return new BasicSettingsFragment(preferenceRes);
    }

    protected void setupPreferenceFragment() {
        Fragment fragment = getPreferenceFragment();
        if (fragment == null) {
            Log.wtf(TAG, "failed to initialize preference fragment");
            assert false;
            finish();
            return;
        }

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.root, fragment)
                .commitNow();
    }
}
