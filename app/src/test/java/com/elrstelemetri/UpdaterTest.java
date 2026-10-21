package com.elrstelemetri;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class UpdaterTest {

    @Test
    public void comparesVersionsNumerically() {
        assertTrue(Updater.compareVersions("1.1", "1.0") > 0);
        assertTrue(Updater.compareVersions("1.10", "1.9") > 0);
        assertTrue(Updater.compareVersions("2.0", "1.15") > 0);
        assertEquals(0, Updater.compareVersions("1.0", "1.0.0"));
        assertTrue(Updater.compareVersions("1.0", "1.0.1") < 0);
        assertTrue(Updater.compareVersions("1.2-beta", "1.1") > 0);
    }

    @Test
    public void parsesGithubLatestRelease() throws Exception {
        String json = "{\"tag_name\":\"v1.1\",\"html_url\":\"https://github.com/x/y/releases/tag/v1.1\","
                + "\"body\":\"**Notes**\",\"assets\":[{\"name\":\"gcs.png\",\"browser_download_url\":\"https://a/gcs.png\",\"size\":5},"
                + "{\"name\":\"ELRS-Telemetry-v1.1.apk\",\"browser_download_url\":\"https://a/app.apk\",\"size\":902279}]}";
        Updater.Release r = Updater.parse(json);
        assertEquals("v1.1", r.tag);
        assertEquals("1.1", r.version);
        assertEquals("https://a/app.apk", r.apkUrl);
        assertEquals(902279, r.apkSize);
    }

    @Test
    public void releaseWithoutApkIsIgnored() throws Exception {
        assertNull(Updater.parse("{\"tag_name\":\"v1.1\",\"assets\":[]}"));
    }
}
