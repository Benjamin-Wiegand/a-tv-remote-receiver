package io.benwiegand.atvremote.receiver.network;

import static io.benwiegand.atvremote.receiver.network.NetworkDebugConstants.FIX_PORT_NUMBER;
import static io.benwiegand.atvremote.receiver.network.SocketUtil.tryClose;
import static io.benwiegand.atvremote.receiver.protocol.ProtocolConstants.OP_EVENT_STREAM_EVENT;

import android.app.Service;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

import io.benwiegand.atvremote.receiver.async.Sec;
import io.benwiegand.atvremote.receiver.auth.ssl.CorruptedKeystoreException;
import io.benwiegand.atvremote.receiver.auth.ssl.KeyUtil;
import io.benwiegand.atvremote.receiver.auth.ssl.KeystoreManager;
import io.benwiegand.atvremote.receiver.control.AccessibilityInputService;
import io.benwiegand.atvremote.receiver.control.ControlSourceConnectionManager;
import io.benwiegand.atvremote.receiver.control.NotificationInputService;
import io.benwiegand.atvremote.receiver.protocol.PairingManager;
import io.benwiegand.atvremote.receiver.protocol.stream.EventStreamManager;

public class TVRemoteServer extends Service {
    private static final String TAG = TVRemoteServer.class.getSimpleName();

    private static final int AUTO_PORT_NUMBER = 0;
    private static final int TARGET_PORT_NUMBER = FIX_PORT_NUMBER ? 6969 : AUTO_PORT_NUMBER;

    private final ServerBinder binder = new ServerBinder();
    private SSLServerSocketFactory serverSocketFactory = null;
    private PairingManager pairingManager = null;
    private final EventStreamManager eventStreamManager = new EventStreamManager(this::sendEvent);

    private final Map<UUID, TVRemoteConnection> connections = new ConcurrentHashMap<>();

    private ServiceAdvertiser serviceAdvertiser = null;
    private NsdManager nsdManager = null;

    private final Object listenThreadLock = new Object();
    private Thread listenThread = null;
    private SSLServerSocket serverSocket = null;
    private boolean shutdown = false;

    private ControlSourceConnectionManager controlSourceConnectionManager;


    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate()");

        controlSourceConnectionManager = new ControlSourceConnectionManager(this, this::onInputServiceBind);
        pairingManager = new PairingManager(this, controlSourceConnectionManager.getControlScheme());
        nsdManager = this.getSystemService(NsdManager.class);

        startListening();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy()");

        shutdown = true;
        for (TVRemoteConnection connection : connections.values()) {
            //todo: move off main thread
            tryClose(connection);
        }

        if (serverSocket != null) tryClose(serverSocket);

        controlSourceConnectionManager.destroy();
        eventStreamManager.destroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void onInputServiceBind(IBinder iBinder) {
        if (iBinder instanceof NotificationInputService.ServiceBinder serviceBinder) {
            serviceBinder.onServerBind(eventStreamManager);
        } else if (iBinder instanceof AccessibilityInputService.AccessibilityInputHandler serviceBinder) {
            serviceBinder.onServerBind(controlSourceConnectionManager.getControlScheme());
        }
    }

    private Sec<Void> sendEvent(UUID connectionUUID, String event) {
        TVRemoteConnection connection = connections.get(connectionUUID);
        if (connection == null) return Sec.premeditatedError(new IOException("no such connection"));

        return connection.sendOperation(OP_EVENT_STREAM_EVENT + " " + event)
                .map(r -> null);
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

            Log.d(TAG, "starting server socket on port " + TARGET_PORT_NUMBER);
            serverSocket = (SSLServerSocket) serverSocketFactory.createServerSocket(TARGET_PORT_NUMBER);
            Log.d(TAG, "listening on port " + serverSocket.getLocalPort());
            startAdvertising(serverSocket.getLocalPort());

            while (!shutdown) {
                SSLSocket newSocket = (SSLSocket) serverSocket.accept();
                Log.d(TAG, "CipherSuite: " + newSocket.getSession().getCipherSuite());
                Log.d(TAG, "Protocol: " + newSocket.getSession().getProtocol());
                Log.d(TAG, "LocalPrincipal: " + newSocket.getSession().getLocalPrincipal());

                synchronized (connections) {
                    UUID connectionUUID = UUID.randomUUID();
                    TVRemoteConnection connection = new TVRemoteConnection(
                            this, connectionUUID, pairingManager, eventStreamManager, newSocket,
                            controlSourceConnectionManager.getControlScheme(),
                            () -> onConnectionDeath(connectionUUID));
                    connections.put(connectionUUID, connection);
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

    private void onConnectionDeath(UUID connectionUUID) {
        connections.remove(connectionUUID);
    }

    public class ServerBinder extends Binder {
        public int getPort() {
            if (serverSocket == null) return -1;
            return serverSocket.getLocalPort();
        }

        public Map<UUID, TVRemoteConnection> getConnections() {
            return connections;
        }
    }
}
