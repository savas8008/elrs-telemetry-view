package com.elrstelemetri;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Doluluk oranını renkle gösteren pil simgesi; içinde voltaj ve yüzde yazar. */
public class BatteryView extends View {

    private float fraction;          // 0..1
    private String label = "-";
    private int fillColor = Color.GRAY;
    private boolean stale = true;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF body = new RectF();
    private final RectF r = new RectF();

    public BatteryView(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(Color.argb(220, 255, 255, 255));
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        text.setShadowLayer(4, 0, 0, Color.BLACK);
    }

    public void set(float fraction, String label, int color, boolean isStale) {
        this.fraction = Math.max(0, Math.min(1, fraction));
        this.label = label;
        this.fillColor = color;
        this.stale = isStale;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float sw = Math.max(2, h * 0.07f);
        stroke.setStrokeWidth(sw);
        float nub = w * 0.04f;
        body.set(sw, sw, w - nub - sw, h - sw);
        float rad = h * 0.18f;

        fill.setColor(Color.argb(120, 0, 0, 0));
        c.drawRoundRect(body, rad, rad, fill);
        if (!stale) {
            float inset = sw * 1.4f;
            r.set(body.left + inset, body.top + inset,
                    body.left + inset + (body.width() - 2 * inset) * fraction, body.bottom - inset);
            fill.setColor(fillColor);
            c.drawRoundRect(r, rad * 0.6f, rad * 0.6f, fill);
        }
        c.drawRoundRect(body, rad, rad, stroke);
        fill.setColor(Color.argb(220, 255, 255, 255));
        c.drawRect(w - nub - sw, h * 0.32f, w - sw * 0.3f, h * 0.68f, fill);

        text.setTextSize(h * 0.48f);
        text.setColor(stale ? Color.GRAY : Color.WHITE);
        c.drawText(label, body.centerX(), body.centerY() + h * 0.17f, text);
    }
}
