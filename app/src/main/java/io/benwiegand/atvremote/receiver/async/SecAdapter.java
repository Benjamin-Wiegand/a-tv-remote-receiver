package io.benwiegand.atvremote.receiver.async;

import android.os.Handler;
import android.util.Log;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import io.benwiegand.atvremote.receiver.stuff.ThrowingSupplier;

public interface SecAdapter<T> {
    String TAG = SecAdapter.class.getSimpleName();

    void provideResult(T result);
    void throwError(Throwable t);

    record SecWithAdapter<T>(Sec<T> sec, SecAdapter<T> secAdapter) {}

    static <T> SecWithAdapter<T> createThreadless() {

        Sec<T> sec = new Sec<>();
        SecAdapter<T> adapter = sec.createAdapter();

        return new SecWithAdapter<>(sec, adapter);

    }

    private static Executor handlerAsExecutor(Handler handler) {
        return runnable -> {
            if (!handler.post(runnable))
                throw new RejectedExecutionException("handler is dead");
        };
    }

    static <T> PendingSec<T> create(Executor executor, Consumer<SecAdapter<T>> deferredResult) {
        return new PendingSec<>(() -> {
            Sec<T> sec = new Sec<>();

            try {
                executor.execute(() -> {
                    SecAdapter<T> adapter = sec.createAdapter();
                    try {
                        deferredResult.accept(adapter);
                    } catch (Throwable t) {
                        // this situation should generally be avoided
                        Log.wtf("SecAdapter", "deferred sec handler threw!", t);
                        try {
                            // ensure sec at least gets finished
                            if (!sec.isFinished()) adapter.throwError(t);
                        } catch (Throwable ignored) {}

                        // this will crash the app anyway
                        throw t;
                    }
                });
            } catch (Throwable t) {
                return Sec.premeditatedError(t);
            }

            return sec;
        });

    }

    static <T> PendingSec<T> create(Handler handler, Consumer<SecAdapter<T>> deferredResult) {
        return create(handlerAsExecutor(handler), deferredResult);
    }

    static <T> PendingSec<T> createSimple(Executor executor, ThrowingSupplier<T> deferredResult) {
        return create(executor, adapter -> {
            T result;
            try {
                result = deferredResult.get();
            } catch (Throwable t) {
                adapter.throwError(t);
                return;
            }
            adapter.provideResult(result);
        });
    }

    static <T> PendingSec<T> createSimple(Handler handler, ThrowingSupplier<T> deferredResult) {
        return createSimple(handlerAsExecutor(handler), deferredResult);
    }
}
