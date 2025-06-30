package io.benwiegand.atvremote.receiver.control;

import static android.media.MediaMetadata.METADATA_KEY_ALBUM;
import static android.media.MediaMetadata.METADATA_KEY_ARTIST;
import static android.media.MediaMetadata.METADATA_KEY_AUTHOR;
import static android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE;
import static android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE;
import static android.media.MediaMetadata.METADATA_KEY_DURATION;
import static android.media.MediaMetadata.METADATA_KEY_TITLE;
import static android.view.KeyEvent.KEYCODE_MEDIA_NEXT;
import static android.view.KeyEvent.KEYCODE_MEDIA_PAUSE;
import static android.view.KeyEvent.KEYCODE_MEDIA_PLAY;
import static android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
import static android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS;

import static io.benwiegand.atvremote.receiver.protocol.ProtocolConstants.EVENT_TYPE_MEDIA_METADATA;
import static io.benwiegand.atvremote.receiver.protocol.ProtocolConstants.EVENT_TYPE_MEDIA_POSITION;
import static io.benwiegand.atvremote.receiver.protocol.ProtocolConstants.EVENT_TYPE_MEDIA_SESSIONS;
import static io.benwiegand.atvremote.receiver.protocol.ProtocolConstants.EVENT_TYPE_MEDIA_STATE;
import static io.benwiegand.atvremote.receiver.util.PackageUtil.getAppName;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;

import androidx.annotation.Nullable;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.benwiegand.atvremote.receiver.R;
import io.benwiegand.atvremote.receiver.control.input.MediaInput;
import io.benwiegand.atvremote.receiver.protocol.KeyEventType;
import io.benwiegand.atvremote.receiver.protocol.RemoteProtocolException;
import io.benwiegand.atvremote.receiver.protocol.json.MediaMetaEvent;
import io.benwiegand.atvremote.receiver.protocol.json.MediaPositionEvent;
import io.benwiegand.atvremote.receiver.protocol.json.MediaSessionsEvent;
import io.benwiegand.atvremote.receiver.protocol.json.MediaStateEvent;
import io.benwiegand.atvremote.receiver.protocol.stream.EventStreamManager;
import io.benwiegand.atvremote.receiver.protocol.stream.OutgoingStateEventStream;
import io.benwiegand.atvremote.receiver.stuff.AnonymousUUIDTranslator;
import io.benwiegand.atvremote.receiver.stuff.FakeKeyDownUpHandler;

public class NotificationInputService extends NotificationListenerService {
    private static final String TAG = NotificationInputService.class.getSimpleName();
    private static final boolean DEBUG_LOGS = false;

    private static final long SEEK_POSITION_POLL_INTERVAL = 250;
    private static final int PLAYBACK_STATE_NULL = -0xdeadbeef;

    private static final Gson gson = new Gson();

    private final IBinder binder = new ServiceBinder();
    private final MediaInput mediaInput = new MediaInputHandler();

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Object activeSessionsLock = new Object();
    private final AnonymousUUIDTranslator<MediaSession.Token> tokenAnonymizer = new AnonymousUUIDTranslator<>();
    private final Map<UUID, MediaController> activeSessionMap = new ConcurrentHashMap<>();
    private List<UUID> activeSessionRanking = Collections.emptyList();

    private OutgoingStateEventStream mediaSessionsEventStream = null;
    private OutgoingStateEventStream mediaMetadataEventStream = null;
    private OutgoingStateEventStream mediaPositionEventStream = null;
    private OutgoingStateEventStream mediaStateEventStream = null;

    private ComponentName getComponentName() {
        return new ComponentName(this, NotificationInputService.class);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.v(TAG, "notification listener connected");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.v(TAG, "notification listener disconnected");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void sendMediaSessionsUpdateLocked(List<UUID> sessionRanking) {
        String[] ids = sessionRanking.stream()
                .map(UUID::toString)
                .toArray(String[]::new);
        mediaSessionsEventStream.sendEvent(null,
                gson.toJson(new MediaSessionsEvent(ids)));
    }

    private void sendMediaMetaUpdate(UUID uuid, String packageName, MediaMetadata metadata) {
        String appName = getAppName(this, packageName);

        if (metadata == null) {
            // no media, but the session still exists
            mediaMetadataEventStream.sendEvent(uuid,
                    gson.toJson(new MediaMetaEvent(uuid.toString(), appName)));
            return;
        }

        // try DISPLAY_TITLE, TITLE, and ALBUM
        String title = (String) metadata.getText(METADATA_KEY_DISPLAY_TITLE);
        if (title == null) title = metadata.getString(METADATA_KEY_TITLE);
        if (title == null) title = metadata.getString(METADATA_KEY_ALBUM);

        // try DISPLAY_SUBTITLE, ARTIST, and AUTHOR
        String subtitle = (String) metadata.getText(METADATA_KEY_DISPLAY_SUBTITLE);
        if (subtitle == null) subtitle = metadata.getString(METADATA_KEY_ARTIST);
        if (subtitle == null) subtitle = metadata.getString(METADATA_KEY_AUTHOR);

        Long duration = metadata.getLong(METADATA_KEY_DURATION);
        if (duration < 0) duration = null;

        mediaMetadataEventStream.sendEvent(uuid,
                gson.toJson(new MediaMetaEvent(
                        uuid.toString(), title, subtitle,
                        appName, duration)));
    }

    private void sendMediaPositionUpdate(UUID uuid, PlaybackState playbackState) {
        if (playbackState == null || playbackState.getPosition() < 0) {
            // no play head, but the session still exists
            mediaPositionEventStream.sendEvent(uuid,
                    gson.toJson(new MediaPositionEvent(uuid.toString())));
            return;
        }

        Long bufferedPosition = playbackState.getBufferedPosition();
        if (bufferedPosition <= 0) bufferedPosition = null;

        mediaPositionEventStream.sendEvent(uuid,
                gson.toJson(new MediaPositionEvent(uuid.toString(), playbackState.getPosition(), bufferedPosition)));
    }

    private void sendMediaStateUpdate(UUID uuid, PlaybackState playbackState) {
        if (playbackState == null) {
            // no state, but the session still exists
            mediaStateEventStream.sendEvent(uuid,
                    gson.toJson(new MediaStateEvent(uuid.toString())));
            return;
        }

        // I don't trust apps to follow the conventions of PlaybackState.isActive(), so I'm
        // separating play/pause into 2 booleans (playing, paused):
        // - true, false -> definitely playing
        // - false, true -> definitely paused
        // - false, false -> assume the previous state
        // - true, true -> run for your life
        @SuppressLint("SwitchIntDef") boolean[] playPause = switch (playbackState.getState()) {

            // playing (not paused)
            case PlaybackState.STATE_PLAYING -> new boolean[] {true, false};

            // paused (not playing)
            case PlaybackState.STATE_PAUSED,
                 PlaybackState.STATE_STOPPED -> new boolean[] {false, true};

            // Schrodinger's playback state
            case PlaybackState.STATE_NONE,
                 PlaybackState.STATE_FAST_FORWARDING,
                 PlaybackState.STATE_REWINDING,
                 PlaybackState.STATE_BUFFERING,
                 PlaybackState.STATE_ERROR,
                 PlaybackState.STATE_CONNECTING,
                 PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
                 PlaybackState.STATE_SKIPPING_TO_NEXT,
                 PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
                 12 /* STATE_PLAYBACK_SUPPRESSED (hidden) */ ->
                    new boolean[] {false, false};

            default -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    yield playbackState.isActive() ? new boolean[] {true, false} : new boolean[] {false, true};
                yield new boolean[] {false, false};
            }
        };

        mediaStateEventStream.sendEvent(uuid,
                gson.toJson(new MediaStateEvent(
                        uuid.toString(), playbackState.getState(), playPause[0], playPause[1])));
    }

    private boolean playbackStatesEqual(PlaybackState firstState, PlaybackState secondState) {
        if (firstState == secondState) return true;
        if (firstState == null) return false;
        if (secondState == null) return false;

        // not concerned about app-specific values for now
        return firstState.getState() == secondState.getState()
                && firstState.getPosition() == secondState.getPosition()
                && firstState.getActions() == secondState.getActions()
                && firstState.getBufferedPosition() == secondState.getBufferedPosition()
                && firstState.getLastPositionUpdateTime() == secondState.getLastPositionUpdateTime()
                && firstState.getPlaybackSpeed() == secondState.getPlaybackSpeed()
                && firstState.getErrorMessage() == secondState.getErrorMessage();

    }

    private void setupMediaControllerCallback(UUID uuid, MediaController mediaController) {
        AtomicInteger state = new AtomicInteger();
        MediaController.Callback callback = new MediaController.Callback() {
            @Override
            public void onPlaybackStateChanged(@Nullable PlaybackState playbackState) {
                super.onPlaybackStateChanged(playbackState);
                if (DEBUG_LOGS) Log.d(TAG, "playback state changed: " + playbackState);

                int currentState = playbackState == null ? PLAYBACK_STATE_NULL : playbackState.getState();
                boolean newState = currentState != state.getAndSet(currentState);

                synchronized (activeSessionsLock) {
                    if (!activeSessionMap.containsKey(uuid)) return;
                    if (newState) sendMediaStateUpdate(uuid, playbackState);
                    sendMediaPositionUpdate(uuid, playbackState);
                }
            }

            @Override
            public void onMetadataChanged(@Nullable MediaMetadata metadata) {
                super.onMetadataChanged(metadata);
                if (DEBUG_LOGS) Log.d(TAG, "metadata changed: " + metadata);
                synchronized (activeSessionsLock) {
                    if (!activeSessionMap.containsKey(uuid)) return;
                    sendMediaMetaUpdate(uuid, mediaController.getPackageName(), metadata);
                }
            }
        };

        callback.onPlaybackStateChanged(mediaController.getPlaybackState());
        callback.onMetadataChanged(mediaController.getMetadata());
        mediaController.registerCallback(callback);

        // poll for position updates (some apps don't give real-time updates)
        AtomicReference<PlaybackState> oldPlaybackStateRef = new AtomicReference<>(null);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!activeSessionMap.containsKey(uuid)) return;
                PlaybackState newPlaybackState = mediaController.getPlaybackState();
                PlaybackState oldPlaybackState = oldPlaybackStateRef.getAndSet(newPlaybackState);

                if (!playbackStatesEqual(oldPlaybackState, newPlaybackState))
                    callback.onPlaybackStateChanged(mediaController.getPlaybackState());

                handler.postDelayed(this, SEEK_POSITION_POLL_INTERVAL);
            }
        }, SEEK_POSITION_POLL_INTERVAL);

    }

    private void onActiveSessionsChanged(@Nullable List<MediaController> mediaControllers) {
        if (mediaControllers == null)
            mediaControllers = Collections.emptyList();

        synchronized (activeSessionsLock) {
            if (DEBUG_LOGS) Log.d(TAG, "onActiveSessionsChanged(): " + mediaControllers.size() + " active sessions");

            List<UUID> newActiveSessionRanking = new ArrayList<>(mediaControllers.size());
            Set<UUID> oldKeys = new HashSet<>(activeSessionMap.keySet());
            List<Pair<UUID, MediaController>> newSessions = new LinkedList<>();

            for (MediaController session : mediaControllers) {
                UUID uuid = tokenAnonymizer.getUUIDOrRegister(session.getSessionToken());
                boolean isNew = !oldKeys.remove(uuid);
                activeSessionMap.put(uuid, session);
                newActiveSessionRanking.add(uuid);

                if (isNew)
                    newSessions.add(Pair.create(uuid, session));

            }

            // new active session list must be sent before any updates
            if (DEBUG_LOGS) Log.i(TAG, "sending active sessions ranking: " + newActiveSessionRanking);
            sendMediaSessionsUpdateLocked(newActiveSessionRanking);
            activeSessionRanking = newActiveSessionRanking;

            for (Pair<UUID, MediaController> newSession: newSessions) {
                UUID uuid = newSession.first;
                MediaController session = newSession.second;

                Log.d(TAG, "session added: " + uuid + " " + session.getPackageName());
                mediaMetadataEventStream.addChannel(uuid);
                mediaPositionEventStream.addChannel(uuid);
                mediaStateEventStream.addChannel(uuid);
                setupMediaControllerCallback(uuid, session);
            }

            for (UUID uuid : oldKeys) {
                MediaController oldSession = activeSessionMap.remove(uuid);
                assert oldSession != null;

                Log.d(TAG, "session removed: " + uuid + " " + oldSession.getPackageName());
                tokenAnonymizer.unregister(oldSession.getSessionToken());
                mediaMetadataEventStream.removeChannel(uuid);
                mediaPositionEventStream.removeChannel(uuid);
                mediaStateEventStream.removeChannel(uuid);
            }

        }
    }

    private Optional<MediaController> getPrimaryMediaSession() {
        synchronized (activeSessionsLock) {
            if (activeSessionRanking.isEmpty()) return Optional.empty();
            UUID uuid = activeSessionRanking.get(0);
            MediaController session = activeSessionMap.get(uuid);
            assert session != null;
            return Optional.of(session);
        }
    }

    private boolean sendKeyPress(MediaController mediaController, KeyEventType type, int keyCode) {
        if (type == KeyEventType.CLICK || type == KeyEventType.DOWN) {
            if (!mediaController.dispatchMediaButtonEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode))) {
                Log.e(TAG, "media key down failed");
                return false;
            }
        }

        if (type == KeyEventType.CLICK || type == KeyEventType.UP) {
            if (!mediaController.dispatchMediaButtonEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode))) {
                Log.e(TAG, "media key up failed");
                return false;
            }
        }

        return true;
    }

    // some apps handle media session key events differently and undesirably, so key up/down events
    // aren't directly forwarded for all keys
    public class MediaInputHandler implements MediaInput {
        public void simulateButton(KeyEventType type, int keyCode) {
            getPrimaryMediaSession().ifPresent(mediaController -> {
                if (sendKeyPress(mediaController, type, keyCode)) return;
                Log.wtf(TAG, "media key press failed");
                // I'm not sure what exactly would cause this, but inform the user that it didn't work
                throw new RemoteProtocolException(R.string.protocol_error_media_button_failed, "media key event returned false");
            });
        }

        private final FakeKeyDownUpHandler fakePlayPauseButtonHandler = new FakeKeyDownUpHandler(
                () -> simulateButton(KeyEventType.CLICK, KEYCODE_MEDIA_PLAY_PAUSE),
                true);

        @Override
        public void pause(KeyEventType type) {
            simulateButton(type, KEYCODE_MEDIA_PAUSE);
        }

        @Override
        public void play(KeyEventType type) {
            simulateButton(type, KEYCODE_MEDIA_PLAY);
        }

        @Override
        public void playPause(KeyEventType type) {
            fakePlayPauseButtonHandler.onKeyEvent(type);
        }

        @Override
        public void nextTrack(KeyEventType type) {
            if (type == KeyEventType.DOWN) return;
            simulateButton(KeyEventType.CLICK, KEYCODE_MEDIA_NEXT);
        }

        @Override
        public void prevTrack(KeyEventType type) {
            if (type == KeyEventType.DOWN) return;
            simulateButton(KeyEventType.CLICK, KEYCODE_MEDIA_PREVIOUS);
        }

        @Override
        public void skipBackward(KeyEventType type) {
            if (type == KeyEventType.UP) return;
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
        public void skipForward(KeyEventType type) {
            if (type == KeyEventType.UP) return;
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
    }

    public class ServiceBinder extends Binder {

        public MediaInput getMediaInput() {
            return mediaInput;
        }

        public void onServerBind(EventStreamManager eventStreamManager) {
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
