package com.gathin.tvhome;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Notices what apps play, through their media sessions, so Jump Back In can show it. Android hands the session
 * list only to a notification listener, which is why this is one; it never reads or touches notifications.
 * Enabled once over adb: {@code cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.MediaListener}.
 */
public class MediaListener extends NotificationListenerService {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<MediaSession.Token, Tracked> tracked = new HashMap<>();
    private MediaSessionManager sessions;
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::onSessions;

    private final class Tracked extends MediaController.Callback {
        final MediaController controller;

        Tracked(MediaController controller) {
            this.controller = controller;
        }

        @Override public void onMetadataChanged(MediaMetadata metadata) { save(controller); }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            // Positions only matter at pauses, stops and seeks; steady playing is saved when it ends.
            if (state == null || state.getState() != PlaybackState.STATE_BUFFERING) save(controller);
        }

        @Override
        public void onSessionDestroyed() {
            save(controller);
            controller.unregisterCallback(this);
            tracked.remove(controller.getSessionToken());
        }
    }

    @Override
    public void onListenerConnected() {
        sessions = getSystemService(MediaSessionManager.class);
        if (sessions == null) return;
        ComponentName me = new ComponentName(this, MediaListener.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, me, handler);
            onSessions(sessions.getActiveSessions(me));
        } catch (SecurityException ignored) {   // listener access was revoked meanwhile
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        for (Tracked t : tracked.values()) {
            save(t.controller);
            t.controller.unregisterCallback(t);
        }
        tracked.clear();
    }

    private void onSessions(List<MediaController> controllers) {
        if (controllers == null) return;
        for (MediaController c : controllers) {
            if (tracked.containsKey(c.getSessionToken())) continue;
            Tracked t = new Tracked(c);
            tracked.put(c.getSessionToken(), t);
            c.registerCallback(t, handler);
            save(c);
        }
    }

    private void save(MediaController c) {
        String pkg = c.getPackageName();
        MediaMetadata md = c.getMetadata();
        PlaybackState state = c.getPlaybackState();
        if (md == null) return;
        Shelf.EXEC.execute(() -> RecentMedia.record(getApplicationContext(), pkg, md, state));
    }
}
