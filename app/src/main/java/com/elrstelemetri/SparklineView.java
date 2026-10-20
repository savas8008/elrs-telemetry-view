package com.elrstelemetri;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.Locale;

/** Halka tampondan son N örneği çizen küçük çizgi grafik. */
public class SparklineView extends View {

    private final String title;
    private final String unit;
    private final int color;
    private float[] data;
    private int count, pos;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final Path path = new Path();
    private final Path fillPath = new Path();

    public SparklineView(Context c, String title, String unit, int color) {
        super(c);
        this.title = title;
        this.unit = unit;
        this.color = color;
        line.setColor(color);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(3);
        line.setStrokeJoin(Paint.Join.ROUND);
        area.setColor(Color.argb(60, Color.red(color), Color.green(color), Color.blue(color)));
        text.setColor(Color.rgb(170, 175, 185));
        grid.setColor(Color.argb(40, 255, 255, 255));
    }

    public void set(float[] data, int count, int pos) {
        this.data = data;
        this.count = count;
        this.pos = pos;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float top = h * 0.3f, bottom = h - 3;
        text.setTextSize(h * 0.22f);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(Color.rgb(170, 175, 185));
        c.drawText(title, 4, h * 0.22f, text);
        c.drawLine(0, bottom, w, bottom, grid);
        if (data == null || count < 2) return;

        int n = data.length;
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE, last = Float.NaN;
        for (int i = 0; i < count; i++) {
            float v = data[(pos - count + i + n) % n];
            if (Float.isNaN(v)) continue;
            min = Math.min(min, v);
            max = Math.max(max, v);
            last = v;
        }
        if (Float.isNaN(last)) return;
        if (max - min < 1e-3f) {
            max += 0.5f;
            min -= 0.5f;
        }

        path.reset();
        fillPath.reset();
        boolean started = false;
        float firstX = 0, lastX = 0;
        for (int i = 0; i < count; i++) {
            float v = data[(pos - count + i + n) % n];
            if (Float.isNaN(v)) continue;
            float x = w * (n - count + i) / (float) (n - 1);  // en yeni örnek sağ kenarda
            float y = bottom - (v - min) / (max - min) * (bottom - top);
            if (!started) {
                path.moveTo(x, y);
                fillPath.moveTo(x, bottom);
                fillPath.lineTo(x, y);
                firstX = x;
                started = true;
            } else {
                path.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
            lastX = x;
        }
        fillPath.lineTo(lastX, bottom);
        fillPath.lineTo(firstX, bottom);
        fillPath.close();
        c.drawPath(fillPath, area);
        c.drawPath(path, line);

        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(color);
        text.setTextSize(h * 0.24f);
        c.drawText(String.format(Locale.US, "%.1f %s", last, unit), w - 4, h * 0.22f, text);
        text.setColor(Color.rgb(140, 145, 155));
        text.setTextSize(h * 0.17f);
        c.drawText(String.format(Locale.US, "↑%.1f ↓%.1f", max, min), w - 4, h * 0.42f, text);
    }
}
