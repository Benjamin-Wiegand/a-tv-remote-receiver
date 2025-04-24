package io.benwiegand.atvremote.receiver.network;

import static io.benwiegand.atvremote.receiver.protocol.ProtocolConstants.MDNS_SERVICE_TYPE;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.provider.Settings;
import android.util.Log;

import io.benwiegand.atvremote.receiver.R;

public class ServiceAdvertiser implements NsdManager.RegistrationListener {
    private static final String TAG = ServiceAdvertiser.class.getSimpleName();

    private final NsdServiceInfo serviceInfo;
    private final NsdManager nsdManager;

    public ServiceAdvertiser(NsdManager nsdManager, NsdServiceInfo serviceInfo) {
        this.serviceInfo = serviceInfo;
        this.nsdManager = nsdManager;
    }

    public void register() {
        Log.d(TAG, "registering NSD service");
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, this);
    }

    public void unregister() {
        Log.d(TAG, "unregistering NSD service");
        nsdManager.unregisterService(this);
    }

    @Override
    public void onServiceRegistered(NsdServiceInfo serviceInfo) {
        Log.i(TAG, "NSD service registered as: " + serviceInfo.getServiceName());
        Log.d(TAG, serviceInfo.toString());
    }

    @Override
    public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
        Log.e(TAG, "NSD registration failed: " + errorCode);
        Log.e(TAG, serviceInfo.toString());
    }

    @Override
    public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
        Log.i(TAG, "NSD service unregistered");
        Log.d(TAG, serviceInfo.toString());
    }

    @Override
    public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
        Log.e(TAG, "NSD unregistration failed: " + errorCode);
        Log.e(TAG, serviceInfo.toString());
    }

    private static String findDeviceName(Context context) {
        String hostname = Settings.Global.getString(context.getContentResolver(), "device_name");
        if (hostname != null) return hostname;

        Log.d(TAG, "no device_name, falling back to app name");
        return context.getString(R.string.app_name);
    }

    public static ServiceAdvertiser createReceiverAdvertiser(Context context, NsdManager nsdManager, int port) {
        NsdServiceInfo serviceInfo = new NsdServiceInfo();

        serviceInfo.setServiceName(findDeviceName(context));
        serviceInfo.setServiceType(MDNS_SERVICE_TYPE);
        serviceInfo.setPort(port);

        return new ServiceAdvertiser(nsdManager, serviceInfo);
    }

}
