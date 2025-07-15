package io.benwiegand.atvremote.receiver.ui.preference;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.Optional;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.network.TVRemoteServer;

public class InputMethodSettingsActivity extends BasicSettingsActivity {
    private static final String TAG = InputMethodSettingsActivity.class.getSimpleName();

    private final ServiceConnection serverConnection = new ServerConnection();
    private TVRemoteServer.ServerBinder serverBinder = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent sintent = new Intent(this, TVRemoteServer.class);
        bindService(sintent, serverConnection, 0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(serverConnection);
    }

    @Override
    protected int getPreferenceResource() {
        return R.xml.input_method_preferences;
    }

    @Override
    protected void onPause() {
        if (!isChangingConfigurations()) finish();
        getServerBinder().ifPresent(binder -> {
            Toast.makeText(this, R.string.input_method_preferences_toast_applying_settings, Toast.LENGTH_SHORT).show();
            binder.regenerateControlScheme();
        });
        super.onPause();
    }

    private Optional<TVRemoteServer.ServerBinder> getServerBinder() {
        return Optional.ofNullable(serverBinder);
    }

    private class ServerConnection implements ServiceConnection {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "server binder connected");
            serverBinder = (TVRemoteServer.ServerBinder) service;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "server binder disconnected");
            serverBinder = null;
        }
    }

    public static Intent getLaunchIntent(Context context) {
        return new Intent(context, InputMethodSettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
