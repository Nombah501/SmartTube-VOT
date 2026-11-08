package com.liskovsoft.smartyoutubetv2.common.vot;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;

import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VotDiagnosticsTest {
    @After
    public void resetState() {
        VotDiagnostics.setState(VotDiagnostics.State.OFF);
        VotDiagnostics.setError(VotDiagnostics.Error.NONE);
        RuntimeEnvironment.application.getSharedPreferences("vot_data", Context.MODE_PRIVATE)
                .edit().clear().commit();
        ((ClipboardManager) RuntimeEnvironment.application.getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("", ""));
    }

    @Test
    public void reportIncludesRuntimeStatusButExcludesStoredSecrets() {
        Context context = RuntimeEnvironment.application;
        VotData data = VotData.instance(context);
        String token = "synthetic-private-oauth-token";
        String relay = "https://private-relay.example/secret-path";
        String clipboardSecret = "private-clipboard-video-id";
        data.setOAuthToken(token);
        data.setRelayUrl(relay);
        ((ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("private", clipboardSecret));
        VotDiagnostics.setState(VotDiagnostics.State.ACTIVE);
        VotDiagnostics.setError(VotDiagnostics.Error.AUDIO);

        String report = VotDiagnostics.createReport(context);

        assertTrue(report.contains(VotDiagnostics.State.ACTIVE.name()));
        assertTrue(report.contains(VotDiagnostics.Error.AUDIO.name()));
        assertTrue(report.contains(Build.MODEL));
        assertTrue(report.contains(Integer.toString(Build.VERSION.SDK_INT)));
        assertFalse(report.contains(token));
        assertFalse(report.contains(data.getTokenPreview()));
        assertFalse(report.contains(relay));
        assertFalse(report.contains("private-relay.example"));
        assertFalse(report.contains(clipboardSecret));
    }

    @Test
    public void reportNeverSourcesPreferencesOrClipboard() {
        Context context = new ContextWrapper(RuntimeEnvironment.application) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                throw new AssertionError("Diagnostics must not read preferences");
            }

            @Override
            public Object getSystemService(String name) {
                throw new AssertionError("Diagnostics must not read services or clipboard");
            }
        };
        VotDiagnostics.setState(null);
        VotDiagnostics.setError(null);
        String initial = VotDiagnostics.createReport(context);
        assertTrue(initial.contains(VotDiagnostics.State.OFF.name()));
        assertTrue(initial.contains(VotDiagnostics.Error.NONE.name()));
        VotDiagnostics.setState(VotDiagnostics.State.PREPARING);
        VotDiagnostics.setError(VotDiagnostics.Error.AUTH);
        String updated = VotDiagnostics.createReport(context);
        assertTrue(updated.contains(VotDiagnostics.State.PREPARING.name()));
        assertTrue(updated.contains(VotDiagnostics.Error.AUTH.name()));
    }


    @Test
    @Config(sdk = 19)
    public void supportsPreLollipopMetadata() {
        String report = VotDiagnostics.createReport(RuntimeEnvironment.application);
        assertTrue(report.contains(Integer.toString(Build.VERSION.SDK_INT)));
        assertTrue(report.contains(Build.CPU_ABI));
    }
}
