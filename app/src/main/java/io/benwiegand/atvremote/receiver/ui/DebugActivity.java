package io.benwiegand.atvremote.receiver.ui;


import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.Map;
import java.util.UUID;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.control.AccessibilityInputService;
import io.benwiegand.atvremote.receiver.control.NotificationInputService;
import io.benwiegand.atvremote.receiver.network.TVRemoteConnection;
import io.benwiegand.atvremote.receiver.network.TVRemoteServer;

public class DebugActivity extends AppCompatActivity {
    private static final String TAG = DebugActivity.class.getSimpleName();

    private AccessibilityInputService.AccessibilityInputHandler binder = null;
    private final BroadcastReceiver receiver = new Receiver();

    private TVRemoteServer.ServerBinder serverBinder = null;

    @SuppressLint({"InlinedApi"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_debug);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.debug), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        findViewById(R.id.try_bind_button).setOnClickListener(v -> {
            Intent intent = new Intent(AccessibilityInputService.INTENT_ACCESSIBILITY_INPUT_BINDER_REQUEST);
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
            Log.i(TAG, "binder request broadcast sent");
        });

        findViewById(R.id.show_test_notif_button).setOnClickListener(v -> {
            if (binder == null) return;
            binder.showTestNotification();
        });

        findViewById(R.id.show_cursor_button).setOnClickListener(v -> {
            if (binder == null) return;
            // todo fixme
//            binder.showCursor();
//            binder.cursorMove(420, 420);
        });

        findViewById(R.id.show_pairing_dialog_button).setOnClickListener(v -> {
            if (binder == null) return;
            binder.showTestPairingDialog();
        });

        findViewById(R.id.start_server_button).setOnClickListener(v -> {
            Intent sintent = new Intent(this, TVRemoteServer.class);
            startService(sintent);
            bindService(sintent, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    Log.i(TAG, "service connected: " + name);
                    serverBinder = (TVRemoteServer.ServerBinder) service;
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    Log.i(TAG, "service disconnected: " + name);
                }
            }, 0);
        });

        findViewById(R.id.show_debug_overlay).setOnClickListener(v -> {
            if (binder == null) return;
            binder.showDebugOverlay();
        });

        findViewById(R.id.accessibility_settings_button).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        findViewById(R.id.notification_listener_settings_button).setOnClickListener(v -> {
            Intent detailIntent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            detailIntent.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, new ComponentName(this, NotificationInputService.class).flattenToString());
            try {
                startActivity(detailIntent);
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "notification listener detail activity does not exist, falling back");
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }
        });

        findViewById(R.id.update_debug_info_button).setOnClickListener(v -> {
            TextView debugInfoText = findViewById(R.id.debug_info_text);

            StringBuilder sb = new StringBuilder("====== debug ======\n");

            if (binder != null) sb.append("accessibility service connected\n");
            if (serverBinder != null) {
                sb.append("server connected\n")
                        .append("port: ")
                            .append(serverBinder.getPort())
                            .append("\n")
                        .append("connections (")
                            .append(serverBinder.getConnections().size())
                            .append("):\n");

                for (Map.Entry<UUID, TVRemoteConnection> entry : serverBinder.getConnections().entrySet()) sb
                        .append(" - uuid ")
                        .append(entry.getKey())
                        .append(" - from ")
                        .append(entry.getValue().getRemoteAddress())
                        .append(" - dead = ")
                        .append(entry.getValue().isDead())
                        .append("\n");

            }

            debugInfoText.setText(sb.toString());
        });

        // todo
//        startActivity(new Intent(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS));
//        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS));

        IntentFilter filter = new IntentFilter();
        filter.addAction(AccessibilityInputService.INTENT_ACCESSIBILITY_INPUT_BINDER_INSTANCE);
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(receiver, filter);
    }

    public class Receiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            binder = (AccessibilityInputService.AccessibilityInputHandler) intent.getExtras().getBinder(AccessibilityInputService.EXTRA_BINDER_INSTANCE);
            Log.i(TAG, "got binder instance: " + binder);
        }

    }
}