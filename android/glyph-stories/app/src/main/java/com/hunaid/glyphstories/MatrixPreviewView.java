package com.hunaid.glyphstories;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** On-screen mirror of the Glyph Matrix: lit LEDs filled white, unlit LEDs outlined. */
public class MatrixPreviewView extends View {

    /** LEDs per row on the round 13x13 Phone (4a) Pro matrix (from Nothing's LED allocation diagram). */
    private static final int[] ROW_WIDTHS_13 = {5, 9, 11, 11, 13, 13, 13, 13, 13, 11, 11, 9, 5};

    private final Paint on = new Paint();
    private final Paint off = new Paint();
    private int size = 13;
    private int[] frame = new int[13 * 13];

    public MatrixPreviewView(Context c, AttributeSet a) {
        super(c, a);
        on.setColor(Color.WHITE);
        on.setStyle(Paint.Style.FILL);
        off.setColor(Color.WHITE);
        off.setStyle(Paint.Style.STROKE);
        off.setStrokeWidth(1f);
        off.setAlpha(90);
    }

    public void setFrame(int[] f, int n) {
        frame = f;
        size = n;
        invalidate();
    }

    private boolean exists(int x, int y) {
        if (size != 13) return true;
        int w = ROW_WIDTHS_13[y];
        int start = (13 - w) / 2;
        return x >= start && x < start + w;
    }

    @Override
    protected void onMeasure(int w, int h) {
        int s = MeasureSpec.getSize(w);
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cell = getWidth() / (float) size;
        float gap = Math.max(2f, cell * 0.18f);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!exists(x, y)) continue;
                float l = x * cell + gap / 2, t = y * cell + gap / 2;
                float r = l + cell - gap, b = t + cell - gap;
                int i = y * size + x;
                int v = frame != null && i < frame.length ? frame[i] : 0;
                if (v > 0) {
                    // Show sub-pixel brightness as white at partial opacity
                    on.setAlpha(Math.max(40, Math.min(255, Math.round(255f * (float) Math.sqrt(v / 4095f)))));
                    canvas.drawRect(l, t, r, b, on);
                } else {
                    canvas.drawRect(l, t, r, b, off);
                }
            }
        }
    }
}
