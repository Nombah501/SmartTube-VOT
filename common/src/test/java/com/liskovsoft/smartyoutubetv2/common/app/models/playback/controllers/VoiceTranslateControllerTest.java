package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.content.Context;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;
import com.liskovsoft.smartyoutubetv2.common.vot.TranslationAudioPlayer;
import com.liskovsoft.smartyoutubetv2.common.vot.VotClient;
import com.liskovsoft.smartyoutubetv2.common.vot.VotProgress;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import io.reactivex.Observable;
import io.reactivex.android.plugins.RxAndroidPlugins;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VoiceTranslateControllerTest {
    @Test public void dedicatedRemoteKeysRouteIntentThroughPublicUiController() throws Exception {
        Harness c = new Harness();
        c.start(true, true);
        PlayerUIController ui = new PlayerUIController() {
            @Override public PlaybackView getPlayer() { return c.getPlayer(); }
            @Override public Context getContext() { return context(); }
            @Override protected com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener
                    getMainController() { return c; }
        };
        assertTrue(ui.onKeyDown(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE));
        assertFalse(c.ownsPreparationPause());
        assertFalse(c.playing);
        c.urlReady();
        c.audio.ready.onReady();
        assertFalse(c.playing);
        assertEquals(0, c.playRequests);
        c.onEngineReleased();

        c.start(true, true);
        assertTrue(c.ownsPreparationPause());
        assertTrue(ui.onKeyDown(android.view.KeyEvent.KEYCODE_MEDIA_PLAY));
        assertFalse(c.ownsPreparationPause());
        assertTrue(c.playing);
        c.onPlay();
        assertTrue(c.playing);
        c.urlReady();
        c.audio.ready.onReady();
        assertTrue(c.audio.playing);
        assertEquals(1, c.playRequests);
        c.onEngineReleased();
    }

    @Test public void russianTrackSkipReleasesOwnedPauseAndCancelsRequest() throws Exception {
        Harness c = new Harness();
        c.start(true, true);
        com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem russian =
                (com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem) Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[]{com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem.class},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "getType": return 1;
                                case "getLanguage": return "ru (original)";
                                case "isSelected": case "isDefault": return true;
                                default: return null;
                            }
                        });
        c.audioFormat = russian;
        c.onTrackChanged(russian);
        assertTrue(c.playing);
        assertFalse(c.ownsPreparationPause());
        assertEquals(1, c.playRequests);
        assertFalse(c.client.jobs.get(0).hasObservers());
        c.onEngineReleased();
    }

    @Test public void realAudioReadyCallbackFiresOncePerReleaseCycle() {
        TranslationAudioPlayer audio = new TranslationAudioPlayer(context());
        int[] calls = {0};
        audio.setOnReadyListener(() -> calls[0]++);
        audio.onPlayerStateChanged(false, com.google.android.exoplayer2.Player.STATE_BUFFERING);
        assertEquals(0, calls[0]);
        audio.onPlayerStateChanged(false, com.google.android.exoplayer2.Player.STATE_READY);
        audio.onPlayerStateChanged(true, com.google.android.exoplayer2.Player.STATE_READY);
        assertEquals(1, calls[0]);
        audio.release();
        audio.onPlayerStateChanged(false, com.google.android.exoplayer2.Player.STATE_READY);
        assertEquals(2, calls[0]);
    }

    @Test public void engineReleaseSnapshotsLogicalIntentWithoutPlayingOldEngine() throws Exception {
        Harness c = new Harness();
        c.start(true, true);
        VideoStateController snapshot = new VideoStateController() {
            @Override public PlaybackView getPlayer() { return c.getPlayer(); }
            @Override public Video getVideo() { return null; }
            @Override public Context getContext() { return context(); }
            @Override protected <T extends com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener>
                    T getController(Class<T> type) {
                return type == VoiceTranslateController.class ? type.cast(c) : null;
            }
        };
        snapshot.onEngineReleased();
        assertTrue(snapshot.isPlayEnabled());
        assertFalse(c.playing);
        assertEquals(0, c.playRequests);
        c.onPauseClicked();
        snapshot.onEngineReleased();
        assertFalse(snapshot.isPlayEnabled());
        assertFalse(c.playing);
        c.onEngineReleased();
    }

    @Test public void disablingPausePreferenceReleasesOnlyOwnedPause() throws Exception {
        Harness c = new Harness();
        c.start(true, true);
        assertTrue(c.ownsPreparationPause());
        c.data.setPauseWhilePreparingEnabled(false);
        c.onPlaybackPreferencesChanged();
        assertTrue(c.playing);
        assertFalse(c.ownsPreparationPause());
        c.urlReady();
        c.audio.ready.onReady();
        assertEquals(1, c.playRequests);
        c.onEngineReleased();
        Harness paused = new Harness();
        paused.start(false, true);
        paused.data.setPauseWhilePreparingEnabled(false);
        paused.onPlaybackPreferencesChanged();
        assertFalse(paused.playing);
        assertEquals(0, paused.playRequests);
        paused.onEngineReleased();
    }

    @Test public void engineRestoreCannotBypassOwnedPauseButExplicitPlayCan() throws Exception {
        Harness c = new Harness();
        c.start(true, true);
        c.playing = true;
        c.onPlay();
        assertFalse(c.playing);
        c.urlReady();
        c.playing = true;
        c.onPlay();
        assertFalse(c.playing);
        c.onPlayClicked();
        c.playing = true;
        c.onPlay();
        assertTrue(c.playing);
        c.audio.ready.onReady();
        assertTrue(c.audio.playing);
        assertEquals(0, c.playRequests);
        c.onEngineReleased();
    }

    @Before public void setup() {
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(s -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(s -> Schedulers.trampoline());
        context().getSharedPreferences("vot_data", Context.MODE_PRIVATE).edit().clear().commit();
    }
    @After public void teardown() {
        RxAndroidPlugins.reset();
        context().getSharedPreferences("vot_data", Context.MODE_PRIVATE).edit().clear().commit();
    }
    @Test public void pauseWhileAudioBuffersCancelsResumeAndOffInvalidatesReadiness() throws Exception {
        Harness c = new Harness();
        c.start(true, true);
        c.urlReady();
        FakeAudio old = c.audio;
        c.onPauseClicked();
        old.ready.onReady();
        assertFalse(c.playing);
        assertFalse(old.playing);
        c.click(VoiceTranslateController.BTN_ON);
        old.ready.onReady();
        assertTrue(old.released);
        assertEquals(1f, c.volume, 0f);
        assertEquals(0, c.playRequests);
    }

    private static Context context() { return RuntimeEnvironment.getApplication(); }

    @Test public void pausesAtRequestAndResumesOnlyAtAudioReadiness() throws Exception {
        Harness c = new Harness();
        c.start(true, true);
        assertFalse(c.playing);
        assertEquals(1, c.pauseRequests);
        assertNull(c.audio);
        c.urlReady();
        assertFalse(c.playing);
        assertEquals(1f, c.volume, 0f);
        c.audio.ready.onReady();
        assertTrue(c.playing);
        assertTrue(c.audio.playing);
        assertEquals(1, c.playRequests);
        assertEquals(4000, c.audio.position);
        c.audio.ready.onReady();
        assertEquals(1, c.playRequests);
        c.onEngineReleased();
        assertTrue(c.audio.released);
        assertEquals(1f, c.volume, 0f);
    }
    @Test public void pauseIntentWhileAlreadyPausedCancelsResume() throws Exception {
        Harness c = new Harness();
        c.start(true, true);
        c.onPauseClicked();
        c.urlReady();
        c.audio.ready.onReady();
        assertFalse(c.playing);
        assertFalse(c.audio.playing);
        assertEquals(0, c.playRequests);
        c.onEngineReleased();
    }
    @Test public void playDuringPreparationReleasesOwnershipAndTracksEngine() throws Exception {
        Harness c = new Harness();
        c.start(true, true);
        c.onPlayClicked();
        c.playing = true;
        c.onPlay();
        c.urlReady();
        c.audio.ready.onReady();
        assertTrue(c.audio.playing);
        assertEquals(0, c.playRequests);
        c.onSeekEnd();
        assertEquals(4000, c.audio.position);
        c.onSpeedChanged(1.5f);
        assertEquals(1.5f, c.audio.speed, 0f);
        c.onPause();
        assertFalse(c.audio.playing);
        c.onEngineReleased();
    }
    @Test public void prePausedVideoNeverAutoResumes() throws Exception {
        Harness c = new Harness();
        c.start(false, true);
        c.urlReady();
        c.audio.ready.onReady();
        assertFalse(c.playing);
        assertEquals(0, c.pauseRequests);
        assertEquals(0, c.playRequests);
        c.onEngineReleased();
    }
    @Test public void defaultOffDoesNotPauseAndDisabledDoesNotRequest() throws Exception {
        Harness c = new Harness();
        c.playing = true;
        c.onNewVideo(c.video);
        c.onVideoLoaded(c.video);
        assertEquals(0, c.client.jobs.size());
        assertTrue(c.playing);
        assertFalse(c.data.isPauseWhilePreparingEnabled());
        c.click(VoiceTranslateController.BTN_OFF);
        assertTrue(c.playing);
        assertEquals(0, c.pauseRequests);
        c.onEngineReleased();
    }
    @Test public void sameIdReloadDisposesOldJobAndRejectsOldAudioCallbacks() throws Exception {
        assertReloadRejectsOldCallbacks("same-id");
    }

    @Test public void differentVideoRejectsOldProgressErrorAndAudioCallbacks() throws Exception {
        assertReloadRejectsOldCallbacks("second-video");
    }

    private void assertReloadRejectsOldCallbacks(String nextId) throws Exception {
        Harness c = new Harness();
        c.start(true, true);
        c.urlReady();
        FakeAudio old = c.audio;
        PublishSubject<VotProgress> oldJob = c.client.jobs.get(0);
        c.video = new Video();
        c.video.videoId = nextId;
        c.onNewVideo(c.video);
        assertFalse(oldJob.hasObservers());
        assertTrue(old.released);
        assertEquals(0, c.playRequests);
        c.onVideoLoaded(c.video);
        assertEquals(2, c.client.jobs.size());
        oldJob.onNext(VotProgress.ready("https://example.invalid/stale"));
        oldJob.onError(new java.io.IOException("stale request"));
        old.ready.onReady();
        old.error.run();
        assertSame(old, c.audio);
        assertEquals(0, c.playRequests);
        c.urlReady();
        assertNotSame(old, c.audio);
        c.audio.ready.onReady();
        assertFalse(c.playing);
        assertEquals(0, c.playRequests);
        c.onEngineReleased();
    }
    @Test public void offDisposesRequestAndRestoresActiveVolume() throws Exception {
        Harness c = new Harness();
        c.start(true, true);
        c.click(VoiceTranslateController.BTN_PENDING);
        assertFalse(c.client.jobs.get(0).hasObservers());
        assertTrue(c.playing);
        c.playing = true;
        c.click(VoiceTranslateController.BTN_OFF);
        c.urlReady();
        c.audio.ready.onReady();
        assertTrue(c.volume < 1f);
        c.click(VoiceTranslateController.BTN_ON);
        assertEquals(1f, c.volume, 0f);
        assertTrue(c.audio.released);
    }
    @Test public void audioFailureCleansUpWithoutResumingMain() throws Exception {
        Harness c = new Harness();
        c.start(true, true);
        c.urlReady();
        FakeAudio old = c.audio;
        old.error.run();
        assertTrue(old.released);
        assertFalse(c.client.jobs.get(0).hasObservers());
        old.ready.onReady();
        assertFalse(c.playing);
        assertEquals(0, c.playRequests);
        assertEquals(1f, c.volume, 0f);
    }
    @Test public void requestFailureLeavesOwnedPauseWithoutAudio() throws Exception {
        Harness c = new Harness();
        c.start(true, true);
        c.client.jobs.get(0).onError(new java.io.IOException("test"));
        assertFalse(c.playing);
        assertNull(c.audio);
        assertEquals(0, c.playRequests);
        assertEquals(1f, c.volume, 0f);
    }

    private static class Harness extends VoiceTranslateController {
        boolean playing;
        float volume = 1f;
        int playRequests, pauseRequests;
        FakeAudio audio;
        com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem audioFormat;
        Video video = new Video();
        final VotData data;
        final FakeClient client = new FakeClient();
        final PlaybackView player = (PlaybackView) Proxy.newProxyInstance(
                PlaybackView.class.getClassLoader(), new Class<?>[]{PlaybackView.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getAudioFormat": return audioFormat;
                        case "containsMedia": return true;
                        case "isEmbed": return false;
                        case "isOverlayShown": case "isSuggestionsShown": return false;
                        case "getVideo": return video;
                        case "getPlayWhenReady": return playing;
                        case "setPlayWhenReady": playing = (Boolean) a[0]; if (playing) playRequests++; else pauseRequests++; return null;
                        case "getPositionMs": return 4000L;
                        case "getDurationMs": return 100000L;
                        case "getSpeed": return 1f;
                        case "getVolume": return volume;
                        case "setVolume": volume = (Float) a[0]; return null;
                        default: return null;
                    }
                });
        Harness() throws Exception {
            video.videoId = "same-id";
            Constructor<VotData> constructor = VotData.class.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            data = constructor.newInstance(context());
        }
        void start(boolean play, boolean pauseOption) {
            data.setPauseWhilePreparingEnabled(pauseOption);
            playing = play;
            onNewVideo(video);
            click(BTN_OFF);
        }
        void click(int state) { onButtonClicked(R.id.action_voice_translate, state); }
        void urlReady() { client.jobs.get(client.jobs.size() - 1).onNext(VotProgress.ready("https://example.invalid/audio")); }
        @Override public PlaybackView getPlayer() { return player; }
        @Override public Context getContext() { return context(); }
        @Override protected VotData votData() { return data; }
        @Override protected VotClient votClient() { return client; }
        @Override protected TranslationAudioPlayer createTranslationPlayer() { audio = new FakeAudio(); return audio; }
    }
    private static class FakeClient extends VotClient {
        final List<PublishSubject<VotProgress>> jobs = new ArrayList<>();
        FakeClient() { super(context()); }
        @Override public Observable<VotProgress> observeTranslation(String url, long duration) {
            PublishSubject<VotProgress> job = PublishSubject.create();
            jobs.add(job);
            return job;
        }
    }
    private static class FakeAudio extends TranslationAudioPlayer {
        OnReadyListener ready;
        Runnable error;
        boolean playing, released;
        long position;
        float speed;
        FakeAudio() { super(context()); }
        @Override public void setOnReadyListener(OnReadyListener listener) { ready = listener; }
        @Override public void setOnErrorListener(Runnable listener) { error = listener; }
        @Override public void play(String url, long start, float volume, float speed) { position = start; this.speed = speed; }
        @Override public void resume() { playing = true; }
        @Override public void pause() { playing = false; }
        @Override public void release() { released = true; playing = false; }
        @Override public boolean isReady() { return true; }
        @Override public void seekTo(long value) { position = value; }
        @Override public long getPositionMs() { return position; }
        @Override public void setPlaybackSpeed(float value) { speed = value; }
    }
}
