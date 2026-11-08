package com.liskovsoft.smartyoutubetv2.common.vot;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import com.liskovsoft.smartyoutubetv2.common.BuildConfig;

/** Local, allowlisted metadata only. Never reads preferences or playback requests. */
public final class VotDiagnostics {
    public enum State { OFF, PENDING, PREPARING, ACTIVE }
    public enum Error { NONE, NETWORK, AUTH, TRANSLATION, AUDIO }

    private static volatile State state = State.OFF;
    private static volatile Error error = Error.NONE;

    private VotDiagnostics() {}

    public static void setState(State value) {
        state = value == null ? State.OFF : value;
    }

    public static void setError(Error value) {
        error = value == null ? Error.NONE : value;
    }

    public static String createReport(Context context) {
        String version = "unknown";
        long code = 0;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            version = info.versionName;
            code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {
            // Package metadata can be unavailable; no exception text enters the report.
        }
        String abi = Build.VERSION.SDK_INT >= 21 && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : Build.CPU_ABI;
        return formatReport(version, code, BuildConfig.UPSTREAM_SHA, Build.MODEL,
                Build.VERSION.SDK_INT, abi, state, error);
    }

    static String formatReport(String versionName, long versionCode, String upstreamSha,
                               String model, int api, String abi, State state, Error error) {
        return "Version: " + versionName + " (" + versionCode + ")\n"
                + "Upstream: " + upstreamSha + "\n"
                + "Model: " + model + "\nAPI: " + api + "\nABI: " + abi + "\n"
                + "State: " + state.name() + "\nError: " + error.name();
    }
}
