package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.content.Context;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class PlayerTweaksDataTest {
    private static final String KEY = "video_player_tweaks_data";
    // Pre-VOT layout: retain the retired slots as well as distinct non-default values.
    private static final String LEGACY = String.join("%OB%",
            "true", "false", "true", "false", "true", "false", "null", "true", "false", "true",
            "false", "true", "false", "3", "null", "true", "false", "true", "false", "true",
            "false", "1.25", "true", "false", "true", "false", "2", "true", "false", "true",
            "false", "true", "true", "37", "true", "false", "true", "43", "true", "false",
            "true", "false", "true", "null", "false", "true", "false", "true", "null", "false",
            "true", "false", "true", "null", "false", "true", "false", "true", "false", "true", "false");

    private Context context;
    private AppPrefs prefs;
    private PlayerTweaksData data;
    private Object previousPrefs;
    private Field prefsInstance;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.application;
        prefsInstance = AppPrefs.class.getDeclaredField("sInstance");
        prefsInstance.setAccessible(true);
        previousPrefs = prefsInstance.get(null);
        prefsInstance.set(null, null);
        prefs = AppPrefs.instance(context);
        prefs.setProfileData(KEY, null);
    }

    @After
    public void tearDown() throws Exception {
        drainPersistence();
        if (data != null) {
            prefs.removeListener(data);
        }
        prefs.setProfileData(KEY, null);
        prefsInstance.set(null, previousPrefs);
    }

    @Test
    public void migratesLegacyButtonOnceAndKeepsAllExistingSlots() throws Exception {
        load(LEGACY);
        assertTrue(data.isPlayerButtonEnabled(PlayerTweaksData.PLAYER_BUTTON_VOICE_TRANSLATE));
        assertFalse(data.isLiveEdgeEnabled());
        assertLegacyGetters();

        data.persistNow();
        shadowOf(Looper.getMainLooper()).idle();
        String[] saved = storedSlots();
        assertEquals(63, saved.length);
        assertEquals("true", saved[61]);
        assertEquals("false", saved[62]);
        assertLegacySlots(saved, true);

        recreate();
        assertTrue(data.isPlayerButtonEnabled(PlayerTweaksData.PLAYER_BUTTON_VOICE_TRANSLATE));
        data.setPlayerButtonDisabled(PlayerTweaksData.PLAYER_BUTTON_VOICE_TRANSLATE);
        drainPersistence();
        assertLegacySlots(storedSlots(), false);
        recreate();
        assertFalse(data.isPlayerButtonEnabled(PlayerTweaksData.PLAYER_BUTTON_VOICE_TRANSLATE));
        assertEquals("true", storedSlots()[61]);
        assertLegacyGetters();
    }

    @Test
    public void migratedDisabledButtonStaysDisabledAcrossLiveEdgeChanges() throws Exception {
        load(LEGACY + "%OB%true");
        assertFalse(data.isPlayerButtonEnabled(PlayerTweaksData.PLAYER_BUTTON_VOICE_TRANSLATE));
        assertFalse(data.isLiveEdgeEnabled());

        data.setLiveEdgeEnabled(true);
        drainPersistence();
        assertEquals("true", storedSlots()[61]);
        assertEquals("true", storedSlots()[62]);
        assertLegacySlots(storedSlots(), false);
        recreate();
        assertTrue(data.isLiveEdgeEnabled());
        assertFalse(data.isPlayerButtonEnabled(PlayerTweaksData.PLAYER_BUTTON_VOICE_TRANSLATE));
        assertLegacyGetters();

        data.setLiveEdgeEnabled(false);
        drainPersistence();
        recreate();
        assertFalse(data.isLiveEdgeEnabled());
        assertFalse(data.isPlayerButtonEnabled(PlayerTweaksData.PLAYER_BUTTON_VOICE_TRANSLATE));
        assertEquals("true", storedSlots()[61]);
        assertEquals("false", storedSlots()[62]);
    }

    @Test
    public void unmigratedMarkerDoesNotConsumeIndependentLiveEdgeSlot() throws Exception {
        load(LEGACY + "%OB%false%OB%true");
        assertTrue(data.isLiveEdgeEnabled());
        assertTrue(data.isPlayerButtonEnabled(PlayerTweaksData.PLAYER_BUTTON_VOICE_TRANSLATE));
        data.setPlayerButtonDisabled(PlayerTweaksData.PLAYER_BUTTON_VOICE_TRANSLATE);
        drainPersistence();
        assertEquals("true", storedSlots()[61]);
        assertEquals("true", storedSlots()[62]);
        recreate();
        assertTrue(data.isLiveEdgeEnabled());
        assertFalse(data.isPlayerButtonEnabled(PlayerTweaksData.PLAYER_BUTTON_VOICE_TRANSLATE));
        assertLegacySlots(storedSlots(), false);
    }

    private void load(String fixture) throws Exception {
        prefs.setProfileData(KEY, fixture);
        recreate();
    }

    private void recreate() throws Exception {
        if (data != null) {
            prefs.removeListener(data);
        }
        Constructor<PlayerTweaksData> constructor = PlayerTweaksData.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        data = constructor.newInstance(context);
    }

    private void drainPersistence() {
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS);
    }

    private String[] storedSlots() {
        return prefs.getProfileData(KEY).split("%OB%", -1);
    }

    private void assertLegacySlots(String[] saved, boolean votEnabled) {
        String[] legacy = LEGACY.split("%OB%", -1);
        assertEquals(61, legacy.length);
        for (int slot = 0; slot < legacy.length; slot++) {
            String expected = slot == 13 && votEnabled
                    ? String.valueOf(3 | PlayerTweaksData.PLAYER_BUTTON_VOICE_TRANSLATE) : legacy[slot];
            assertEquals("slot " + slot, expected, saved[slot]);
        }
    }

    private void assertLegacyGetters() {
        assertTrue(data.isAmlogicFixEnabled());
        assertFalse(data.isAmazonFrameDropFixEnabled());
        assertEquals(1.25f, data.getPixelRatio(), 0f);
        assertEquals(PlayerTweaksData.PLAYER_DATA_SOURCE_CRONET, data.getPlayerDataSource());
        assertEquals(37, data.getScreenOffTimeoutSec());
        assertEquals(43, data.getScreenOffDimmingPercents());
        assertFalse(data.isAudioFocusEnabled());
        assertTrue(data.isDontResizeVideoToFitDialogEnabled());
        assertFalse(data.isSuggestionsHorizontallyScrolled());
        assertTrue(data.isQuickSkipShortsAltEnabled());
        assertFalse(data.isQuickSkipVideosAltEnabled());
        assertTrue(data.isAudioTimeStretchingEnabled());
        assertFalse(data.isQueueRespectsPlaybackMode());
    }
}
