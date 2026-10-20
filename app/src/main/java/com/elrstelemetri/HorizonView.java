package com.elrstelemetri;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Yapay ufuk: roll ile döner, pitch ile kayar; üstte pusula şeridi. */
public class HorizonView extends View {

    private float pitch, roll, heading;
    private boolean stale = true;

    private final Paint sky = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plane = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tape = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();

    public HorizonView(Context c) {
        super(c);
        line.setColor(Color.WHITE);
        line.setStrokeWidth(2.5f);
        plane.setColor(Color.rgb(255, 210, 0));
        plane.setStrokeWidth(7);
        plane.setStrokeCap(Paint.Cap.ROUND);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        tape.setColor(Color.argb(150, 0, 0, 0));
    }

    public void set(double pitchDeg, double rollDeg, double headingDeg, boolean isStale) {
        pitch = (float) pitchDeg;
        roll = (float) rollDeg;
        heading = (float) ((headingDeg % 360 + 360) % 360);
        stale = isStale;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        rect.set(0, 0, w, h);
        clip.reset();
        float r = Math.min(w, h) * 0.08f;
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float pxPerDeg = h / 50f;  // ±25° pitch görünür

        sky.setColor(stale ? Color.rgb(55, 58, 64) : Color.rgb(38, 120, 210));
        ground.setColor(stale ? Color.rgb(38, 38, 42) : Color.rgb(120, 78, 36));

        c.save();
        c.clipPath(clip);
        c.save();
        c.rotate(-roll, cx, cy);
        float horizonY = cy + pitch * pxPerDeg;
        float big = Math.max(w, h) * 2;
        c.drawRect(cx - big, cy - big, cx + big, horizonY, sky);
        c.drawRect(cx - big, horizonY, cx + big, cy + big, ground);
        c.drawLine(cx - big, horizonY, cx + big, horizonY, line);

        text.setTextSize(h / 14f);
        for (int d = -20; d <= 20; d += 10) {
            if (d == 0) continue;
            float y = horizonY - d * pxPerDeg;
            float half = w * (d % 20 == 0 ? 0.14f : 0.08f);
            c.drawLine(cx - half, y, cx + half, y, line);
            c.drawText(String.valueOf(Math.abs(d)), cx + half + h / 14f, y + h / 40f, text);
        }
        c.restore();

        // Pusula şeridi
        float tapeH = h * 0.18f;
        c.drawRect(0, 0, w, tapeH, tape);
        text.setTextSize(tapeH * 0.45f);
        float pxPerHdg = w / 90f;  // şeritte ±45° görünür
        for (int d = -60; d <= 60; d += 5) {
            float a = Math.round(heading / 5f) * 5 + d;
            float x = cx + (a - heading) * pxPerHdg;
            if (x < 0 || x > w) continue;
            int hd = (int) ((a % 360 + 360) % 360);
            boolean major = hd % 30 == 0;
            c.drawLine(x, tapeH, x, tapeH - (major ? tapeH * 0.35f : tapeH * 0.18f), line);
            if (major) {
                String lbl = hd == 0 ? "K" : hd == 90 ? "D" : hd == 180 ? "G" : hd == 270 ? "B" : String.valueOf(hd / 10);
                c.drawText(lbl, x, tapeH * 0.5f, text);
            }
        }
        text.setTextSize(tapeH * 0.55f);
        String hdgTxt = stale ? "---" : String.format(java.util.Locale.US, "%03.0f", heading);
        float bw = text.measureText("000") * 0.75f;
        tape.setColor(Color.argb(230, 10, 12, 16));
        c.drawRect(cx - bw, 0, cx + bw, tapeH * 1.05f, tape);
        tape.setColor(Color.argb(150, 0, 0, 0));
        c.drawText(hdgTxt, cx, tapeH * 0.8f, text);

        // Sabit uçak sembolü
        c.drawLine(cx - w * 0.22f, cy, cx - w * 0.07f, cy, plane);
        c.drawLine(cx - w * 0.07f, cy, cx - w * 0.035f, cy + h * 0.05f, plane);
        c.drawLine(cx + w * 0.07f, cy, cx + w * 0.22f, cy, plane);
        c.drawLine(cx + w * 0.07f, cy, cx + w * 0.035f, cy + h * 0.05f, plane);
        c.drawCircle(cx, cy, 5, plane);

        if (stale) {
            text.setTextSize(h / 9f);
            c.drawText("ATTITUDE YOK", cx, cy + h * 0.3f, text);
        }
        c.restore();
    }
}
