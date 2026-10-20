package com.elrstelemetri;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.CopyrightOverlay;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.ScaleBarOverlay;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** osmdroid harita: katmanlar, uçuş izi, uçak/ev katmanı, takip modu. */
public class GcsMap {

    private static final int MAX_TRACK = 20000;
    private static final double MIN_TRACK_STEP_M = 1.5;

    /** Esri World Imagery; URL sırası z/y/x. */
    static final OnlineTileSourceBase SATELLITE = new OnlineTileSourceBase(
            "EsriWorldImagery", 1, 19, 256, ".jpg",
            new String[]{"https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"},
            "Uydu © Esri, Maxar, Earthstar Geographics") {
        @Override
        public String getTileURLString(long index) {
            return getBaseUrl() + MapTileIndex.getZoom(index) + "/" + MapTileIndex.getY(index) + "/" + MapTileIndex.getX(index);
        }
    };

    public final MapView view;
    private final PlaneOverlay planeOverlay;
    private final Polyline track;
    private final Polyline trackShadow;
    private final List<GeoPoint> points = new ArrayList<>();

    private boolean follow = true;
    private boolean zoomedToPlane;
    private Runnable onFollowChanged;

    /** MapView oluşturulmadan önce çağrılmalı. */
    public static void configure(Context c) {
        IConfigurationProvider cfg = Configuration.getInstance();
        cfg.load(c, c.getSharedPreferences("osmdroid", Context.MODE_PRIVATE));
        cfg.setUserAgentValue("ELRSTelemetri/" + c.getPackageName());
        File base = new File(c.getCacheDir(), "osmdroid");
        cfg.setOsmdroidBasePath(base);
        cfg.setOsmdroidTileCache(new File(base, "tiles"));
        cfg.setTileFileSystemCacheMaxBytes(600L * 1024 * 1024);
        cfg.setTileFileSystemCacheTrimBytes(500L * 1024 * 1024);
    }

    @SuppressLint("ClickableViewAccessibility")
    public GcsMap(Context c, float density) {
        view = new MapView(c);
        view.setMultiTouchControls(true);
        view.setTilesScaledToDpi(true);
        view.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        view.setHorizontalMapRepetitionEnabled(false);
        view.setVerticalMapRepetitionEnabled(false);
        view.setMinZoomLevel(3.0);
        view.setMaxZoomLevel(20.0);

        trackShadow = new Polyline(view);
        Paint sp = trackShadow.getOutlinePaint();
        sp.setColor(Color.argb(160, 0, 0, 0));
        sp.setStrokeWidth(7 * density);
        sp.setStrokeCap(Paint.Cap.ROUND);
        sp.setStrokeJoin(Paint.Join.ROUND);

        track = new Polyline(view);
        Paint tp = track.getOutlinePaint();
        tp.setColor(Color.rgb(255, 64, 160));
        tp.setStrokeWidth(3.5f * density);
        tp.setStrokeCap(Paint.Cap.ROUND);
        tp.setStrokeJoin(Paint.Join.ROUND);

        planeOverlay = new PlaneOverlay(density);

        ScaleBarOverlay scale = new ScaleBarOverlay(view);
        scale.setAlignBottom(true);
        scale.setScaleBarOffset((int) (12 * density), (int) (64 * density));

        view.getOverlays().add(trackShadow);
        view.getOverlays().add(track);
        view.getOverlays().add(planeOverlay);
        view.getOverlays().add(scale);
        view.getOverlays().add(new CopyrightOverlay(c));

        // Kullanıcı haritayı sürüklerse takip modunu kapat
        view.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE && follow) {
                follow = false;
                if (onFollowChanged != null) onFollowChanged.run();
            }
            return false;
        });
    }

    public void setOnFollowChanged(Runnable r) {
        onFollowChanged = r;
    }

    public void setMapType(int type) {
        ITileSource src;
        switch (type) {
            case Settings.MAP_STREET: src = TileSourceFactory.MAPNIK; break;
            case Settings.MAP_TOPO: src = TileSourceFactory.OpenTopo; break;
            default: src = SATELLITE; break;
        }
        view.setTileSource(src);
    }

    public void startAt(double lat, double lon, double zoom) {
        view.getController().setZoom(zoom);
        view.getController().setCenter(new GeoPoint(lat, lon));
    }

    public boolean isFollowing() {
        return follow;
    }

    public void setFollow(boolean f) {
        follow = f;
        if (onFollowChanged != null) onFollowChanged.run();
    }

    /** Her ekran yenilemesinde çağrılır. */
    public void update(boolean hasFix, double lat, double lon, double heading, boolean stale,
                       boolean homeSet, double homeLat, double homeLon) {
        planeOverlay.setHome(homeSet, homeLat, homeLon);
        if (hasFix) {
            planeOverlay.setPlane(lat, lon, heading, stale);
            if (!zoomedToPlane) {
                zoomedToPlane = true;
                view.getController().setZoom(17.0);
                view.getController().setCenter(new GeoPoint(lat, lon));
            } else if (follow) {
                view.getController().setCenter(new GeoPoint(lat, lon));
            }
        }
        view.invalidate();
    }

    public void addTrackPoint(double lat, double lon) {
        GeoPoint gp = new GeoPoint(lat, lon);
        if (!points.isEmpty() && points.get(points.size() - 1).distanceToAsDouble(gp) < MIN_TRACK_STEP_M) return;
        points.add(gp);
        if (points.size() > MAX_TRACK) {
            points.subList(0, MAX_TRACK / 4).clear();
            track.setPoints(points);
            trackShadow.setPoints(points);
        } else {
            track.addPoint(gp);
            trackShadow.addPoint(gp);
        }
    }

    public void clearTrack() {
        points.clear();
        track.setPoints(points);
        trackShadow.setPoints(points);
        view.invalidate();
    }

    public int trackSize() {
        return points.size();
    }

    public void centerOn(double lat, double lon) {
        view.getController().animateTo(new GeoPoint(lat, lon));
    }

    public void onResume() {
        view.onResume();
    }

    public void onPause() {
        view.onPause();
    }
}
