package com.gathin.tvhome;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The top shelf above the dock: Continue Watching from the TV's Watch Next list (what the Google TV home shows),
 * or, when no app publishes one, the TV apps opened most recently.
 */
final class Shelf {

    private static final String TAG = "TvHome.Shelf";
    private static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");
    private static final int MAX_ITEMS = 10;
    private static final int MAX_ART_BYTES = 6 * 1024 * 1024;
    private static final Set<String> NOT_RECENT = new HashSet<>(java.util.Arrays.asList(
            "com.android.tv.settings", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
            "com.android.vending", "com.google.android.tungsten.setupwraith"));

    static final ExecutorService EXEC = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> ART = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    static final class Item {
        final String title, subtitle, pkg, artUri;
        final Intent intent;
        final float progress;   // 0..1, or -1 for none
        final Drawable banner, icon;

        Item(String title, String subtitle, String pkg, String artUri, Intent intent, float progress,
             Drawable banner, Drawable icon) {
            this.title = title;
            this.subtitle = subtitle;
            this.pkg = pkg;
            this.artUri = artUri;
            this.intent = intent;
            this.progress = progress;
            this.banner = banner;
            this.icon = icon;
        }
    }

    static final class Result {
        final List<Item> items;
        final boolean watchNext;

        Result(List<Item> items, boolean watchNext) {
            this.items = items;
            this.watchNext = watchNext;
        }
    }

    private Shelf() { }

    /** Off the main thread. */
    static Result load(Context ctx, Set<String> hidden) {
        List<Item> watch = watchNext(ctx, hidden);
        if (!watch.isEmpty()) return new Result(watch, true);
        return new Result(recentApps(ctx, hidden), false);
    }

    /** Hold OK → Remove: the app's card stays away until the app is used again. */
    static void remove(Context ctx, String pkg) {
        ThemePrefs.get(ctx).edit().putLong(ThemePrefs.SHELF_HIDDEN + pkg, System.currentTimeMillis()).apply();
    }

    /** Clear: every card goes until its app is used again. */
    static void clear(Context ctx) {
        ThemePrefs.get(ctx).edit().putLong(ThemePrefs.SHELF_CLEARED_AT, System.currentTimeMillis()).apply();
    }

    /** True when this app's latest activity predates a Remove or Clear. */
    private static boolean dismissed(android.content.SharedPreferences prefs, String pkg, long activity) {
        long after = Math.max(prefs.getLong(ThemePrefs.SHELF_CLEARED_AT, 0), prefs.getLong(ThemePrefs.SHELF_HIDDEN + pkg, 0));
        return activity <= after;
    }

    private static List<Item> watchNext(Context ctx, Set<String> hidden) {
        List<Item> out = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        android.content.SharedPreferences prefs = ThemePrefs.get(ctx);
        Set<String> seen = new HashSet<>();
        try (Cursor c = ctx.getContentResolver().query(WATCH_NEXT, null, null, null,
                "last_engagement_time_utc_millis DESC")) {
            if (c == null) return out;
            while (c.moveToNext() && out.size() < MAX_ITEMS) {
                String pkg = str(c, "package_name");
                String uri = str(c, "intent_uri");
                if (pkg == null || uri == null || hidden.contains(pkg) || pkg.equals(ctx.getPackageName())) continue;
                if (dismissed(prefs, pkg, lng(c, "last_engagement_time_utc_millis"))) continue;
                if (c.getColumnIndex("browsable") >= 0 && c.getInt(c.getColumnIndex("browsable")) == 0) continue;
                Intent intent = safeIntent(uri, pkg);
                if (intent == null) continue;
                String title = str(c, "title");
                if (title == null || !seen.add(pkg + '\n' + title)) continue;
                String sub = episodeLine(c);
                if (sub == null) sub = appLabel(pm, pkg);
                long pos = lng(c, "last_playback_position_millis"), dur = lng(c, "duration_millis");
                float progress = pos > 0 && dur > 0 ? Math.min(1f, pos / (float) dur) : -1f;
                String art = str(c, "thumbnail_uri");
                if (art == null) art = str(c, "poster_art_uri");
                out.add(new Item(title, sub, pkg, art, intent, progress, null, appIcon(pm, pkg)));
            }
        } catch (RuntimeException e) {   // SecurityException on firmware without the permission, bad rows
            Log.w(TAG, "watch next unavailable: " + e);
        }
        return out;
    }

    /**
     * The row's intent, kept to its own app: an intent naming another package is dropped, an implicit one is
     * pinned to the row's package, and URI grants are stripped.
     */
    private static Intent safeIntent(String uri, String pkg) {
        Intent i;
        try {
            i = uri.startsWith("intent:") ? Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        } catch (Exception e) {
            return null;
        }
        i.setSelector(null);
        if (i.getComponent() != null && !pkg.equals(i.getComponent().getPackageName())) return null;
        if (i.getPackage() != null && !pkg.equals(i.getPackage())) return null;
        if (i.getComponent() == null) i.setPackage(pkg);
        i.setFlags((i.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
                | Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private static String episodeLine(Cursor c) {
        String ep = str(c, "episode_title"), s = str(c, "season_display_number"), e = str(c, "episode_display_number");
        String num = s != null && e != null ? "S" + s + " E" + e : null;
        if (num != null && ep != null) return num + " · " + ep;
        return num != null ? num : ep;
    }

    /** Needs usage access (appops GET_USAGE_STATS); without it the list is simply empty. */
    private static List<Item> recentApps(Context ctx, Set<String> hidden) {
        List<Item> out = new ArrayList<>();
        UsageStatsManager usm = ctx.getSystemService(UsageStatsManager.class);
        if (usm == null) return out;
        long now = System.currentTimeMillis();
        List<UsageStats> stats;
        try {
            stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 14L * 24 * 3600 * 1000, now);
        } catch (RuntimeException e) {
            stats = Collections.emptyList();
        }
        if (stats == null) stats = Collections.emptyList();
        Map<String, long[]> byPkg = new HashMap<>();   // pkg -> {last used, foreground ms}
        for (UsageStats u : stats) {
            long[] v = byPkg.get(u.getPackageName());
            if (v == null) byPkg.put(u.getPackageName(), v = new long[2]);
            v[0] = Math.max(v[0], u.getLastTimeUsed());
            v[1] += u.getTotalTimeInForeground();
        }
        // What apps last played (MediaListener) puts them in the row even after a short visit.
        Map<String, RecentMedia.Entry> played = RecentMedia.load(ctx);
        for (RecentMedia.Entry e : played.values()) {
            if (now - e.time > 14L * 24 * 3600 * 1000) continue;
            long[] v = byPkg.get(e.pkg);
            if (v == null) byPkg.put(e.pkg, v = new long[2]);
            v[0] = Math.max(v[0], e.time);
            v[1] = Math.max(v[1], 30_000);
        }
        List<String> pkgs = new ArrayList<>(byPkg.keySet());
        Collections.sort(pkgs, (a, b) -> Long.compare(byPkg.get(b)[0], byPkg.get(a)[0]));
        PackageManager pm = ctx.getPackageManager();
        android.content.SharedPreferences prefs = ThemePrefs.get(ctx);
        for (String pkg : pkgs) {
            if (out.size() >= 8) break;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg) || NOT_RECENT.contains(pkg)) continue;
            if (byPkg.get(pkg)[1] < 30_000 || dismissed(prefs, pkg, byPkg.get(pkg)[0])) continue;
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) continue;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Drawable banner = null;
            try {
                banner = launch.getComponent() != null ? pm.getActivityBanner(launch.getComponent()) : null;
                if (banner == null) banner = pm.getApplicationBanner(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            String label = appLabel(pm, pkg);
            RecentMedia.Entry media = played.get(pkg);
            if (media != null && now - media.time <= 14L * 24 * 3600 * 1000) {
                // The thing it played, not just the app: thumbnail, title, channel and how far in.
                Intent resume = RecentMedia.resumeIntent(media);
                String sub = media.subtitle != null ? label + " · " + media.subtitle : label;
                String art = RecentMedia.art(media);
                out.add(new Item(media.title, sub, pkg, art, resume != null ? resume : launch,
                        media.progress(), art == null ? banner : null, appIcon(pm, pkg)));
            } else {
                out.add(new Item(label, ctx.getString(R.string.shelf_recent), pkg, null, launch, -1f,
                        banner, appIcon(pm, pkg)));
            }
        }
        return out;
    }

    private static String appLabel(PackageManager pm, String pkg) {
        try {
            return Cards.cleanLabel(String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))), pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static Drawable appIcon(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0 || c.isNull(i)) return null;
        String s = c.getString(i);
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static long lng(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }

    /** Poster or thumbnail from a content://, android.resource://, file:// or https:// URI, sampled to about reqW. */
    static Bitmap loadArt(Context ctx, String uri, int reqW) {
        Bitmap cached = ART.get(uri);
        if (cached != null) return cached;
        byte[] data;
        try (InputStream in = open(ctx, uri)) {
            if (in == null) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_ART_BYTES) return null;
            }
            data = buf.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0) return null;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= reqW) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (bmp != null) ART.put(uri, bmp);
        return bmp;
    }

    private static InputStream open(Context ctx, String uri) throws IOException {
        Uri u = Uri.parse(uri);
        String scheme = u.getScheme();
        if ("https".equals(scheme) || "http".equals(scheme)) {
            HttpURLConnection conn = (HttpURLConnection) new URL(uri).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.setInstanceFollowRedirects(true);
            return conn.getResponseCode() == 200 ? conn.getInputStream() : null;
        }
        if (ContentResolver.SCHEME_CONTENT.equals(scheme) || ContentResolver.SCHEME_ANDROID_RESOURCE.equals(scheme)
                || ContentResolver.SCHEME_FILE.equals(scheme)) {
            return ctx.getContentResolver().openInputStream(u);
        }
        return null;
    }

    // ---- the row ---------------------------------------------------------------------------------

    interface CardListener {
        void onCardFocused();

        /** Hold OK on a card: Remove or Clear. */
        void onCardMenu(Item item, View card);
    }

    static final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {

        private final Context ctx;
        private final CardListener listener;
        private final Handler main = new Handler(Looper.getMainLooper());
        private List<Item> items = Collections.emptyList();

        Adapter(Context ctx, CardListener listener) {
            this.ctx = ctx;
            this.listener = listener;
        }

        void submit(List<Item> fresh) {
            items = fresh;
            notifyDataSetChanged();
        }

        @Override public int getItemCount() { return items.size(); }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(ctx).inflate(R.layout.item_shelf, parent, false);
            v.setClipToOutline(true);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Item it = items.get(position);
            h.item = it;
            h.name.setText(it.title);
            h.sub.setText(it.subtitle);
            h.progress.setVisibility(it.progress >= 0 ? View.VISIBLE : View.GONE);
            if (it.progress >= 0) h.progress.setProgress((int) (it.progress * 1000));
            h.art.setImageDrawable(it.banner);
            h.icon.setVisibility(View.GONE);
            if (it.banner == null && it.artUri == null) showIcon(h, it);
            if (it.artUri != null) {
                Bitmap cached = ART.get(it.artUri);
                if (cached != null) {
                    h.art.setImageBitmap(cached);
                } else {
                    showIcon(h, it);
                    int reqW = h.itemView.getLayoutParams().width;
                    EXEC.execute(() -> {
                        Bitmap bmp = loadArt(ctx, it.artUri, reqW);
                        if (bmp != null) main.post(() -> {
                            if (h.item != it) return;
                            h.icon.setVisibility(View.GONE);
                            h.art.setImageBitmap(bmp);
                        });
                    });
                }
            }
            h.itemView.setOnClickListener(v -> {
                try {
                    ctx.startActivity(it.intent);
                } catch (Exception e) {
                    Toast.makeText(ctx, ctx.getString(R.string.cant_open, it.title), Toast.LENGTH_SHORT).show();
                }
            });
        }

        private static void showIcon(Holder h, Item it) {
            h.art.setImageDrawable(null);
            h.icon.setImageDrawable(it.icon);
            h.icon.setVisibility(it.icon != null ? View.VISIBLE : View.GONE);
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView art, icon;
            final TextView name, sub;
            final ProgressBar progress;
            Item item;

            Holder(View v) {
                super(v);
                art = v.findViewById(R.id.shelfArt);
                icon = v.findViewById(R.id.shelfIcon);
                name = v.findViewById(R.id.shelfName);
                sub = v.findViewById(R.id.shelfSub);
                progress = v.findViewById(R.id.shelfProgress);
                v.setOnFocusChangeListener((view, focused) -> {
                    float s = focused ? 1.08f : 1f;
                    view.animate().scaleX(s).scaleY(s).translationZ(focused ? 12f : 0f).setDuration(160).start();
                    if (focused) listener.onCardFocused();
                });
                v.setOnLongClickListener(view -> {
                    if (item != null) listener.onCardMenu(item, view);
                    return true;
                });
            }
        }
    }
}
