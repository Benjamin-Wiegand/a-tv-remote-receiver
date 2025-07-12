package io.benwiegand.atvremote.receiver.ui.preference;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.XmlRes;
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

        int preferenceRes = getIntent().getIntExtra(EXTRA_PREFERENCE_XML_RESOURCE, -1);
        if (preferenceRes == -1) {
            Log.wtf(TAG, "required extra for preference xml resource id not provided!");
            assert false;
            finish();
            return;
        }

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.root, new BasicSettingsFragment(preferenceRes))
                .commitNow();

    }

    public static Intent getLaunchIntent(Context context, @XmlRes int preferenceRes) {
        return new Intent(context, BasicSettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra(EXTRA_PREFERENCE_XML_RESOURCE, preferenceRes);
    }
}
