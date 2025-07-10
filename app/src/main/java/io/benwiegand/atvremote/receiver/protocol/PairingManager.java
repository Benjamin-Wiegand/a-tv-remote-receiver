package io.benwiegand.atvremote.receiver.protocol;

import static io.benwiegand.atvremote.receiver.auth.ssl.KeyUtil.getSecureRandom;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.benwiegand.atvremote.receiver.control.ControlNotInitializedException;
import io.benwiegand.atvremote.receiver.control.ControlScheme;
import io.benwiegand.atvremote.receiver.ui.PairingDialog;

public class PairingManager implements PairingCallback {
    private static final String TAG = PairingManager.class.getSimpleName();

    private static final int TOKEN_MIN_LENGTH = 64;
    private static final int TOKEN_MAX_LENGTH = 128;
    private static final String KEY_PAIRED_DEVICES = "devices";
    private static final String KEY_PREFIX_PAIRING_DATA = "pairing_data_";

    private final Object pairingLock = new Object();
    private PairingSession pairingSession = null;

    private final Context context;
    private byte[] fingerprint = null;

    private final ControlScheme controlScheme;

    private final Map<String, String> tokenMap = new HashMap<>();

    public PairingManager(Context context, ControlScheme controlScheme) {
        this.context = context;
        this.controlScheme = controlScheme;

        loadPairedDevices();
    }

    public void setFingerprint(byte[] fingerprint) {
        this.fingerprint = fingerprint;
    }

    private void loadPairedDevices() {
        synchronized (tokenMap) {
            Log.d(TAG, "loading token map");
            tokenMap.clear();
            SharedPreferences sp = context.getSharedPreferences(KEY_PAIRED_DEVICES, Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> entry : sp.getAll().entrySet()) {
                String deviceId = entry.getKey();

                if (entry.getValue() instanceof String token)
                    tokenMap.put(token, deviceId);
                else
                    Log.wtf(TAG, "non-string value in paired devices table for key: " + deviceId);
            }
        }
    }

    private boolean addNewDevice(PairingData data) {
        synchronized (tokenMap) {
            String deviceId = UUID.randomUUID().toString();

            boolean tokenCommitted = context.getSharedPreferences(KEY_PAIRED_DEVICES, Context.MODE_PRIVATE)
                    .edit()
                    .putString(deviceId, data.token())
                    .commit();

            if (!tokenCommitted) {
                Log.wtf(TAG, "failed to write token to preference map");
                return false;
            }

            tokenMap.put(data.token(), deviceId);

            return writePairingData(deviceId, data);
        }

    }

    private boolean writePairingData(String deviceId, PairingData data) {
        return data.writeToPreferences(sharedPreferencesForDevice(deviceId).edit());
    }

    public PairingData fetchPairingData(String token) {
        String deviceId;
        synchronized (tokenMap) {
            deviceId = tokenMap.get(token);
            if (deviceId == null) return null;
        }

        return PairingData.readFromPreferences(sharedPreferencesForDevice(deviceId));
    }

    private SharedPreferences sharedPreferencesForDevice(String deviceId) {
        String key = KEY_PREFIX_PAIRING_DATA + deviceId;
        Log.d(TAG, "loading " + key);
        return context.getSharedPreferences(key, Context.MODE_PRIVATE);
    }

    public void startPairing(Runnable cancelCallback) throws ControlNotInitializedException {
        synchronized (pairingLock) {
            if (pairingSession == null) pairingSession = createPairingSessionLocked();
            pairingSession.cancelCallbacks().add(cancelCallback);
        }
    }

    public boolean updatePairingData(PairingData pairingData) {
        String deviceId;
        synchronized (tokenMap) {
            deviceId = tokenMap.get(pairingData.token());
            if (deviceId == null) return false;
        }

        return writePairingData(deviceId, pairingData);
    }

    public String pair(int pairingCode, Runnable cancelCallback) {
        synchronized (pairingLock) {
            if (pairingSession == null) return null;

            PairingSession pairingSession = this.pairingSession;
            pairingSession.cancelCallbacks().remove(cancelCallback);
            cancelPairingLocked();

            try {
                if (pairingSession.pairingCode() != pairingCode) {
                    Log.v(TAG, "wrong code provided");
                    return null;
                }

                String token = generateToken();

                PairingData data = new PairingData(token, null, null, -1, -1);
                if (!addNewDevice(data)) return null;

                return token;
            } catch (RuntimeException e) {
                try {
                    cancelCallback.run();
                } catch (Throwable t) {
                    Log.wtf(TAG, "exception thrown in cancel callback", t);
                }
                throw e;
            }
        }
    }

    public void cancelPairing(Runnable cancelCallback) {
        synchronized (pairingLock) {
            if (pairingSession == null) return;
            pairingSession.cancelCallbacks().remove(cancelCallback);
            if (pairingSession.cancelCallbacks().isEmpty()) cancelPairingLocked();
        }
    }

    private static int nextIntBetween(SecureRandom r, int min, int upperbound) {
        return r.nextInt(upperbound - min) + min;
    }

    private String generateToken() {
        SecureRandom r = getSecureRandom();

        int length = nextIntBetween(r, TOKEN_MIN_LENGTH, TOKEN_MAX_LENGTH);
        char[] cBuffer = new char[length];
        for (int i = 0; i < length; i++) {
                cBuffer[i] = (char) nextIntBetween(r, 33, 127);
        }

        return new String(cBuffer);
    }

    private PairingSession createPairingSessionLocked() throws ControlNotInitializedException {
        Log.i(TAG, "starting new pairing session");

        int code = getSecureRandom().nextInt(999999);

        if (fingerprint == null) throw new IllegalStateException("fingerprint should be set for a pairing process to initiate");

        PairingDialog dialog = controlScheme.getPairingOverlayOutput().createPairingDialog(this, code, fingerprint);
        dialog.start();

        return new PairingSession(dialog, new LinkedList<>(), code, fingerprint);
    }

    private void cancelPairingLocked() {
        if (pairingSession == null) return;
        pairingSession.dialog().destroy();
        for (Runnable cancelCb : pairingSession.cancelCallbacks()) {
            try {
                cancelCb.run();
            } catch (Throwable t) {
                Log.e(TAG, "exception in cancel callback", t);
            }
        }
        pairingSession = null;
    }

    @Override
    public void cancel() {
        synchronized (pairingLock) {
            cancelPairingLocked();
        }
    }

    @Override
    public void disablePairingForAWhile(TimeUnit timeUnit, long period) {
        synchronized (pairingLock) {
            cancelPairingLocked();
            // todo: apply pairing ban
        }
    }
}
