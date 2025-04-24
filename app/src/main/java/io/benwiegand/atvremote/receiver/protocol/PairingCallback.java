package io.benwiegand.atvremote.receiver.protocol;

import java.util.concurrent.TimeUnit;

public interface PairingCallback {

    void cancel();
    void disablePairingForAWhile(TimeUnit timeUnit, long period);

}
