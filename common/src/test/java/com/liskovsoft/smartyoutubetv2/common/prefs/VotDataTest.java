package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.content.Context;
import android.content.SharedPreferences;

import com.liskovsoft.smartyoutubetv2.common.vot.VotConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VotDataTest {
    private SharedPreferences prefs;
    private VotData data;

    @Before
    public void setUp() throws Exception {
        Context context = RuntimeEnvironment.application;
        prefs = context.getSharedPreferences("vot_data", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        Constructor<VotData> constructor = VotData.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        data = constructor.newInstance(context);
    }

    @After
    public void tearDown() {
        prefs.edit().clear().commit();
    }

    @Test
    public void migratesOnlyLegacyRelayBases() {
        for (String base : new String[]{"v.n501.site", "https://v.n501.site", "http://v.n501.site"}) {
            for (String suffix : new String[]{"", "/", "///"}) {
                for (String padding : new String[]{"", " \t\r\n"}) {
                    String stored = padding + base + suffix + padding;
                    prefs.edit().putString("relay_url", stored).commit();
                    assertEquals(stored, VotConfig.RELAY_BASE_URL, data.getRelayUrl());
                }
            }
        }
    }

    @Test
    public void preservesCustomUrlsExactly() {
        for (String stored : new String[]{
                "", " \t", VotConfig.RELAY_BASE_URL,
                "https://custom.example/", " custom.example/// ",
                "https://v.n501.site.evil.example", "v.n501.site.evil.example/",
                "https://v.n501.site/path/", "v.n501.site/path",
                "http://v.n501.site/path", "https://v.n501.site:443/",
                "https://v.n501.site?query", "https://v.n501.site#fragment",
                "https://v.n501.site@evil.example/", "https://evil.example/v.n501.site"
        }) {
            prefs.edit().putString("relay_url", stored).commit();
            assertEquals(stored, stored, data.getRelayUrl());
        }
    }

    @Test
    public void usesDefaultWithoutMutatingAnyStoredSettings() {
        assertEquals(VotConfig.RELAY_BASE_URL, data.getRelayUrl());
        prefs.edit().putString("relay_url", " v.n501.site/// ")
                .putString("yandex_oauth_token", "test-token")
                .putBoolean("use_lively_voice", true)
                .putInt("original_volume_percent", 23)
                .putInt("translation_volume_percent", 87)
                .putBoolean("auto_translate_enabled", true)
                .putBoolean("prefer_youtube_auto_dub", true)
                .putString("unrelated_setting", "unchanged").commit();
        Map<String, ?> before = new HashMap<>(prefs.getAll());
        assertEquals(VotConfig.RELAY_BASE_URL, data.getRelayUrl());
        assertEquals(VotConfig.RELAY_BASE_URL, data.getRelayUrl());
        assertEquals("test-token", data.getOAuthToken());
        assertEquals(before, prefs.getAll());
    }
    @Test
    public void pauseDefaultsOffAndPersists() {
        assertEquals(false, data.isPauseWhilePreparingEnabled());
        data.setPauseWhilePreparingEnabled(true);
        assertEquals(true, data.isPauseWhilePreparingEnabled());
    }

    @Test
    public void resetOnlyPlaybackPreferencesAndIsIdempotent() {
        data.setOAuthToken("secret-token");
        data.setRelayUrl("https://custom.example");
        prefs.edit().putString("unrelated_setting", "kept").commit();
        data.setLivelyVoiceEnabled(true);
        data.setAutoTranslateEnabled(true);
        data.setPreferYoutubeAutoDub(true);
        data.setPauseWhilePreparingEnabled(true);
        data.setOriginalVolumePercent(90);
        data.setTranslationVolumePercent(20);
        for (int i = 0; i < 2; i++) {
            data.resetPlaybackPreferences();
            assertEquals(false, data.isLivelyVoiceEnabled());
            assertEquals(false, prefs.getBoolean("use_lively_voice", true));
            assertEquals(false, data.isAutoTranslateEnabled());
            assertEquals(false, data.isPreferYoutubeAutoDub());
            assertEquals(false, data.isPauseWhilePreparingEnabled());
            assertEquals(15, data.getOriginalVolumePercent());
            assertEquals(100, data.getTranslationVolumePercent());
            assertEquals("secret-token", data.getOAuthToken());
            assertEquals("https://custom.example", data.getRelayUrl());
            assertEquals("kept", prefs.getString("unrelated_setting", ""));
        }
    }
}
