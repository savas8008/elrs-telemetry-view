package com.elrstelemetri;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.tileprovider.cachemanager.CacheManager;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy;
import org.osmdroid.util.BoundingBox;
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

/**
 * osmdroid map: layers, flight track, aircraft/home overlay, follow mode and offline downloads.
 *
 * The phone is on the backpack WiFi during flight, which has no internet. Tiles that were viewed
 * or downloaded beforehand are served from the on-device cache, so the map works offline.
 */
public class GcsMap {

    private static final int MAX_TRACK = 20000;
    private static final double MIN_TRACK_STEP_M = 1.5;
    public static final int MAX_DOWNLOAD_TILES = 30000;
    public static final int APPROX_TILE_KB = 25;

    /** Esri ArcGIS Online basemap; URL order is z/y/x. At most 2 parallel requests. */
    static OnlineTileSourceBase esri(String name, String service, String copyright) {
        return new OnlineTileSourceBase(name, 1, 19, 256, ".jpg",
                new String[]{"https://server.arcgisonline.com/ArcGIS/rest/services/" + service + "/MapServer/tile/"},
                copyright, new TileSourcePolicy(2, 0)) {
            @Override
            public String getTileURLString(long index) {
                return getBaseUrl() + MapTileIndex.getZoom(index) + "/" + MapTileIndex.getY(index) + "/" + MapTileIndex.getX(index);
            }
        };
    }

    static final OnlineTileSourceBase SATELLITE = esri("EsriWorldImagery", "World_Imagery",
            "© Esri, Maxar, Earthstar");
    static final OnlineTileSourceBase TOPO = esri("EsriWorldTopo", "World_Topo_Map",
            "© Esri and contributors");
    static final OnlineTileSourceBase STREET = esri("EsriWorldStreet", "World_Street_Map",
            "© Esri and contributors");

    public final MapView view;
    private final PlaneOverlay planeOverlay;
    private final Polyline track;
    private final Polyline trackShadow;
    private final List<GeoPoint> points = new ArrayList<>();

    private boolean follow = true;
    private boolean zoomedToPlane;
    private Runnable onFollowChanged;
    private CacheManager downloader;

    /** Must be called before the MapView is created. */
    public static void configure(Context c) {
        IConfigurationProvider cfg = Configuration.getInstance();
        cfg.load(c, c.getSharedPreferences("osmdroid", Context.MODE_PRIVATE));
        cfg.setUserAgentValue("ELRSTelemetry/" + c.getPackageName());
        File base = new File(c.getFilesDir(), "osmdroid");  // files dir: not cleared like the cache dir
        cfg.setOsmdroidBasePath(base);
        cfg.setOsmdroidTileCache(new File(base, "tiles"));
        cfg.setTileFileSystemCacheMaxBytes(2000L * 1024 * 1024);
        cfg.setTileFileSystemCacheTrimBytes(1700L * 1024 * 1024);
        // Treat cached tiles as valid for a year so offline areas are not considered stale
        cfg.setExpirationOverrideDuration(365L * 24 * 3600 * 1000);
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
        scale.setScaleBarOffset((int) (10 * density), (int) (20 * density));
        scale.setMaxLength(0.8f);  // inches; keeps it clear of the centred button bar

        CopyrightOverlay copyright = new CopyrightOverlay(c);
        // bottom-left under the scale bar: the bottom centre/right is used by the buttons
        copyright.setAlignRight(false);
        copyright.setAlignBottom(true);
        copyright.setTextSize(9);
        copyright.setOffset((int) (10 * density), (int) (4 * density));

        view.getOverlays().add(trackShadow);
        view.getOverlays().add(track);
        view.getOverlays().add(planeOverlay);
        view.getOverlays().add(scale);
        view.getOverlays().add(copyright);

        // Dragging the map turns follow mode off
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

    public static ITileSource source(int type) {
        switch (type) {
            case Settings.MAP_TOPO: return TOPO;
            case Settings.MAP_STREET: return STREET;
            case Settings.MAP_OSM: return TileSourceFactory.MAPNIK;
            default: return SATELLITE;
        }
    }

    /** OpenStreetMap's tile policy forbids bulk downloads; the Esri layers allow caching. */
    public static boolean supportsOffline(int type) {
        return type != Settings.MAP_OSM;
    }

    public void setMapType(int type) {
        view.setTileSource(source(type));
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

    /** Called on every screen refresh. */
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

    // ---------------------------------------------------------------- Offline maps

    public int currentZoom() {
        return (int) Math.floor(view.getZoomLevelDouble());
    }

    /** Lowest zoom included in offline downloads: an overview costs only a few tiles. */
    public static final int OFFLINE_MIN_ZOOM = 8;

    /** Map area covered by a rectangle given in map-view pixels. */
    public BoundingBox areaForPixels(int left, int top, int right, int bottom) {
        org.osmdroid.views.Projection p = view.getProjection();
        org.osmdroid.api.IGeoPoint nw = p.fromPixels(left, top);
        org.osmdroid.api.IGeoPoint se = p.fromPixels(right, bottom);
        return new BoundingBox(nw.getLatitude(), se.getLongitude(), se.getLatitude(), nw.getLongitude());
    }

    private CacheManager cacheManager() throws Exception {
        if (downloader == null) downloader = new CacheManager(view);
        return downloader;
    }

    /** Number of tiles needed for {@code area} from {@link #OFFLINE_MIN_ZOOM} up to {@code maxZoom}. */
    public int tilesForArea(BoundingBox area, int maxZoom) {
        try {
            return cacheManager().possibleTilesInArea(area, Math.min(OFFLINE_MIN_ZOOM, maxZoom), maxZoom);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Prepares a cache checker for the current layer. Create it on the UI thread, then call
     * {@link #countCachedTiles} on a background thread.
     */
    public CacheManager newCacheChecker() {
        return new CacheManager(view);
    }

    /**
     * Counts how many tiles of {@code area} (zoom {@link #OFFLINE_MIN_ZOOM}..maxZoom) are already in the
     * offline cache. Blocking (one database lookup per tile); returns -1 if {@code cancelled} turns true.
     */
    public static int countCachedTiles(CacheManager checker, BoundingBox area, int maxZoom,
                                       java.util.function.BooleanSupplier cancelled) {
        int cached = 0;
        for (long tile : CacheManager.getTilesCoverage(area, Math.min(OFFLINE_MIN_ZOOM, maxZoom), maxZoom)) {
            if (cancelled.getAsBoolean()) return -1;
            if (checker.checkTile(tile)) cached++;
        }
        return cached;
    }

    /**
     * Downloads {@code area} for offline use. Tiles already in the cache are skipped (osmdroid's
     * CacheManager.loadTile checks the cache first), so downloading an area again only fetches what
     * is missing; nothing that was downloaded before is deleted. Returns an error message or null.
     */
    public String downloadArea(BoundingBox area, int maxZoom, CacheManager.CacheManagerCallback cb) {
        try {
            downloader = new CacheManager(view);
            downloader.downloadAreaAsyncNoUI(view.getContext(), area, Math.min(OFFLINE_MIN_ZOOM, maxZoom), maxZoom, cb);
            return null;
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : e.toString();
        }
    }

    public void cancelDownload() {
        if (downloader != null) downloader.cancelAllJobs();
    }

    /** Size of the on-device tile cache in bytes. */
    public static long cacheSizeBytes(Context c) {
        return dirSize(new File(new File(c.getFilesDir(), "osmdroid"), "tiles"));
    }

    private static long dirSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long s = 0;
        File[] list = f.listFiles();
        if (list != null) for (File x : list) s += dirSize(x);
        return s;
    }

    public void onResume() {
        view.onResume();
    }

    public void onPause() {
        view.onPause();
    }
}
