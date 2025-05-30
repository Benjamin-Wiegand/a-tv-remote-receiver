package io.benwiegand.atvremote.receiver.control.input;

import android.content.ComponentName;

public interface ActivityLauncherInput extends InputHandler {
    void launchActivity(ComponentName activityComponent);
}
