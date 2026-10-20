package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Choices made in the on-TV Theme app. They live in the same preferences as the launcher's own settings
 * ({@link Config#SETTINGS_NAME}); every change bumps {@link #VERSION} so a resumed Home knows to rebuild.
 */
final class ThemePrefs {

    static final String VERSION = "theme_version";

    static final String BG_ENABLED = "bg_enabled";
    /** Seconds a wallpaper stays before the next one: 0 = after each clip, -1 = never change. */
    static final String BG_INTERVAL = "bg_interval";
    static final String BG_SHUFFLE = "bg_shuffle";
    static final String BG_DIM = "bg_dim";
    static final String BG_CURRENT = "bg_current";
    static final String BG_CHANGED_AT = "bg_changed_at";

    static final String ROWS_BELOW_DOCK = "rows_below_dock";
    static final String SHELF = "shelf";
    static final String ACCENT = "accent";
    static final String FROST = "frost";
    static final String SHOW_CLOCK = "show_clock";

    /** Jump Back In: cards whose app was last used before these times stay hidden. */
    static final String SHELF_CLEARED_AT = "shelf_cleared_at";
    static final String SHELF_HIDDEN = "shelf_hidden:";

    static final String SAVER_STYLE = "saver_style";
    static final String SAVER_CLOCK = "saver_clock";

    static final int[] INTERVALS = {0, 60, 300, 900, 1800, 3600, -1};
    /** 0 white glass, then blue, gold, pink, green, purple. */
    static final int[] ACCENTS = {0, 0xFF6EC1FF, 0xFFFFC857, 0xFFFF6FAE, 0xFF5EE6A8, 0xFFB78CFF};
    static final float[] DIM_ALPHA = {0.45f, 0.8f, 1f};
    static final float[] FROST_ALPHA = {0.6f, 1f, 1.6f};

    static final int SAVER_AERIAL = 0, SAVER_CLOCK_ONLY = 1;

    private ThemePrefs() { }

    static SharedPreferences get(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    static int version(Context ctx) {
        return get(ctx).getInt(VERSION, 0);
    }

    static SharedPreferences.Editor edit(Context ctx) {
        SharedPreferences p = get(ctx);
        return p.edit().putInt(VERSION, p.getInt(VERSION, 0) + 1);
    }

    static boolean bgEnabled(Context ctx) { return get(ctx).getBoolean(BG_ENABLED, true); }
    static int bgInterval(Context ctx) { return get(ctx).getInt(BG_INTERVAL, 60); }
    static boolean bgShuffle(Context ctx) { return get(ctx).getBoolean(BG_SHUFFLE, false); }
    static int bgDim(Context ctx) { return clamp(get(ctx).getInt(BG_DIM, 1), 0, DIM_ALPHA.length - 1); }
    static boolean shelf(Context ctx) { return get(ctx).getBoolean(SHELF, true); }
    static int accent(Context ctx) { return clamp(get(ctx).getInt(ACCENT, 0), 0, ACCENTS.length - 1); }
    static int frost(Context ctx) { return clamp(get(ctx).getInt(FROST, 1), 0, FROST_ALPHA.length - 1); }
    static boolean showClock(Context ctx) { return get(ctx).getBoolean(SHOW_CLOCK, true); }
    static int saverStyle(Context ctx) { return get(ctx).getInt(SAVER_STYLE, SAVER_AERIAL); }
    static boolean saverClock(Context ctx) { return get(ctx).getBoolean(SAVER_CLOCK, true); }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
