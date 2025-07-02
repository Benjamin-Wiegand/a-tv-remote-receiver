package io.benwiegand.atvremote.receiver.stuff;

/**
 * like SerialInt but lets you block for things.
 * this isn't built-in to SerialInt because it doesn't advance atomically.
 */
public class NotifyingSerialInt extends SerialInt {
    private final Object lock = new Object();

    @Override
    public int advance() {
        synchronized (lock) {
            int newSerial = super.advance();
            lock.notifyAll();
            return newSerial;
        }
    }

    /**
     * waits while the provided serial number is valid.
     * @param serial the serial number
     * @param timeout the maximum time to wait
     * @return true if the serial is still valid, false if not. true also implies the timeout was reached.
     */
    public boolean waitWhileValid(int serial, long timeout) throws InterruptedException {
        synchronized (lock) {
            if (!isValid(serial)) return false;
            lock.wait(timeout);
            return isValid(serial);
        }
    }
}
