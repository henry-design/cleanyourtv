package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Blurred apps behind the header, with no panel of its own. Android 11 has no live blur, so while the grid is
 * scrolled this draws the grid region under the header into a bitmap at 1/8 scale, box-blurs it and stretches
 * it back. The work happens only on scroll and layout, once per frame at most, and fades out when the grid is
 * back at the top.
 */
public class HeaderGlass extends View {

    private static final String TAG = "TvHome.HeaderGlass";
    private static final int SCALE = 8;
    // In small-bitmap pixels (about 16 px on screen): enough to defocus each app, too little to merge
    // neighbours into one band that would read as a header background.
    private static final int BLUR_RADIUS = 2;
    /** The apps read as out of focus behind the header, a little softer than the real ones. */
    private static final float SOFTNESS = 0.8f;

    private final float density;
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fade = new Paint();
    {
        fade.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
    }
    private final Rect dst = new Rect();
    private RecyclerView source;
    private final RecyclerView.State state = new RecyclerView.State();
    private View extra;
    private Bitmap small;
    private Canvas smallCanvas;
    private int[] pixels, scratch;
    private float amount;
    private boolean pending, broken;

    public HeaderGlass(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
    }

    /** Another view that slides under the header with the grid (the Jump Back In row). */
    void addSource(View v) {
        extra = v;
    }

    void attach(RecyclerView grid) {
        source = grid;
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                requestCapture();
            }
        });
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> requestCapture());
    }

    /** Header height in px: the glass covers exactly the header. */
    void setGlassHeight(int px) {
        if (getLayoutParams().height == px) return;
        getLayoutParams().height = px;
        requestLayout();
        requestCapture();
    }

    void requestCapture() {
        if (pending || broken || source == null) return;
        pending = true;
        postOnAnimation(this::capture);
    }

    private void capture() {
        pending = false;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 1 || source == null) return;
        float was = amount;
        amount = Math.min(1f, source.computeVerticalScrollOffset() / (48f * density));
        if (amount <= 0f) {
            if (was > 0f) invalidate();
            return;
        }
        int sw = Math.max(1, w / SCALE), sh = Math.max(1, h / SCALE);
        if (small == null || small.getWidth() != sw || small.getHeight() != sh) {
            small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            smallCanvas = new Canvas(small);
            pixels = new int[sw * sh];
            scratch = new int[sw * sh];
        }
        small.eraseColor(0);
        try {
            drawSource(source);
            if (extra != null && extra.getVisibility() == VISIBLE && extra.getAlpha() > 0f) drawSource(extra);
        } catch (RuntimeException e) {   // e.g. a hardware bitmap somewhere in a tile
            Log.w(TAG, "no frosted header: " + e);
            broken = true;
            amount = 0f;
        }
        if (!broken) {
            small.getPixels(pixels, 0, sw, 0, 0, sw, sh);
            // Two box passes: a soft defocus rather than smeared squares.
            boxBlur(pixels, scratch, sw, sh, BLUR_RADIUS);
            boxBlur(pixels, scratch, sw, sh, BLUR_RADIUS);
            small.setPixels(pixels, 0, sw, 0, 0, sw, sh);
        }
        invalidate();
    }

    /** Draws {@code v} into the small bitmap where it sits relative to this view (its translation included). */
    private void drawSource(View v) {
        int[] a = new int[2], b = new int[2];
        v.getLocationInWindow(a);
        getLocationInWindow(b);
        smallCanvas.save();
        smallCanvas.scale(1f / SCALE, 1f / SCALE);
        smallCanvas.translate(a[0] - b[0], a[1] - b[1]);
        if (v instanceof RecyclerView) drawRows((RecyclerView) v);
        else v.draw(smallCanvas);
        smallCanvas.restore();
    }

    /**
     * The grid drawn item by item rather than through its own draw(): drawing the RecyclerView itself here would
     * run (and use up) its tile entrance animation off screen, leaving the real tiles invisible.
     */
    private void drawRows(RecyclerView rv) {
        for (int i = 0; i < rv.getItemDecorationCount(); i++) {
            rv.getItemDecorationAt(i).onDraw(smallCanvas, rv, state);
        }
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (child.getVisibility() != VISIBLE) continue;
            smallCanvas.save();
            smallCanvas.translate(child.getLeft() + child.getTranslationX(), child.getTop() + child.getTranslationY());
            child.draw(smallCanvas);
            smallCanvas.restore();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (amount <= 0f || small == null) return;
        int alpha = Math.round(255 * amount * SOFTNESS);
        dst.set(0, 0, getWidth(), getHeight());
        image.setAlpha(alpha);
        // The apps fade out towards the top of the screen instead of ending in a hard edge like a panel.
        int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        canvas.drawBitmap(small, null, dst, image);
        canvas.drawRect(dst, fade);
        canvas.restoreToCount(layer);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fade.setShader(new android.graphics.LinearGradient(0, 0, 0, h * 0.6f, 0x00000000, 0xFF000000,
                android.graphics.Shader.TileMode.CLAMP));
    }

    /** One horizontal and one vertical running-sum box pass over ARGB pixels. */
    private static void boxBlur(int[] px, int[] tmp, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = px[row + clamp(i, w)];
                sa += c >>> 24; sr += (c >> 16) & 0xFF; sg += (c >> 8) & 0xFF; sb += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                tmp[row + x] = (sa / div) << 24 | (sr / div) << 16 | (sg / div) << 8 | (sb / div);
                int out = px[row + clamp(x - r, w)], in = px[row + clamp(x + r + 1, w)];
                sa += (in >>> 24) - (out >>> 24);
                sr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                sg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                sb += (in & 0xFF) - (out & 0xFF);
            }
        }
        for (int x = 0; x < w; x++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = tmp[clamp(i, h) * w + x];
                sa += c >>> 24; sr += (c >> 16) & 0xFF; sg += (c >> 8) & 0xFF; sb += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                px[y * w + x] = (sa / div) << 24 | (sr / div) << 16 | (sg / div) << 8 | (sb / div);
                int out = tmp[clamp(y - r, h) * w + x], in = tmp[clamp(y + r + 1, h) * w + x];
                sa += (in >>> 24) - (out >>> 24);
                sr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                sg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                sb += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static int clamp(int i, int n) {
        return i < 0 ? 0 : i >= n ? n - 1 : i;
    }
}
