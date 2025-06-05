package io.benwiegand.atvremote.receiver.stuff;

import java.util.concurrent.atomic.AtomicInteger;

public class SerialInt {
    private final AtomicInteger currentSerial = new AtomicInteger(0);


    /**
     * increases serial by 1, invalidating previous serial.
     * @return the new serial
     */
    public int advance() {
        // roll over from MAX_VALUE to MIN_VALUE
        return currentSerial.updateAndGet(i -> i == Integer.MAX_VALUE ? Integer.MIN_VALUE : i+1);
    }

    /**
     * gets the current serial
     * @return the current serial
     */
    public int get() {
        return currentSerial.get();
    }

    /**
     * check whether the provided serial is still valid
     * @param serial serial to check
     * @return true if that serial is valid
     */
    public boolean isValid(int serial) {
        return get() == serial;
    }
}
