package com.elrstelemetri;

import static org.junit.Assume.assumeTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;

/**
 * Renders the GCS screen and the settings screen to PNG: the app is started, backpack-style
 * UDP packets (CRSF inside MSP v2) are sent to localhost, then the views are drawn to bitmaps.
 * Only runs with -Pscreenshots=true.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w820dp-h390dp-land-xhdpi")
public class GcsScreenshotTest {

    static byte[] crsf(int type, int... payload) {
        return CrsfParserTest.frame(type, payload);
    }

    static byte[] msp(byte[] crsf) {
        byte[] dg = new byte[8 + crsf.length + 1];
        dg[0] = '$'; dg[1] = 'X'; dg[2] = '<';
        dg[4] = 0x11;
        dg[6] = (byte) crsf.length;
        System.arraycopy(crsf, 0, dg, 8, crsf.length);
        dg[dg.length - 1] = (byte) CrsfParser.crc8(dg, 3, 5 + crsf.length);
        return dg;
    }

    static int[] be(long v, int n) {
        return CrsfParserTest.be(v, n);
    }

    static void render(View root, int w, int h, String name) throws Exception {
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bmp));
        File out = new File("build/screenshots/" + name);
        out.getParentFile().mkdirs();
        try (FileOutputStream fo = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
        }
        System.out.println("Screenshot: " + out.getAbsolutePath() + " " + w + "x" + h);
    }

    @Test
    public void gcsScreen() throws Exception {
        assumeTrue(Boolean.getBoolean("screenshots"));

        Context ctx = RuntimeEnvironment.getApplication();
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putBoolean("autoRecord", false).putBoolean("voice", false).putBoolean("vibrate", false)
                // low max-altitude threshold so the screenshot shows an active alarm
                .putLong("alarm.max_alt.value", Double.doubleToLongBits(110))
                .apply();

        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = ctl.get();

        DatagramSocket tx = new DatagramSocket();
        InetAddress lo = InetAddress.getLoopbackAddress();
        double homeLat = 37.0742, homeLon = 36.2464;
        for (int i = 0; i < 40; i++) {
            double t = i / 40.0;
            double lat = homeLat + 0.0025 * Math.sin(t * 5) + 0.002 * t;
            double lon = homeLon + 0.004 * t + 0.0015 * Math.cos(t * 4);
            byte[][] frames = {
                    crsf(CrsfParser.LINK_STATS, 52, 58, 96 - i / 4, 9, 0, 7, 3, 61, 100, 8),
                    crsf(CrsfParser.BATTERY, CrsfParserTest.join(be(158 - i / 8, 2), be(124, 2), be(820 + i * 3, 3), new int[]{64})),
                    crsf(CrsfParser.GPS, CrsfParserTest.join(be(Math.round(lat * 1e7), 4), be(Math.round(lon * 1e7), 4),
                            be(612, 2), be(6200, 2), be(1000 + 95 + i, 2), new int[]{14})),
                    crsf(CrsfParser.ATTITUDE, CrsfParserTest.join(be(1047, 2), be((-2618) & 0xFFFF, 2), be(10821, 2))),
                    crsf(CrsfParser.FLIGHT_MODE, 'A', 'N', 'G', 'L', 0),
                    crsf(CrsfParser.VARIO, be(140, 2)),
                    crsf(CrsfParser.BARO_ALT, be(10000 + 1080 + i * 10, 2)),
            };
            for (byte[] f : frames) {
                byte[] dg = msp(f);
                tx.send(new DatagramPacket(dg, dg.length, lo, 14550));
            }
            Thread.sleep(60);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        }
        Thread.sleep(200);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        tx.close();

        View root = a.getWindow().getDecorView();
        int w = root.getWidth() > 0 ? root.getWidth() : 1640;
        int h = root.getHeight() > 0 ? root.getHeight() : 780;
        render(root, w, h, "gcs.png");

        a.setVideoMode(Settings.VIDEO_REPLACE_MAP);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        render(root, w, h, "gcs-full.png");
        a.setVideoMode(Settings.VIDEO_PANEL);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));

        Dialog settings = a.showSettings();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        render(settings.getWindow().getDecorView(), w, h, "settings.png");
        settings.dismiss();

        ctl.pause().stop().destroy();
    }
}
