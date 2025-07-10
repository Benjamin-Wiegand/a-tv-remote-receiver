package io.benwiegand.atvremote.receiver.ui;


import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Map;
import java.util.UUID;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.control.AccessibilityInputService;
import io.benwiegand.atvremote.receiver.control.IMEInputService;
import io.benwiegand.atvremote.receiver.control.NotificationInputService;
import io.benwiegand.atvremote.receiver.network.TVRemoteConnection;
import io.benwiegand.atvremote.receiver.network.TVRemoteServer;
import io.benwiegand.atvremote.receiver.protocol.KeyEventType;
import io.benwiegand.atvremote.receiver.stuff.makeshiftbind.MakeshiftServiceConnection;
import io.benwiegand.atvremote.receiver.util.UiUtil;

public class DebugActivity extends AppCompatActivity {
    private static final String TAG = DebugActivity.class.getSimpleName();

    private AccessibilityInputService.AccessibilityInputHandler binder = null;
    private final MakeshiftServiceConnection debugServiceConnection = new DebugServiceConnection();

    private TVRemoteServer.ServerBinder serverBinder = null;
    private IMEInputService.ServiceBinder imeBinder = null;

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

        findViewById(R.id.show_debug_overlay).setOnClickListener(v -> {
            if (binder == null) return;
            binder.showDebugOverlay();
        });

        findViewById(R.id.show_test_notif_button).setOnClickListener(v -> {
            if (binder == null) return;
            binder.showTestNotification();
        });

        findViewById(R.id.show_pairing_dialog_button).setOnClickListener(v -> {
            if (binder == null) return;
            binder.showTestPairingDialog();
        });

        findViewById(R.id.start_server_button).setOnClickListener(v -> {
            Intent sintent = new Intent(this, TVRemoteServer.class);
            startService(sintent);
            bindService(sintent, debugServiceConnection, 0);
        });

        findViewById(R.id.application_overlay_settings_button).setOnClickListener(v -> tryActivityIntents(
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                new Intent(Settings.ACTION_APPLICATION_SETTINGS)
        ));

        findViewById(R.id.accessibility_settings_button).setOnClickListener(v -> tryActivityIntents(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        ));

        findViewById(R.id.notification_listener_settings_button).setOnClickListener(v -> tryActivityIntents(
                new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, new ComponentName(this, NotificationInputService.class).flattenToString()),
                new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        ));

        findViewById(R.id.keyboard_settings_button).setOnClickListener(v -> tryActivityIntents(
                new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        ));

        findViewById(R.id.switch_ime_keyboard_button).setOnClickListener(v -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            imm.showInputMethodPicker();
        });

        findViewById(R.id.set_ime_keyboard_button).setOnClickListener(v -> {
            if (binder == null) {
                Log.e(TAG, "no accessibility binder");
                Toast.makeText(this, "no accessibility binder", Toast.LENGTH_SHORT).show();
                return;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Log.e(TAG, "set ime not supported on your api level");
                Toast.makeText(this, "set ime not supported on your api level", Toast.LENGTH_SHORT).show();
                return;
            }
            binder.switchToIme();
        });

        findViewById(R.id.test_ime_dpad_button).setOnClickListener(v -> {
            if (imeBinder == null) {
                Log.e(TAG, "no ime binder");
                Toast.makeText(this, "no ime binder, did you select the ime?", Toast.LENGTH_SHORT).show();
                return;
            }

            imeBinder.getDirectionalPadInput().dpadDown(KeyEventType.CLICK);
            Toast.makeText(this, "dpadDown()", Toast.LENGTH_SHORT).show();
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

        Spinner showMatchingNodesSpinner = findViewById(R.id.debug_show_matching_nodes_spinner);
        ArrayAdapter<AccessibilityInputService.NodeCondition> conditionAdapter = new ArrayAdapter<>(this, R.layout.layout_debug_spinner_item);
        conditionAdapter.addAll(AccessibilityInputService.NodeCondition.values());
        showMatchingNodesSpinner.setAdapter(conditionAdapter);
        showMatchingNodesSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (binder == null) return;

                assert position < AccessibilityInputService.NodeCondition.values().length;
                assert position >= 0;
                binder.setShowMatchingDebugNodesCondition(AccessibilityInputService.NodeCondition.values()[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                if (binder == null) return;
                binder.setShowMatchingDebugNodesCondition(null);
            }
        });

        MakeshiftServiceConnection.bindService(this, new ComponentName(this, AccessibilityInputService.class), debugServiceConnection);
        MakeshiftServiceConnection.bindService(this, new ComponentName(this, IMEInputService.class), debugServiceConnection);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.i(TAG, "onKeyDown(): " + event.toString());
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        Log.i(TAG, "onKeyUp(): " + event.toString());
        return super.onKeyUp(keyCode, event);
    }

    private void tryActivityIntents(Intent... intents) {
        if (!UiUtil.tryActivityIntents(this, intents))
            Toast.makeText(this, "no suitable activity found", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        debugServiceConnection.destroy();
    }

    public class DebugServiceConnection extends MakeshiftServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "service connected: " + name);
            if (name.equals(new ComponentName(DebugActivity.this, AccessibilityInputService.class))) {
                binder = (AccessibilityInputService.AccessibilityInputHandler) service;
            } else if (name.equals(new ComponentName(DebugActivity.this, TVRemoteServer.class))) {
                serverBinder = (TVRemoteServer.ServerBinder) service;
            } else if (name.equals(new ComponentName(DebugActivity.this, IMEInputService.class))) {
                imeBinder = (IMEInputService.ServiceBinder) service;
            }

        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "service disconnected: " + name);
        }
    }
}