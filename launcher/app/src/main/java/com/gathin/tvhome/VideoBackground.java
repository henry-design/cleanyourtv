package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.view.View;
import android.widget.VideoView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Muted aerial loops behind Home, from the .mp4 files in files/backgrounds or, where adb cannot write there
 * (some Android 11 TVs), the shared Movies/TvHome folder, read with the storage permission. Each return to
 * Home starts the next clip, and a finished clip hands over to the next. Playback runs only while Home is
 * resumed, so the hardware decoder is free the moment an app opens.
 */
final class VideoBackground {

    private static final String PREFS = "video_background", KEY_INDEX = "index";

    private final VideoView view;
    private final View scrim;
    private final File[] dirs;
    private final SharedPreferences prefs;
    private List<File> clips = new ArrayList<>();
    private int index;
    private int failures;
    private boolean playing;

    VideoBackground(Context ctx, VideoView view, View scrim, File... dirs) {
        this.view = view;
        this.scrim = scrim;
        this.dirs = dirs;
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        view.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE);   // never pause the music someone is playing
        view.setOnPreparedListener(mp -> {
            mp.setVolume(0f, 0f);
            mp.setLooping(clips.size() == 1);
            failures = 0;
        });
        view.setOnCompletionListener(mp -> next());
        view.setOnErrorListener((mp, what, extra) -> {
            if (++failures >= clips.size()) {
                stop();
                hide();
            } else {
                next();
            }
            return true;
        });
    }

    void start() {
        if (playing) return;
        clips = new ArrayList<>();
        for (File d : dirs) clips.addAll(list(d));
        if (clips.isEmpty()) {
            hide();
            return;
        }
        index = (prefs.getInt(KEY_INDEX, -1) + 1) % clips.size();
        prefs.edit().putInt(KEY_INDEX, index).apply();
        failures = 0;
        view.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        playing = true;
        play();
    }

    void stop() {
        if (!playing) return;
        playing = false;
        view.stopPlayback();
    }

    private void play() {
        view.setVideoPath(clips.get(index).getAbsolutePath());
        view.start();
    }

    private void next() {
        if (!playing || clips.isEmpty()) return;
        index = (index + 1) % clips.size();
        prefs.edit().putInt(KEY_INDEX, index).apply();
        play();
    }

    private void hide() {
        view.setVisibility(View.GONE);
        scrim.setVisibility(View.GONE);
    }

    private static List<File> list(File dir) {
        List<File> out = new ArrayList<>();
        File[] files = dir != null ? dir.listFiles() : null;
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (f.isFile() && f.length() > 0 && (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm"))) out.add(f);
        }
        return out;
    }
}
