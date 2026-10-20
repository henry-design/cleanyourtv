package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.VideoView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Muted aerial loops behind Home (and in the screensaver). Clips live in the launcher's own files/backgrounds;
 * anything pushed to the shared Movies/TvHome folder is copied in first, so the Theme app can delete it later.
 *
 * Each clip loops until the chosen interval is up (or plays once, or never changes), and every switch dips
 * through black instead of flashing a blank frame. Playback runs only while its screen is in front.
 */
final class VideoBackground {

    private static final long FADE_IN_MS = 700, FADE_OUT_MS = 500, END_LEAD_MS = 650, POLL_MS = 250;
    private static final String IMPORTED = "bg_imported";

    private final Context ctx;
    private final VideoView view;
    private final View scrim, fade;
    private final boolean saver;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private List<File> clips = new ArrayList<>();
    private int index;
    private int interval;
    private boolean shuffle;
    private int failures;
    private boolean playing, switching;
    private MediaPlayer player;

    private final Runnable timedSwitch = () -> switchTo(nextIndex());
    private final Runnable endWatch = new Runnable() {
        @Override
        public void run() {
            if (!playing) return;
            try {
                int dur = view.getDuration(), pos = view.getCurrentPosition();
                if (dur > 0 && pos > 0 && dur - pos <= END_LEAD_MS) {
                    switchTo(nextIndex());
                    return;
                }
            } catch (IllegalStateException ignored) {
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    /** {@code saver}: the screensaver's own rotation (a new clip after each one, shuffled, nothing saved). */
    VideoBackground(Context ctx, VideoView view, View scrim, View fade, boolean saver) {
        this.ctx = ctx;
        this.view = view;
        this.scrim = scrim;
        this.fade = fade;
        this.saver = saver;
        view.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE);   // never pause the music someone is playing
        view.setOnPreparedListener(mp -> {
            player = mp;
            mp.setVolume(0f, 0f);
            // Clips are encoded as seamless loops, so repeating one is invisible; "after each clip" plays it once.
            mp.setLooping(interval != 0 || clips.size() == 1);
            failures = 0;
        });
        view.setOnInfoListener((mp, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                switching = false;
                fade.animate().cancel();
                fade.animate().alpha(0f).setDuration(FADE_IN_MS).start();
            }
            return false;
        });
        view.setOnCompletionListener(mp -> {
            if (playing && !switching) switchTo(nextIndex());
        });
        view.setOnErrorListener((mp, what, extra) -> {
            switching = false;
            if (++failures >= clips.size()) {
                stop();
                hide();
            } else {
                index = nextIndex();
                play();
            }
            return true;
        });
    }

    void start() {
        if (playing) return;
        if (!saver && !ThemePrefs.bgEnabled(ctx)) {
            hide();
            return;
        }
        clips = list(ctx);
        if (clips.isEmpty()) {
            hide();
            return;
        }
        SharedPreferences prefs = ThemePrefs.get(ctx);
        interval = saver ? 0 : ThemePrefs.bgInterval(ctx);
        shuffle = saver || ThemePrefs.bgShuffle(ctx);
        index = saver ? random.nextInt(clips.size()) : indexOf(prefs.getString(ThemePrefs.BG_CURRENT, null));
        long elapsed = System.currentTimeMillis() - prefs.getLong(ThemePrefs.BG_CHANGED_AT, 0);
        if (!saver) {
            // Back on Home: a new clip once the interval is up ("after each clip": every visit, but not right
            // after one was picked in the Theme app).
            boolean due = interval == 0 ? elapsed > 10_000 : interval > 0 && elapsed >= interval * 1000L;
            if (index < 0 || due) {
                index = index < 0 ? 0 : nextIndex();
                remember();
                elapsed = 0;
            }
        }
        if (index < 0) index = 0;
        failures = 0;
        view.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        if (!saver) scrim.setAlpha(ThemePrefs.DIM_ALPHA[ThemePrefs.bgDim(ctx)]);
        fade.setVisibility(View.VISIBLE);
        fade.setAlpha(1f);
        playing = true;
        play();
        if (interval > 0) handler.postDelayed(timedSwitch, Math.max(1000L, interval * 1000L - elapsed));
    }

    void stop() {
        if (!playing) return;
        playing = false;
        switching = false;
        handler.removeCallbacksAndMessages(null);
        fade.animate().cancel();
        fade.setAlpha(1f);
        player = null;
        view.stopPlayback();
    }

    /** Plays this clip now (Theme app: "Show now"), keeping it as the current wallpaper. */
    void show(File clip) {
        int i = clips.indexOf(clip);
        if (i < 0 || !playing) return;
        switchTo(i);
    }

    private void play() {
        view.setVideoPath(clips.get(index).getAbsolutePath());
        view.start();
        handler.removeCallbacks(endWatch);
        if (interval == 0 && clips.size() > 1) handler.postDelayed(endWatch, POLL_MS);
    }

    private void switchTo(int next) {
        if (!playing || switching || clips.isEmpty()) return;
        switching = true;
        handler.removeCallbacks(timedSwitch);
        handler.removeCallbacks(endWatch);
        fade.animate().cancel();
        fade.animate().alpha(1f).setDuration(FADE_OUT_MS).withEndAction(() -> {
            if (!playing) return;
            index = next;
            remember();
            play();
            if (interval > 0) handler.postDelayed(timedSwitch, interval * 1000L);
        }).start();
    }

    private int nextIndex() {
        int n = clips.size();
        if (n <= 1) return 0;
        if (!shuffle) return (Math.max(index, -1) + 1) % n;
        int r;
        do r = random.nextInt(n); while (r == index);
        return r;
    }

    private int indexOf(String name) {
        if (name == null) return -1;
        for (int i = 0; i < clips.size(); i++) if (clips.get(i).getName().equals(name)) return i;
        return -1;
    }

    private void remember() {
        if (saver || index < 0 || index >= clips.size()) return;
        ThemePrefs.get(ctx).edit().putString(ThemePrefs.BG_CURRENT, clips.get(index).getName())
                .putLong(ThemePrefs.BG_CHANGED_AT, System.currentTimeMillis()).apply();
    }

    private void hide() {
        view.setVisibility(View.GONE);
        scrim.setVisibility(View.GONE);
        fade.setVisibility(View.GONE);
    }

    // ---- the clip folder -------------------------------------------------------------------------

    static File ownDir(Context ctx) {
        return ctx.getExternalFilesDir("backgrounds");
    }

    static File sharedDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "TvHome");
    }

    static List<File> list(Context ctx) {
        List<File> out = new ArrayList<>();
        File dir = ownDir(ctx);
        File[] files = dir != null ? dir.listFiles() : null;
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) if (isClip(f)) out.add(f);
        return out;
    }

    static boolean isClip(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return f.isFile() && f.length() > 0 && (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm"));
    }

    /**
     * Copies new clips from Movies/TvHome into the launcher's own folder (off the main thread). A source the
     * launcher may not delete (pushed by adb) is remembered, so a clip deleted in the Theme app stays deleted.
     */
    static int importShared(Context ctx) {
        File[] files = sharedDir().listFiles();
        File dst = ownDir(ctx);
        if (files == null || dst == null) return 0;
        SharedPreferences prefs = ThemePrefs.get(ctx);
        Set<String> done = new HashSet<>(prefs.getStringSet(IMPORTED, new HashSet<>()));
        int count = 0;
        for (File f : files) {
            if (!isClip(f)) continue;
            String key = f.getName() + ":" + f.length();
            if (done.contains(key)) continue;
            File out = new File(dst, f.getName());
            File tmp = new File(dst, f.getName() + ".part");
            try (InputStream in = new FileInputStream(f); OutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            } catch (IOException e) {
                tmp.delete();
                continue;
            }
            if (!tmp.renameTo(out)) {
                tmp.delete();
                continue;
            }
            count++;
            if (!f.delete()) done.add(key);
        }
        prefs.edit().putStringSet(IMPORTED, done).apply();
        return count;
    }
}
