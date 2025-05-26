package io.benwiegand.atvremote.receiver.async;

public interface SecAdapter<T> {

    void provideResult(T result);
    void throwError(Throwable t);

    record SecWithAdapter<T>(Sec<T> sec, SecAdapter<T> secAdapter) {}

    static <T> SecWithAdapter<T> createThreadless() {

        Sec<T> sec = new Sec<>();
        SecAdapter<T> adapter = sec.createAdapter();

        return new SecWithAdapter<>(sec, adapter);

    }
}
