package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The last thing each app played, as announced through its media session (title, channel, artwork, position).
 * Written by {@link MediaListener} while apps play; read by the Jump Back In shelf so an app's card shows what
 * was on instead of only its icon. Kept in the launcher's private files, one entry per app.
 */
final class RecentMedia {

    private static final String TAG = "TvHome.RecentMedia";
    private static final String FILE = "recent_media.json";
    private static final String ART_DIR = "media_art";
    private static final int MAX_ENTRIES = 20;
    private static final String YOUTUBE_TV = "com.google.android.youtube.tv";
    private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    static final class Entry {
        final String pkg, title, subtitle, mediaId, art;
        final long position, duration, time;

        Entry(String pkg, String title, String subtitle, String mediaId, String art, long position, long duration, long time) {
            this.pkg = pkg;
            this.title = title;
            this.subtitle = subtitle;
            this.mediaId = mediaId;
            this.art = art;
            this.position = position;
            this.duration = duration;
            this.time = time;
        }

        float progress() {
            return duration > 0 && position > 0 ? Math.min(1f, position / (float) duration) : -1f;
        }
    }

    private RecentMedia() { }

    static synchronized Map<String, Entry> load(Context ctx) {
        Map<String, Entry> out = new HashMap<>();
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return out;
        try {
            JSONArray arr = new JSONArray(new String(new AtomicFile(f).readFully(), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry(o.getString("pkg"), o.getString("title"), o.optString("subtitle", null),
                        o.optString("media_id", null), o.optString("art", null), o.optLong("position"),
                        o.optLong("duration"), o.optLong("time"));
                out.put(e.pkg, e);
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "cannot read " + FILE + ": " + e);
        }
        return out;
    }

    /** Records what {@code pkg} is playing now. Off the main thread: it may write a thumbnail. */
    static synchronized void record(Context ctx, String pkg, MediaMetadata md, PlaybackState state) {
        if (md == null || pkg == null || pkg.equals(ctx.getPackageName())) return;
        String title = text(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (title == null) return;
        String subtitle = text(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                MediaMetadata.METADATA_KEY_ALBUM);
        String mediaId = text(md, MediaMetadata.METADATA_KEY_MEDIA_ID);
        long duration = Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION));
        long position = 0;
        if (state != null && state.getPosition() >= 0) {
            position = state.getPosition();
            if (state.getState() == PlaybackState.STATE_PLAYING && state.getLastPositionUpdateTime() > 0) {
                position += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime())
                        * state.getPlaybackSpeed());
            }
        }
        Map<String, Entry> all = load(ctx);
        Entry before = all.get(pkg);
        long now = System.currentTimeMillis();
        String art = before != null && title.equals(before.title) ? before.art : null;
        if (art == null) art = saveArt(ctx, pkg, md, now);
        all.put(pkg, new Entry(pkg, title, subtitle, mediaId, art, position, duration, now));
        save(ctx, all);
    }

    /** For YouTube, the video itself (at the saved position) when the session named it by its id; else null. */
    static Intent resumeIntent(Entry e) {
        if (!YOUTUBE_TV.equals(e.pkg) || e.mediaId == null || !YOUTUBE_ID.matcher(e.mediaId).matches()) return null;
        String url = "https://www.youtube.com/watch?v=" + e.mediaId + (e.position > 10_000 ? "&t=" + e.position / 1000 + "s" : "");
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(e.pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** The entry's artwork, or for a YouTube video named by its id (YouTube TV sends no artwork), its thumbnail. */
    static String art(Entry e) {
        if (e.art != null) return e.art;
        if (YOUTUBE_TV.equals(e.pkg) && e.mediaId != null && YOUTUBE_ID.matcher(e.mediaId).matches()) {
            return "https://i.ytimg.com/vi/" + e.mediaId + "/mqdefault.jpg";
        }
        return null;
    }

    private static String saveArt(Context ctx, String pkg, MediaMetadata md, long now) {
        Bitmap bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (bmp != null) {
            File dir = new File(ctx.getFilesDir(), ART_DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File out = new File(dir, pkg + ".jpg");
            int w = Math.min(480, bmp.getWidth());
            Bitmap scaled = w == bmp.getWidth() ? bmp
                    : Bitmap.createScaledBitmap(bmp, w, Math.max(1, bmp.getHeight() * w / bmp.getWidth()), true);
            try (FileOutputStream os = new FileOutputStream(out)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, os);
            } catch (IOException e) {
                return null;
            }
            // The time keeps the shelf's artwork cache from showing the previous video's thumbnail.
            return Uri.fromFile(out).buildUpon().appendQueryParameter("t", String.valueOf(now)).build().toString();
        }
        return text(md, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
    }

    private static void save(Context ctx, Map<String, Entry> all) {
        JSONArray arr = new JSONArray();
        all.values().stream().sorted((a, b) -> Long.compare(b.time, a.time)).limit(MAX_ENTRIES).forEach(e -> {
            try {
                arr.put(new JSONObject().put("pkg", e.pkg).put("title", e.title).put("subtitle", e.subtitle)
                        .put("media_id", e.mediaId).put("art", e.art).put("position", e.position)
                        .put("duration", e.duration).put("time", e.time));
            } catch (JSONException ignored) {
            }
        });
        AtomicFile af = new AtomicFile(new File(ctx.getFilesDir(), FILE));
        FileOutputStream os = null;
        try {
            os = af.startWrite();
            os.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            af.finishWrite(os);
        } catch (IOException e) {
            if (os != null) af.failWrite(os);
        }
    }

    private static String text(MediaMetadata md, String... keys) {
        for (String k : keys) {
            CharSequence s = md.getText(k);
            if (s != null && s.toString().trim().length() > 0) return s.toString().trim();
        }
        return null;
    }
}
