package io.benwiegand.atvremote.receiver.control;

import static android.view.KeyEvent.KEYCODE_MEDIA_NEXT;
import static android.view.KeyEvent.KEYCODE_MEDIA_PAUSE;
import static android.view.KeyEvent.KEYCODE_MEDIA_PLAY;
import static android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
import static android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS;

import static io.benwiegand.atvremote.receiver.protocol.ProtocolConstants.EVENT_TYPE_MEDIA_METADATA;
import static io.benwiegand.atvremote.receiver.protocol.ProtocolConstants.EVENT_TYPE_MEDIA_POSITION;
import static io.benwiegand.atvremote.receiver.protocol.ProtocolConstants.EVENT_TYPE_MEDIA_SESSIONS;
import static io.benwiegand.atvremote.receiver.protocol.ProtocolConstants.EVENT_TYPE_MEDIA_STATE;

import android.content.ComponentName;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Binder;
import android.os.IBinder;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.Nullable;

import com.google.gson.Gson;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.control.input.MediaInput;
import io.benwiegand.atvremote.receiver.protocol.RemoteProtocolException;
import io.benwiegand.atvremote.receiver.protocol.json.MediaSessionsEvent;
import io.benwiegand.atvremote.receiver.protocol.stream.EventStreamManager;
import io.benwiegand.atvremote.receiver.protocol.stream.OutgoingStateEventStream;

public class NotificationInputService extends NotificationListenerService {
    private static final String TAG = NotificationInputService.class.getSimpleName();

    private static final Gson gson = new Gson();

    private final IBinder binder = new ServiceBinder();
    private final MediaInput mediaInput = new MediaInputHandler();

    private final Object activeSessionsLock = new Object();
    private List<MediaController> activeSessions = Collections.emptyList();

    private OutgoingStateEventStream mediaSessionsEventStream = null;
    private OutgoingStateEventStream mediaMetadataEventStream = null;
    private OutgoingStateEventStream mediaPositionEventStream = null;
    private OutgoingStateEventStream mediaStateEventStream = null;

    private ComponentName getComponentName() {
        return new ComponentName(this, NotificationInputService.class);
    }

    private void onActiveSessionsChanged(@Nullable List<MediaController> mediaControllers) {
        if (mediaControllers == null)
            mediaControllers = Collections.emptyList();

        Log.d(TAG, "onActiveSessionsChanged(): " + mediaControllers.size() + " active sessions");
        synchronized (activeSessionsLock) {
            activeSessions = mediaControllers;
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "onListenerConnected()");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.d(TAG, "onListenerDisconnected()");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private Optional<MediaController> getPrimaryMediaSession() {
        synchronized (activeSessionsLock) {
            if (activeSessions.isEmpty()) return Optional.empty();
            return Optional.of(activeSessions.get(0));
        }
    }

    private boolean sendKeyPress(MediaController mediaController, int keyCode) {
        boolean down = mediaController.dispatchMediaButtonEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        boolean up = mediaController.dispatchMediaButtonEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));

        if (down && !up) Log.w(TAG, "ACTION_DOWN sent, but ACTION_UP failed");
        return down;
    }

    public class MediaInputHandler implements MediaInput {

        public void simulateButton(int keyCode) {
            getPrimaryMediaSession().ifPresent(mediaController -> {
                if (sendKeyPress(mediaController, keyCode)) return;
                Log.wtf(TAG, "media key press failed");
                // I'm not sure what exactly would cause this, but inform the user that it didn't work
                throw new RemoteProtocolException(R.string.protocol_error_media_button_failed, "media key event returned false");
            });
        }

        @Override
        public void pause() {
            simulateButton(KEYCODE_MEDIA_PAUSE);
        }

        @Override
        public void play() {
            simulateButton(KEYCODE_MEDIA_PLAY);
        }

        @Override
        public void playPause() {
            simulateButton(KEYCODE_MEDIA_PLAY_PAUSE);
        }

        @Override
        public void nextTrack() {
            simulateButton(KEYCODE_MEDIA_NEXT);
        }

        @Override
        public void prevTrack() {
            simulateButton(KEYCODE_MEDIA_PREVIOUS);
        }

        @Override
        public void skipBackward() {
//            simulateButton(KEYCODE_MEDIA_REWIND);
            getPrimaryMediaSession().ifPresent(session -> {
                if (session.getPlaybackState() == null) {
                    session.getTransportControls().rewind();
                    return;
                }
                long pos = session.getPlaybackState().getPosition();
                long targetPos = pos - 10000;
                if (targetPos < 0) targetPos = 0;
                session.getTransportControls().seekTo(targetPos);
            });
        }

        @Override
        public void skipForward() {
//            simulateButton(KEYCODE_MEDIA_FAST_FORWARD);
            getPrimaryMediaSession().ifPresent(session -> {
                if (session.getPlaybackState() == null) {
                    session.getTransportControls().fastForward();
                    return;
                }
                long pos = session.getPlaybackState().getPosition();
                long targetPos = pos + 10000;
                session.getTransportControls().seekTo(targetPos);
            });
        }

        @Override
        public void destroy() {

        }
    }

    public class ServiceBinder extends Binder {

        public MediaInput getMediaInput() {
            return mediaInput;
        }

        public void onServerBind(EventStreamManager eventStreamManager) {
            Log.d(TAG, "onServerBind()");
            mediaSessionsEventStream = eventStreamManager.getOrCreateStateEventStream(EVENT_TYPE_MEDIA_SESSIONS);
            mediaMetadataEventStream = eventStreamManager.getOrCreateStateEventStream(EVENT_TYPE_MEDIA_METADATA);
            mediaPositionEventStream = eventStreamManager.getOrCreateStateEventStream(EVENT_TYPE_MEDIA_POSITION);
            mediaStateEventStream = eventStreamManager.getOrCreateStateEventStream(EVENT_TYPE_MEDIA_STATE);
            mediaSessionsEventStream.addChannel(null);

            MediaSessionManager mediaSessionManager = getSystemService(MediaSessionManager.class);
            mediaSessionManager.addOnActiveSessionsChangedListener(NotificationInputService.this::onActiveSessionsChanged, getComponentName());
            onActiveSessionsChanged(mediaSessionManager.getActiveSessions(getComponentName()));
        }

    }

}
