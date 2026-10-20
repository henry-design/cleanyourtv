package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The on-TV Theme app: wallpaper videos (pick, delete, how long each stays), the screensaver, the app grid and
 * the look. Every change is saved at once through {@link ThemePrefs}; Home rebuilds itself when it comes back.
 * The current wallpaper plays behind the settings as a live preview.
 */
public final class ThemeActivity extends Activity {

    private static final int[] SAVER_MINUTES = {5, 10, 15, 30, 60};

    private float density;
    private LinearLayout column;
    private VideoBackground preview;
    private String focusTag;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        VideoView video = new VideoView(this);
        View fade = new View(this);
        fade.setBackgroundColor(0xFF000000);
        View scrim = new View(this);
        scrim.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        root.addView(video, full);
        root.addView(fade, new FrameLayout.LayoutParams(-1, -1));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        video.setVisibility(View.GONE);
        fade.setVisibility(View.GONE);
        scrim.setVisibility(View.GONE);
        preview = new VideoBackground(this, video, scrim, fade, false);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(40);
        column.setPadding(pad, dp(36), pad, dp(60));
        FrameLayout.LayoutParams colLp = new FrameLayout.LayoutParams(dp(720), -2, Gravity.CENTER_HORIZONTAL);
        scroll.addView(column, colLp);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        preview.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        preview.stop();
    }

    // ---- the screen ------------------------------------------------------------------------------

    private void build() {
        View focused = getCurrentFocus();
        if (focused != null && focused.getTag() != null) focusTag = String.valueOf(focused.getTag());
        column.removeAllViews();
        TextView title = text(getString(R.string.theme_title), 40, 0xFFFFFFFF, "sans-serif-light");
        title.setPadding(dp(8), 0, 0, dp(18));
        column.addView(title);

        // Wallpaper
        LinearLayout wall = section(R.string.theme_wallpaper);
        toggle(wall, "bg_on", R.string.theme_video_wallpaper, ThemePrefs.BG_ENABLED, true, true);
        cycle(wall, "bg_interval", R.string.theme_change_every, () -> intervalLabel(ThemePrefs.bgInterval(this)), () -> {
            int at = indexOf(ThemePrefs.INTERVALS, ThemePrefs.bgInterval(this));
            ThemePrefs.edit(this).putInt(ThemePrefs.BG_INTERVAL,
                    ThemePrefs.INTERVALS[(at + 1) % ThemePrefs.INTERVALS.length])
                    .putLong(ThemePrefs.BG_CHANGED_AT, System.currentTimeMillis()).apply();
            restartPreview();
        });
        cycle(wall, "bg_order", R.string.theme_order,
                () -> getString(ThemePrefs.bgShuffle(this) ? R.string.theme_shuffle : R.string.theme_in_order), () -> {
                    ThemePrefs.edit(this).putBoolean(ThemePrefs.BG_SHUFFLE, !ThemePrefs.bgShuffle(this)).apply();
                    restartPreview();
                });
        cycle(wall, "bg_dim", R.string.theme_dim, () -> levelLabel(ThemePrefs.bgDim(this)), () -> {
            ThemePrefs.edit(this).putInt(ThemePrefs.BG_DIM, (ThemePrefs.bgDim(this) + 1) % 3).apply();
            restartPreview();
        });

        List<File> clips = VideoBackground.list(this);
        LinearLayout list = section(getString(R.string.theme_clips, clips.size()));
        String current = ThemePrefs.get(this).getString(ThemePrefs.BG_CURRENT, null);
        if (clips.isEmpty()) list.addView(note(getString(R.string.theme_no_clips)));
        for (File clip : clips) {
            boolean now = clip.getName().equals(current);
            String value = (now ? getString(R.string.theme_now_playing) + "  ·  " : "")
                    + String.format(Locale.US, "%d MB", Math.max(1, clip.length() / (1024 * 1024)));
            row(list, "clip:" + clip.getName(), pretty(clip.getName()), () -> value, () -> clipMenu(clip));
        }
        list.addView(note(getString(R.string.theme_add_hint)));

        // Screensaver
        LinearLayout saver = section(R.string.theme_screensaver);
        cycle(saver, "saver_style", R.string.theme_saver_style, () -> getString(
                ThemePrefs.saverStyle(this) == ThemePrefs.SAVER_AERIAL ? R.string.theme_saver_aerial : R.string.theme_saver_clock), () ->
                ThemePrefs.edit(this).putInt(ThemePrefs.SAVER_STYLE, ThemePrefs.saverStyle(this) == ThemePrefs.SAVER_AERIAL
                        ? ThemePrefs.SAVER_CLOCK_ONLY : ThemePrefs.SAVER_AERIAL).apply());
        toggle(saver, "saver_clock", R.string.theme_saver_show_clock, ThemePrefs.SAVER_CLOCK, true, false);
        cycle(saver, "saver_after", R.string.theme_saver_after, this::saverAfterLabel, this::cycleSaverAfter);
        row(saver, "saver_pick", getString(R.string.theme_saver_pick), () -> "", () -> {
            Toast.makeText(this, R.string.theme_saver_pick_hint, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_DREAM_SETTINGS));
        });

        // Apps
        LinearLayout apps = section(R.string.theme_apps);
        cycle(apps, "columns", R.string.theme_columns, () -> String.valueOf(Config.load(this).columns), () -> {
            int c = Config.load(this).columns;
            ThemePrefs.edit(this).putInt("columns", c >= 7 || c < 4 ? 4 : c + 1).apply();
        });
        cycle(apps, "rows_below", R.string.theme_rows_below, () -> String.valueOf(Config.load(this).rowsBelowDock), () ->
                ThemePrefs.edit(this).putInt(ThemePrefs.ROWS_BELOW_DOCK, (Config.load(this).rowsBelowDock + 1) % 4).apply());
        toggle(apps, "shelf", R.string.theme_shelf, ThemePrefs.SHELF, true, false);
        apps.addView(note(getString(R.string.theme_move_hint)));

        // Look
        LinearLayout look = section(R.string.theme_look);
        cycle(look, "accent", R.string.theme_accent, () -> accentLabel(ThemePrefs.accent(this)), () ->
                ThemePrefs.edit(this).putInt(ThemePrefs.ACCENT, (ThemePrefs.accent(this) + 1) % ThemePrefs.ACCENTS.length).apply());
        cycle(look, "frost", R.string.theme_frost, () -> levelLabel(ThemePrefs.frost(this)), () ->
                ThemePrefs.edit(this).putInt(ThemePrefs.FROST, (ThemePrefs.frost(this) + 1) % 3).apply());
        cycle(look, "clock", R.string.theme_clock, () -> getString(ThemePrefs.showClock(this) ? R.string.theme_show : R.string.theme_hide), () ->
                ThemePrefs.edit(this).putBoolean(ThemePrefs.SHOW_CLOCK, !ThemePrefs.showClock(this)).apply());
        cycle(look, "animations", R.string.theme_animations, () -> onOff(Config.load(this).animations), () -> {
            boolean on = !Config.load(this).animations;
            SharedPreferences.Editor e = ThemePrefs.edit(this).putBoolean("animations", on);
            if (!on) e.putBoolean("advanced_effects", false);
            e.apply();
        });
        row(look, "reset", getString(R.string.theme_reset), () -> "", () -> {
            ThemePrefs.edit(this).remove(ThemePrefs.BG_ENABLED).remove(ThemePrefs.BG_INTERVAL).remove(ThemePrefs.BG_SHUFFLE)
                    .remove(ThemePrefs.BG_DIM).remove(ThemePrefs.ROWS_BELOW_DOCK).remove(ThemePrefs.SHELF)
                    .remove(ThemePrefs.ACCENT).remove(ThemePrefs.FROST).remove(ThemePrefs.SHOW_CLOCK)
                    .remove(ThemePrefs.SAVER_STYLE).remove(ThemePrefs.SAVER_CLOCK)
                    .remove("columns").remove("animations").remove("advanced_effects").apply();
            Toast.makeText(this, R.string.theme_reset_done, Toast.LENGTH_SHORT).show();
            restartPreview();
        });

        View restore = focusTag != null ? column.findViewWithTag(focusTag) : null;
        if (restore != null) restore.requestFocus();
        else if (column.findViewWithTag("bg_on") != null) column.findViewWithTag("bg_on").requestFocus();
    }

    private void clipMenu(File clip) {
        CharSequence[] items = {getString(R.string.theme_set_wallpaper), getString(R.string.theme_delete)};
        new AlertDialog.Builder(this)
                .setTitle(pretty(clip.getName()))
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        ThemePrefs.edit(this).putString(ThemePrefs.BG_CURRENT, clip.getName())
                                .putLong(ThemePrefs.BG_CHANGED_AT, System.currentTimeMillis())
                                .putBoolean(ThemePrefs.BG_ENABLED, true).apply();
                        restartPreview();
                        build();
                    } else {
                        confirmDelete(clip);
                    }
                })
                .show();
    }

    private void confirmDelete(File clip) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.theme_delete_confirm, pretty(clip.getName())))
                .setPositiveButton(R.string.theme_delete, (d, w) -> {
                    preview.stop();
                    if (clip.delete()) {
                        ThemePrefs.edit(this).apply();
                        Toast.makeText(this, getString(R.string.theme_deleted, pretty(clip.getName())), Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                    }
                    focusTag = null;
                    preview.start();
                    build();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- screensaver timeout -------------------------------------------------------------------

    private String saverAfterLabel() {
        int ms = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 600_000);
        int min = Math.round(ms / 60_000f);
        return min >= 60 ? getString(R.string.theme_saver_hour) : getString(R.string.theme_saver_minutes, Math.max(1, min));
    }

    private void cycleSaverAfter() {
        if (!Settings.System.canWrite(this)) {
            Toast.makeText(this, R.string.theme_saver_no_access, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_DREAM_SETTINGS));
            return;
        }
        int ms = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 600_000);
        int min = Math.round(ms / 60_000f), next = SAVER_MINUTES[0];
        for (int i = 0; i < SAVER_MINUTES.length; i++) {
            if (SAVER_MINUTES[i] > min) { next = SAVER_MINUTES[i]; break; }
        }
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, next * 60_000);
    }

    // ---- row builders ----------------------------------------------------------------------------

    private LinearLayout section(int titleRes) {
        return section(getString(titleRes));
    }

    private LinearLayout section(String name) {
        TextView header = text(name.toUpperCase(Locale.getDefault()), 13, 0xB3FFFFFF, "sans-serif-medium");
        header.setLetterSpacing(0.12f);
        header.setPadding(dp(14), dp(22), 0, dp(8));
        column.addView(header);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        panel.setPadding(dp(8), dp(8), dp(8), dp(8));
        column.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        return panel;
    }

    private void toggle(LinearLayout panel, String tag, int label, String key, boolean def, boolean restart) {
        cycle(panel, tag, label, () -> onOff(ThemePrefs.get(this).getBoolean(key, def)), () -> {
            ThemePrefs.edit(this).putBoolean(key, !ThemePrefs.get(this).getBoolean(key, def)).apply();
            if (restart) restartPreview();
        });
    }

    private void cycle(LinearLayout panel, String tag, int label, Supplier<String> value, Runnable action) {
        row(panel, tag, getString(label), value, () -> {
            action.run();
            build();
        });
    }

    private void row(LinearLayout panel, String tag, String label, Supplier<String> value, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setTag(tag);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.setPadding(dp(18), 0, dp(18), 0);
        row.setBackgroundResource(R.drawable.theme_row_bg);
        row.setFocusable(true);
        row.setClickable(true);
        row.setDuplicateParentStateEnabled(false);
        TextView l = text(label, 18, 0, "sans-serif");
        l.setTextColor(getColorStateList(R.drawable.theme_row_text));
        l.setDuplicateParentStateEnabled(true);
        l.setSingleLine(true);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = text(value.get(), 17, 0, "sans-serif");
        v.setTextColor(getColorStateList(R.drawable.theme_row_value));
        v.setDuplicateParentStateEnabled(true);
        v.setSingleLine(true);
        row.addView(v, new LinearLayout.LayoutParams(-2, -2));
        row.setOnClickListener(x -> action.run());
        panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView note(String s) {
        TextView t = text(s, 14, 0x99FFFFFF, "sans-serif");
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private TextView text(String s, int sp, int color, String family) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (color != 0) t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        return t;
    }

    // ---- labels and helpers ------------------------------------------------------------------------

    private String intervalLabel(int seconds) {
        if (seconds == 0) return getString(R.string.theme_after_each);
        if (seconds < 0) return getString(R.string.theme_never);
        if (seconds >= 3600) return getString(R.string.theme_hour);
        return getString(R.string.theme_minutes, seconds / 60);
    }

    private String levelLabel(int level) {
        return getString(level == 0 ? R.string.theme_light : level == 1 ? R.string.theme_medium : R.string.theme_strong);
    }

    private String accentLabel(int i) {
        int[] names = {R.string.accent_white, R.string.accent_blue, R.string.accent_gold, R.string.accent_pink,
                R.string.accent_green, R.string.accent_purple};
        return getString(names[i]);
    }

    private String onOff(boolean on) {
        return getString(on ? R.string.on : R.string.off);
    }

    /** "coastal-cliffs.mp4" -> "Coastal Cliffs". */
    private static String pretty(String file) {
        String base = file.replaceAll("\\.[^.]+$", "").replace('-', ' ').replace('_', ' ').trim();
        StringBuilder sb = new StringBuilder();
        for (String w : base.split("\\s+")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.length() > 0 ? sb.toString() : file;
    }

    private static int indexOf(int[] values, int v) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return i;
        return 0;
    }

    private void restartPreview() {
        preview.stop();
        preview.start();
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
