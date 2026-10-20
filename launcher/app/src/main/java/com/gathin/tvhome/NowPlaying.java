package com.gathin.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

/**
 * The Now Playing widget above Jump Back In: whatever an app plays in the background (music, mostly), with its
 * artwork, title, artist and play/pause, previous and next. It follows the app that is playing; one paused from
 * here stays on show so it can be resumed. Uses MediaListener's notification access to see the sessions, and
 * runs only while Home is in front.
 */
final class NowPlaying {

    interface Listener {
        void onNowPlayingChanged();
    }

    private final Activity activity;
    private final View root;
    private final ImageView art, play;
    private final TextView source, title, artist;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ComponentName access;
    private MediaSessionManager sessions;
    private MediaController current;
    private boolean started;
    private String artKey;

    private final MediaSessionManager.OnActiveSessionsChangedListener changed = list -> pick();
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { update(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { pick(); }
        @Override public void onSessionDestroyed() { pick(); }
    };

    NowPlaying(Activity activity, View root, Listener listener) {
        this.activity = activity;
        this.root = root;
        this.listener = listener;
        access = new ComponentName(activity, MediaListener.class);
        art = root.findViewById(R.id.npArt);
        art.setClipToOutline(true);
        play = root.findViewById(R.id.npPlay);
        source = root.findViewById(R.id.npSource);
        title = root.findViewById(R.id.npTitle);
        artist = root.findViewById(R.id.npArtist);
        root.findViewById(R.id.npPrev).setOnClickListener(v -> {
            if (current != null) current.getTransportControls().skipToPrevious();
        });
        root.findViewById(R.id.npNext).setOnClickListener(v -> {
            if (current != null) current.getTransportControls().skipToNext();
        });
        play.setOnClickListener(v -> {
            if (current == null) return;
            if (isPlaying(current)) current.getTransportControls().pause();
            else current.getTransportControls().play();
        });
    }

    boolean visible() {
        return root.getVisibility() == View.VISIBLE;
    }

    /** Focus for the remote: the play/pause button. */
    boolean focus() {
        return visible() && play.requestFocus();
    }

    void start() {
        if (started) return;
        sessions = activity.getSystemService(MediaSessionManager.class);
        if (sessions == null) return;
        try {
            sessions.addOnActiveSessionsChangedListener(changed, access, handler);
        } catch (SecurityException e) {
            return;   // no notification access: no widget
        }
        started = true;
        pick();
    }

    void stop() {
        if (!started) return;
        started = false;
        sessions.removeOnActiveSessionsChangedListener(changed);
        follow(null);
    }

    /** The app playing now; else the one already shown, if it is only paused; else none. */
    private void pick() {
        if (!started) return;
        List<MediaController> list;
        try {
            list = sessions.getActiveSessions(access);
        } catch (SecurityException e) {
            list = Collections.emptyList();
        }
        MediaController chosen = null;
        for (MediaController c : list) {
            if (isPlaying(c) && hasTitle(c) && !c.getPackageName().equals(activity.getPackageName())) {
                chosen = c;
                break;
            }
        }
        if (chosen == null && current != null) {
            for (MediaController c : list) {
                if (c.getSessionToken().equals(current.getSessionToken()) && hasTitle(c) && isPaused(c)) chosen = c;
            }
        }
        follow(chosen);
        update();
    }

    private void follow(MediaController c) {
        if (current != null && c != null && current.getSessionToken().equals(c.getSessionToken())) return;
        if (current != null) current.unregisterCallback(callback);
        current = c;
        if (c != null) c.registerCallback(callback, handler);
        if (c == null) update();
    }

    private void update() {
        boolean was = visible();
        MediaMetadata md = current != null ? current.getMetadata() : null;
        if (current == null || md == null || !hasTitle(current)) {
            if (root.hasFocus()) root.getRootView().findViewById(R.id.grid).requestFocus();
            root.setVisibility(View.GONE);
            artKey = null;
            if (was) listener.onNowPlayingChanged();
            return;
        }
        String app = appLabel(current.getPackageName());
        boolean playing = isPlaying(current);
        source.setText(activity.getString(playing ? R.string.np_now_playing : R.string.np_paused, app));
        title.setText(md.getText(MediaMetadata.METADATA_KEY_TITLE) != null
                ? md.getText(MediaMetadata.METADATA_KEY_TITLE) : md.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
        CharSequence by = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (by == null) by = md.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        artist.setText(by);
        artist.setVisibility(by != null && by.length() > 0 ? View.VISIBLE : View.GONE);
        play.setImageResource(playing ? R.drawable.ic_np_pause : R.drawable.ic_np_play);
        showArt(md);
        root.setVisibility(View.VISIBLE);
        if (!was) listener.onNowPlayingChanged();
    }

    private void showArt(MediaMetadata md) {
        String url = RecentMedia.artUrl(md);
        Bitmap bmp = url == null ? RecentMedia.artBitmap(md) : null;
        String key = url != null ? url : String.valueOf(md.getText(MediaMetadata.METADATA_KEY_TITLE));
        if (key.equals(artKey) && url != null) return;
        artKey = key;
        if (bmp != null) {
            art.setImageBitmap(bmp);
            return;
        }
        if (url == null) {
            try {
                art.setImageDrawable(activity.getPackageManager().getApplicationIcon(current.getPackageName()));
            } catch (PackageManager.NameNotFoundException e) {
                art.setImageDrawable(null);
            }
            return;
        }
        int size = art.getLayoutParams().width;
        Shelf.EXEC.execute(() -> {
            Bitmap loaded = Shelf.loadArt(activity.getApplicationContext(), url, size);
            handler.post(() -> {
                if (url.equals(artKey) && loaded != null) art.setImageBitmap(loaded);
            });
        });
    }

    private String appLabel(String pkg) {
        PackageManager pm = activity.getPackageManager();
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static boolean isPlaying(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING);
    }

    private static boolean isPaused(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s != null && s.getState() == PlaybackState.STATE_PAUSED;
    }

    private static boolean hasTitle(MediaController c) {
        MediaMetadata md = c.getMetadata();
        if (md == null) return false;
        CharSequence t = md.getText(MediaMetadata.METADATA_KEY_TITLE);
        if (t == null) t = md.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        return t != null && t.toString().trim().length() > 0;
    }
}
