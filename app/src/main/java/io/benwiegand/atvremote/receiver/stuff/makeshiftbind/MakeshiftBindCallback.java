package io.benwiegand.atvremote.receiver.stuff.makeshiftbind;

import android.content.Intent;
import android.os.IBinder;

public interface MakeshiftBindCallback {
    IBinder onMakeshiftBind(Intent intent);
}
