package com.elrstelemetri;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

/** Haritada uçak simgesi (yöne göre döner), ev noktası ve evden uçağa kesikli çizgi. */
public class PlaneOverlay extends Overlay {

    private final float density;
    private final Path plane = new Path();
    private final Paint planeFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint planeStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint homeFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint homeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint homeLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final GeoPoint planePos = new GeoPoint(0.0, 0.0);
    private final GeoPoint homePos = new GeoPoint(0.0, 0.0);
    private final Point pp = new Point();
    private final Point hp = new Point();

    private boolean hasPlane, hasHome, stale;
    private float heading;

    public PlaneOverlay(float density) {
        this.density = density;
        // Üstten görünüş uçak silueti, burun -y yönünde; birim boy = 1
        float[][] half = {
                {0f, -1f}, {0.09f, -0.86f}, {0.1f, -0.32f}, {0.98f, 0.02f}, {0.98f, 0.17f},
                {0.1f, 0.06f}, {0.07f, 0.6f}, {0.36f, 0.78f}, {0.36f, 0.9f}, {0f, 0.84f}};
        plane.moveTo(half[0][0], half[0][1]);
        for (int i = 1; i < half.length; i++) plane.lineTo(half[i][0], half[i][1]);
        for (int i = half.length - 2; i > 0; i--) plane.lineTo(-half[i][0], half[i][1]);
        plane.close();

        planeFill.setStyle(Paint.Style.FILL);
        planeStroke.setStyle(Paint.Style.STROKE);
        planeStroke.setColor(Color.rgb(20, 20, 24));
        planeStroke.setStrokeJoin(Paint.Join.ROUND);

        homeFill.setColor(Color.rgb(30, 200, 90));
        homeText.setColor(Color.WHITE);
        homeText.setTextAlign(Paint.Align.CENTER);
        homeText.setFakeBoldText(true);
        homeText.setTextSize(13 * density);

        homeLine.setColor(Color.argb(200, 255, 255, 255));
        homeLine.setStyle(Paint.Style.STROKE);
        homeLine.setStrokeWidth(2 * density);
        homeLine.setPathEffect(new DashPathEffect(new float[]{8 * density, 6 * density}, 0));

        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * density);
        ring.setColor(Color.WHITE);
    }

    public void setPlane(double lat, double lon, double headingDeg, boolean isStale) {
        planePos.setCoords(lat, lon);
        heading = (float) headingDeg;
        stale = isStale;
        hasPlane = true;
    }

    public void setHome(boolean set, double lat, double lon) {
        hasHome = set;
        if (set) homePos.setCoords(lat, lon);
    }

    @Override
    public void draw(Canvas c, MapView mv, boolean shadow) {
        if (shadow) return;
        Projection pj = mv.getProjection();

        if (hasHome) {
            pj.toPixels(homePos, hp);
            if (hasPlane) {
                pj.toPixels(planePos, pp);
                c.drawLine(hp.x, hp.y, pp.x, pp.y, homeLine);
            }
            float r = 11 * density;
            c.drawCircle(hp.x, hp.y, r, homeFill);
            c.drawCircle(hp.x, hp.y, r, ring);
            c.drawText("H", hp.x, hp.y + 4.5f * density, homeText);
        }

        if (hasPlane) {
            pj.toPixels(planePos, pp);
            float size = 22 * density;
            c.save();
            c.translate(pp.x, pp.y);
            c.rotate(heading + mv.getMapOrientation());
            c.scale(size, size);
            planeFill.setColor(stale ? Color.rgb(150, 150, 150) : Color.rgb(255, 214, 0));
            planeStroke.setStrokeWidth(2.2f * density / size);
            c.drawPath(plane, planeFill);
            c.drawPath(plane, planeStroke);
            c.restore();
        }
    }
}
