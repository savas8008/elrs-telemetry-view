package com.elrstelemetri;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Basit yapay ufuk: roll ile döner, pitch ile kayar. */
public class HorizonView extends View {

    private float pitch, roll, heading;
    private boolean stale = true;

    private final Paint sky = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plane = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public HorizonView(Context c) {
        super(c);
        line.setColor(Color.WHITE);
        line.setStrokeWidth(3);
        plane.setColor(Color.YELLOW);
        plane.setStrokeWidth(8);
        plane.setStrokeCap(Paint.Cap.ROUND);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
    }

    public void set(double pitchDeg, double rollDeg, double headingDeg, boolean isStale) {
        pitch = (float) pitchDeg;
        roll = (float) rollDeg;
        heading = (float) headingDeg;
        stale = isStale;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float pxPerDeg = h / 60f;  // ekranda ±30° pitch görünür

        sky.setColor(stale ? Color.rgb(60, 60, 60) : Color.rgb(40, 110, 200));
        ground.setColor(stale ? Color.rgb(40, 40, 40) : Color.rgb(130, 80, 30));

        c.save();
        c.clipRect(0, 0, w, h);
        c.rotate(-roll, cx, cy);
        float horizonY = cy + pitch * pxPerDeg;
        float big = Math.max(w, h) * 2;
        c.drawRect(cx - big, cy - big, cx + big, horizonY, sky);
        c.drawRect(cx - big, horizonY, cx + big, cy + big, ground);
        c.drawLine(cx - big, horizonY, cx + big, horizonY, line);

        text.setTextSize(h / 18f);
        for (int d = -30; d <= 30; d += 10) {
            if (d == 0) continue;
            float y = horizonY - d * pxPerDeg;
            float half = w * (d % 20 == 0 ? 0.12f : 0.07f);
            c.drawLine(cx - half, y, cx + half, y, line);
            c.drawText(String.valueOf(d), cx + half + h / 20f, y + h / 50f, text);
        }
        c.restore();

        // Sabit uçak sembolü
        c.drawLine(cx - w * 0.2f, cy, cx - w * 0.06f, cy, plane);
        c.drawLine(cx + w * 0.06f, cy, cx + w * 0.2f, cy, plane);
        c.drawCircle(cx, cy, 6, plane);

        text.setTextSize(h / 10f);
        String top = stale ? "VERİ YOK" : String.format("%03.0f°", (heading + 360) % 360);
        c.drawText(top, cx, h / 8f, text);
        text.setTextSize(h / 14f);
        c.drawText(String.format("P %.0f°   R %.0f°", pitch, roll), cx, h - h / 20f, text);
    }
}
