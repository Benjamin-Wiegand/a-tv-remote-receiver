package io.benwiegand.atvremote.receiver.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.LayoutRes;

public abstract class MakeshiftActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Context context;
    private final WindowManager wm;
    private final WindowManager.LayoutParams layoutParams;

    protected final View root;
    private boolean showing = false;

    protected MakeshiftActivity(Context context, @LayoutRes int layout, WindowManager.LayoutParams layoutParams) {
        this.context = context;
        this.wm = context.getSystemService(WindowManager.class);
        this.layoutParams = layoutParams;

        root = getLayoutInflater().inflate(layout, null);
    }


    public void start() {
        runOnUiThread(this::show);
    }

    public void destroy() {
        runOnUiThread(this::hide);
    }

    public void show() {
        if (showing) return;
        wm.addView(root, layoutParams);
        showing = true;
    }

    public void hide() {
        if (!showing) return;
        wm.removeView(root);
        showing = false;
    }

    protected void runOnUiThread(Runnable run) {
        if (handler.getLooper() == Looper.myLooper()) {
            run.run();
            return;
        }
        handler.post(run);
    }

    public Context getContext() {
        return context;
    }

    public LayoutInflater getLayoutInflater() {
        return LayoutInflater.from(context);
    }

    protected Handler getHandler() {
        return handler;
    }
}
