package io.benwiegand.atvremote.receiver.network;

import static io.benwiegand.atvremote.receiver.network.SocketUtil.tryClose;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.nsd.NsdManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.LinkedList;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.auth.ssl.CorruptedKeystoreException;
import io.benwiegand.atvremote.receiver.auth.ssl.KeyUtil;
import io.benwiegand.atvremote.receiver.auth.ssl.KeystoreManager;
import io.benwiegand.atvremote.receiver.control.AccessibilityInputService;
import io.benwiegand.atvremote.receiver.control.ControlScheme;
import io.benwiegand.atvremote.receiver.control.ControlSourceErrors;
import io.benwiegand.atvremote.receiver.protocol.PairingManager;

public class TVRemoteServer extends Service {
    private static final String TAG = TVRemoteServer.class.getSimpleName();

    private static final int AUTO_PORT_NUMBER = 0;

    private final BroadcastReceiver accessibilityBinderReceiver = new AccessibilityBinderReceiver();
    private final ServerBinder binder = new ServerBinder();
    private SSLServerSocketFactory serverSocketFactory = null;
    private PairingManager pairingManager = null;

    private final List<TVRemoteConnection> connections = new LinkedList<>();

    private ServiceAdvertiser serviceAdvertiser = null;
    private NsdManager nsdManager = null;

    private final Object listenThreadLock = new Object();
    private Thread listenThread = null;
    private ControlScheme controlScheme;
    private SSLServerSocket serverSocket = null;
    private boolean shutdown = false;


    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate()");
        IntentFilter filter = new IntentFilter();
        filter.addAction(AccessibilityInputService.INTENT_ACCESSIBILITY_INPUT_BINDER_INSTANCE);
        LocalBroadcastManager
                .getInstance(this)
                .registerReceiver(accessibilityBinderReceiver, filter);

        nsdManager = this.getSystemService(NsdManager.class);

        // todo: this should be set based on whatever control schemes are configured
        String accessibilityServiceException = getString(R.string.control_source_not_loaded_accessibility);
        ControlSourceErrors controlSourceErrors = new ControlSourceErrors(
                accessibilityServiceException,
                accessibilityServiceException,
                "not implemented",
                "not implemented",
                accessibilityServiceException,
                "not implemented",
                accessibilityServiceException,
                accessibilityServiceException
        );
        controlScheme = new ControlScheme(controlSourceErrors);

        pairingManager = new PairingManager(this, controlScheme);

        startListening();
        requestAccessibilityBinder();

    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy()");

        shutdown = true;
        for (TVRemoteConnection connection : connections) {
            //todo: move off main thread
            tryClose(connection);
        }

        if (serverSocket != null) tryClose(serverSocket);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void startListening() {
        synchronized (listenThreadLock) {
            if (listenThread != null) return;
            Log.i(TAG, "starting socket listen thread");
            listenThread = new Thread(this::listenLoop);
            listenThread.start();
        }
    }

    private void startAdvertising(int port) {
        if (serviceAdvertiser != null) serviceAdvertiser.unregister();
        serviceAdvertiser = ServiceAdvertiser.createReceiverAdvertiser(this, nsdManager, port);
        serviceAdvertiser.register();
    }

    private void listenLoop() {
        try {

            KeystoreManager keystoreManager;
            byte[] fingerprint;
            try {
                Log.v(TAG, "initializing keystore");
                keystoreManager = new KeystoreManager(this);
                keystoreManager.loadKeystore();
                keystoreManager.initSSL();
                keystoreManager.saveKeystore();

                // since there's no "root of trust" here, the user must somehow compare these fingerprints
                fingerprint = KeyUtil.calculateCertificateFingerprint(keystoreManager.getSSLCertificate());
                pairingManager.setFingerprint(fingerprint);

                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(keystoreManager.getKeyManagers(), keystoreManager.getTrustManagers(), SecureRandom.getInstanceStrong());
                // todo: harden supported ciphers
//                sslContext.getSupportedSSLParameters().setCipherSuites();

                serverSocketFactory = sslContext.getServerSocketFactory();

            } catch (IOException | CorruptedKeystoreException | KeyManagementException |
                     NoSuchAlgorithmException e) {
                Log.wtf(TAG, "failed to load keystore", e);
                // todo: error notifications
                return;
            }

            if (serverSocket != null && !serverSocket.isClosed()) {
                Log.w(TAG, "closing existing server socket");
                tryClose(serverSocket);
            }

            Log.d(TAG, "starting server socket on port " + AUTO_PORT_NUMBER);
            serverSocket = (SSLServerSocket) serverSocketFactory.createServerSocket(AUTO_PORT_NUMBER);
            Log.d(TAG, "listening on port " + serverSocket.getLocalPort());
            startAdvertising(serverSocket.getLocalPort());

            while (!shutdown) {
                SSLSocket newSocket = (SSLSocket) serverSocket.accept();
                Log.d(TAG, "CipherSuite: " + newSocket.getSession().getCipherSuite());
                Log.d(TAG, "Protocol: " + newSocket.getSession().getProtocol());
                Log.d(TAG, "LocalPrincipal: " + newSocket.getSession().getLocalPrincipal());

                synchronized (connections) {
                    TVRemoteConnection connection = new TVRemoteConnection(this, pairingManager, newSocket, controlScheme);
                    connections.add(connection);
                }
            }
        } catch (IOException e) {
            // todo: try to recover from specific errors
            //  - in use
            //  - security exception
            Log.e(TAG, "IOException during socket listen loop", e);
            // todo: error notif
        } finally {
            stopSelf();
        }
    }

    public void requestAccessibilityBinder() {
        Log.v(TAG, "requesting accessibility service binder");
        Intent intent = new Intent(AccessibilityInputService.INTENT_ACCESSIBILITY_INPUT_BINDER_REQUEST);
        LocalBroadcastManager
                .getInstance(this)
                .sendBroadcast(intent);
    }

    public class AccessibilityBinderReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "got accessibility binder instance intent");
            Bundle extras = intent.getExtras();
            assert extras != null; // this intent should always have an extra

            AccessibilityInputService.AccessibilityInputHandler binder = (AccessibilityInputService.AccessibilityInputHandler) extras.getBinder(AccessibilityInputService.EXTRA_BINDER_INSTANCE);
            Log.i(TAG, "accessibility binder instance: " + binder);
            assert binder != null; // this extra should never be null

            // set accessibility control methods
            controlScheme.setDirectionalPadInput(binder.getDirectionalPadInput());
            controlScheme.setNavigationInput(binder.getNavigationInput());
            controlScheme.setCursorInput(binder.getCursorInput());
            controlScheme.setVolumeInput(binder.getVolumeInput());

            controlScheme.setOverlayOutput(binder.getOverlayOutput());
        }
    }


    public class ServerBinder extends Binder {
        public int getPort() {
            if (serverSocket == null) return -1;
            return serverSocket.getLocalPort();
        }

        public List<TVRemoteConnection> getConnections() {
            return connections;
        }
    }
}
