package io.benwiegand.atvremote.receiver.ui.preference;

import android.util.Log;

import androidx.annotation.XmlRes;
import androidx.fragment.app.Fragment;
import androidx.leanback.preference.LeanbackSettingsFragmentCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import java.util.function.Supplier;

// more boilerplate for preference api
public class BasicSettingsFragment extends LeanbackSettingsFragmentCompat {
    private static final String TAG = BasicSettingsFragment.class.getSimpleName();

    private final Supplier<Fragment> initialScreenSupplier;

    public BasicSettingsFragment(Supplier<Fragment> initialScreenSupplier) {
        this.initialScreenSupplier = initialScreenSupplier;
    }

    /**
     * creates a BasicSettingsFragment initialized with a BasicPreferenceFragment
     * @param basicPreferenceRes the xml preference resource to use for constructing the BasicPreferenceFragment
     */
    public BasicSettingsFragment(@XmlRes int basicPreferenceRes) {
        this(() -> new BasicPreferenceFragment(basicPreferenceRes));
    }

    @Override
    public void onPreferenceStartInitialScreen() {
        startPreferenceFragment(initialScreenSupplier.get());
    }

    @Override
    public boolean onPreferenceStartFragment(PreferenceFragmentCompat caller, Preference pref) {
        Log.wtf(TAG, "preference fragment not handled: " + pref.getFragment());
        return false;
    }

    @Override
    public boolean onPreferenceStartScreen(PreferenceFragmentCompat caller, PreferenceScreen pref) {
        Log.wtf(TAG, "preference screen not handled: " + pref.getFragment());
        return false;
    }
}
