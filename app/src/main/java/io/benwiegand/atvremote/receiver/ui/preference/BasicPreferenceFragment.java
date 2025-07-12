package io.benwiegand.atvremote.receiver.ui.preference;

import android.os.Bundle;

import androidx.annotation.XmlRes;
import androidx.leanback.preference.LeanbackPreferenceFragmentCompat;

// this is boilerplate for preference api
public class BasicPreferenceFragment extends LeanbackPreferenceFragmentCompat {
    @XmlRes
    private final int preferenceRes;

    public BasicPreferenceFragment(int preferenceRes) {
        this.preferenceRes = preferenceRes;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(preferenceRes, rootKey);
    }
}
