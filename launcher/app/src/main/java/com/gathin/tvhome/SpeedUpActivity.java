package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;

/**
 * Speed Up: closes every app's background processes and reports the memory that came free. The launcher (and
 * its own services), the system, the keyboard and whatever is playing music right now are left alone. Without
 * root Android lets an app end only background processes, which is what this does.
 */
public final class SpeedUpActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView title, detail;
    private View card;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        float d = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x99000000);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setBackgroundResource(R.drawable.glass_panel);
        int pad = (int) (36 * d);
        box.setPadding(pad, (int) (30 * d), pad, (int) (30 * d));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_speed);
        box.addView(icon, new LinearLayout.LayoutParams((int) (72 * d), (int) (72 * d)));
        title = new TextView(this);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setPadding(0, (int) (14 * d), 0, (int) (4 * d));
        title.setText(R.string.speed_working);
        box.addView(title);
        detail = new TextView(this);
        detail.setTextColor(0xB3FFFFFF);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        detail.setGravity(Gravity.CENTER);
        box.addView(detail);
        root.addView(box, new FrameLayout.LayoutParams((int) (420 * d), -2, Gravity.CENTER));
        setContentView(root);
        card = box;
        card.setAlpha(0f);
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start();
        icon.animate().rotationBy(360f).setDuration(1400).start();

        Set<String> keep = keepRunning();
        new Thread(() -> {
            ActivityManager am = getSystemService(ActivityManager.class);
            ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(before);
            int closed = 0;
            for (ApplicationInfo app : getPackageManager().getInstalledApplications(0)) {
                if (keep.contains(app.packageName) || (app.flags & ApplicationInfo.FLAG_PERSISTENT) != 0) continue;
                am.killBackgroundProcesses(app.packageName);
                closed++;
            }
            try {
                Thread.sleep(1500);   // memory returns once the killed processes are reaped
            } catch (InterruptedException ignored) {
            }
            ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(after);
            long freedMb = Math.max(0, after.availMem - before.availMem) / (1024 * 1024);
            long availMb = after.availMem / (1024 * 1024);
            int apps = closed;
            handler.post(() -> done(freedMb, availMb, apps));
        }, "speed-up").start();
    }

    private void done(long freedMb, long availMb, int apps) {
        if (isFinishing()) return;
        title.setText(freedMb >= 5 ? getString(R.string.speed_done, freedMb) : getString(R.string.speed_already));
        detail.setText(getString(R.string.speed_detail, apps, availMb));
        handler.postDelayed(() -> card.animate().alpha(0f).setDuration(240).withEndAction(this::finish).start(), 2600);
    }

    /** The launcher, the system UI, the keyboard and the app playing right now. */
    private Set<String> keepRunning() {
        Set<String> keep = new HashSet<>();
        keep.add(getPackageName());
        keep.add("android");
        keep.add("com.android.systemui");
        keep.add("com.android.tv.settings");
        // Ending these only makes them restart, and the remote (Bluetooth, voice) could drop for a moment.
        keep.add("com.android.bluetooth");
        keep.add("com.google.android.gms");
        keep.add("com.google.android.gsf");
        keep.add("com.google.android.katniss");
        keep.add("com.google.android.tvrecommendations");
        keep.add("com.tcl.tv");
        keep.add("com.tcl.tvinput");
        keep.add("com.tvos");
        keep.add("com.tcl.system.server");
        keep.add("com.tcl.framework.custom");
        String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        ComponentName imeName = ime != null ? ComponentName.unflattenFromString(ime) : null;
        if (imeName != null) keep.add(imeName.getPackageName());
        try {
            MediaSessionManager msm = getSystemService(MediaSessionManager.class);
            for (MediaController c : msm.getActiveSessions(new ComponentName(this, MediaListener.class))) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) keep.add(c.getPackageName());
            }
        } catch (SecurityException | NullPointerException ignored) {
        }
        return keep;
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
