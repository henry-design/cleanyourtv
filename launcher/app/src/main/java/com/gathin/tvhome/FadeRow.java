package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A horizontal row that runs to the screen edge and fades out there while more cards wait off screen, and fades
 * in at the left once it has scrolled. The fade erases the cards themselves, so the video behind shows through:
 * no colour is painted.
 */
public class FadeRow extends RecyclerView {

    private final Paint erase = new Paint();
    private final float length;
    private LinearGradient rightShader, leftShader;
    private int shaderWidth = -1;

    public FadeRow(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        erase.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        length = 140 * getResources().getDisplayMetrics().density;
    }

    // dispatchDraw, not draw: a RecyclerView with overscroll off and no decorations skips draw() entirely.
    @Override
    protected void dispatchDraw(Canvas canvas) {
        boolean right = canScrollHorizontally(1), left = canScrollHorizontally(-1);
        if (!right && !left) {
            super.dispatchDraw(canvas);
            return;
        }
        int w = getWidth(), h = getHeight();
        if (shaderWidth != w) {
            shaderWidth = w;
            rightShader = new LinearGradient(w - length, 0, w, 0, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
            float edge = Math.max(1f, getPaddingLeft() - 16 * getResources().getDisplayMetrics().density);
            leftShader = new LinearGradient(0, 0, edge, 0, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP);
        }
        int layer = canvas.saveLayer(0, 0, w, h, null);
        super.dispatchDraw(canvas);
        if (right) {
            erase.setShader(rightShader);
            canvas.drawRect(w - length, 0, w, h, erase);
        }
        if (left) {
            erase.setShader(leftShader);
            canvas.drawRect(0, 0, getPaddingLeft(), h, erase);
        }
        canvas.restoreToCount(layer);
    }
}
