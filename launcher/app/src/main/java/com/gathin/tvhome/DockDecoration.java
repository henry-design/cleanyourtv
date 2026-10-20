package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the tvOS frosted-glass band behind the first (dock) row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float shaderTop = Float.NaN, shaderBottom = Float.NaN;

    DockDecoration(int dockSize, float density) {
        this.dockSize = dockSize;
        this.density = density;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(0x40FFFFFF);
        sheen.setStyle(Paint.Style.STROKE);
        sheen.setStrokeWidth(1.5f * density);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            View card = child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        float pad = 14 * density;
        rect.inset(-pad, -pad);
        float radius = 26 * density;
        // No live blur on Android 11: frosted glass is a milky gradient, a bright top rim and a faint edge.
        if (rect.top != shaderTop || rect.bottom != shaderBottom) {
            shaderTop = rect.top;
            shaderBottom = rect.bottom;
            band.setShader(new android.graphics.LinearGradient(0, rect.top, 0, rect.bottom,
                    new int[]{0x47FFFFFF, 0x24FFFFFF, 0x1AFFFFFF}, new float[]{0f, 0.5f, 1f},
                    android.graphics.Shader.TileMode.CLAMP));
            sheen.setShader(new android.graphics.LinearGradient(0, rect.top, 0, rect.top + radius,
                    0x80FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
        }
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);
        c.drawRoundRect(rect, radius, radius, sheen);
    }
}
