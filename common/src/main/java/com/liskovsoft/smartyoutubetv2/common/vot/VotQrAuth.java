package com.liskovsoft.smartyoutubetv2.common.vot;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.schedulers.Schedulers;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Yandex QR-code login flow, mirrors YandexStation's yandex_session.py (maintained reference)
 * with the legacy status endpoint of Stmol/yandex-oauth-token passport-auth.js as fallback.
 *
 * Cold observable on Schedulers.io(); unsubscribing cancels the flow and wipes the in-memory
 * cookie jar. Tokens, cookies and CSRF values are never logged or persisted.
 */
public final class VotQrAuth {
    private static final String PASSPORT_ORIGIN = "https://passport.yandex.ru";
    private static final String MOBILE_PROXY_ORIGIN = "https://mobileproxy.passport.yandex.net";
    private static final String USER_AGENT =
            "com.yandex.mobile.auth.sdk/6.32.2.11 (Apple iPad8,6; iOS 26.4) PassportSDK/6.32.2.11 ru.yandex.key/24023131";
    private static final Pattern CSRF_PRIMARY = Pattern.compile("window\\.__CSRF__\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern CSRF_FALLBACK = Pattern.compile("\"csrf_token\"\\s+value=\"([^\"]+)\"");
    // Public client credentials of the official Yandex mobile apps (same as the reference sources).
    private static final String OAUTH_CLIENT_ID = "c0ebe342af7d48fbbbfcf2d2eedb8f9e";
    private static final String OAUTH_CLIENT_SECRET = "ad0a908f0aa341a182a37ecd75bc319e";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");
    private static final long WAIT_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long POLL_INTERVAL_MS = TimeUnit.SECONDS.toMillis(2);

    public enum Phase { CREATING, WAITING, CONFIRMED_EXCHANGING, SUCCESS, CAPTCHA, EXPIRED, NETWORK_ERROR }

    public static final class Event {
        public final Phase phase;
        @Nullable
        public final String token;  // non-null only on SUCCESS
        @Nullable
        public final String detail; // CAPTCHA/NETWORK_ERROR text; QR URL while WAITING
        public final long expiresAtMs; // valid while WAITING

        private Event(Phase phase, @Nullable String token, @Nullable String detail, long expiresAtMs) {
            this.phase = phase;
            this.token = token;
            this.detail = detail;
            this.expiresAtMs = expiresAtMs;
        }

        public static Event creating() {
            return new Event(Phase.CREATING, null, null, 0L);
        }

        /** The QR URL is carried in {@link #detail} (contract v1.1). */
        public static Event waiting(long expiresAtMs, String qrUrl) {
            return new Event(Phase.WAITING, null, qrUrl, expiresAtMs);
        }

        public static Event confirmed() {
            return new Event(Phase.CONFIRMED_EXCHANGING, null, null, 0L);
        }

        public static Event success(String token) {
            return new Event(Phase.SUCCESS, token, null, 0L);
        }

        public static Event captcha(String detail) {
            return new Event(Phase.CAPTCHA, null, detail, 0L);
        }

        public static Event expired() {
            return new Event(Phase.EXPIRED, null, null, 0L);
        }

        public static Event networkError(String detail) {
            return new Event(Phase.NETWORK_ERROR, null, detail, 0L);
        }
    }

    private static final class SessionState {
        final LinkedHashMap<String, LinkedHashMap<String, String>> jar = new LinkedHashMap<>();
        String csrfToken;
        String trackId;
    }

    private static final class CaptchaException extends RuntimeException {
        CaptchaException(String detail) {
            super(detail);
        }
    }

    private static final class AuthException extends RuntimeException {
        AuthException(String detail) {
            super(detail);
        }
    }

    private VotQrAuth() {
    }

    /** Cold observable on Schedulers.io(); unsubscribe cancels the flow and clears the cookie jar. */
    public static Observable<Event> create() {
        return Observable.<Event>create(emitter -> {
            SessionState state = new SessionState();
            emitter.setCancellable(() -> clearJar(state));
            runFlow(emitter, state);
        }).subscribeOn(Schedulers.io());
    }

    private static void runFlow(ObservableEmitter<Event> emitter, SessionState state) {
        VotHttp http = new VotHttp(new MemoryCookieJar(state));
        try {
            emitter.onNext(Event.creating());

            state.csrfToken = openPassportPage(http);
            startQrSession(http, state);
            String qrUrl = requestMagicCode(http, state);

            long expiresAtMs = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
            emitter.onNext(Event.waiting(expiresAtMs, qrUrl));

            if (!awaitConfirmation(emitter, http, state, expiresAtMs)) {
                return; // disposed or expired (already reported)
            }

            emitter.onNext(Event.confirmed());
            finalizeSession(http, state);

            String token = exchangeToken(http, state);
            if (!emitter.isDisposed()) {
                emitter.onNext(Event.success(token));
                emitter.onComplete();
            }
        } catch (CaptchaException e) {
            notifyTerminal(emitter, Event.captcha(e.getMessage()));
        } catch (AuthException e) {
            notifyTerminal(emitter, Event.networkError(e.getMessage()));
        } catch (IOException e) {
            notifyTerminal(emitter, Event.networkError(
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            clearJar(state);
        }
    }

    /** Step 1: open the Passport auth page and extract the CSRF token. */
    private static String openPassportPage(VotHttp http) throws IOException {
        try (Response response = http.execute(PASSPORT_ORIGIN, USER_AGENT, "/am?app_platform=android",
                "GET", null, null)) {
            String body = responseBody(response);
            if (response.code() != 200) {
                throw new CaptchaException("Passport page HTTP " + response.code());
            }
            if (looksLikeCaptcha(body)) {
                throw new CaptchaException("Passport captcha challenge");
            }
            String csrfToken = extractCsrfToken(body);
            if (csrfToken == null) {
                throw new AuthException("No csrf_token on the Passport page");
            }
            return csrfToken;
        }
    }

    /** Step 2: register the auth session, refreshes CSRF and stores the track id. */
    private static void startQrSession(VotHttp http, SessionState state) throws IOException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("retpath", PASSPORT_ORIGIN + "/profile");
            payload.put("with_code", true);
        } catch (JSONException e) {
            throw new AuthException("Cannot build password/submit request");
        }
        try (Response response = http.execute(PASSPORT_ORIGIN, USER_AGENT,
                "/pwl-yandex/api/passport/auth/password/submit", "POST", jsonBody(payload),
                passportHeaders(state.csrfToken))) {
            ensureSuccessful(response, "password/submit");
            JSONObject resp = parseJson(response);
            state.csrfToken = resp.optString("csrf_token", state.csrfToken);
            state.trackId = resp.optString("track_id", null);
            if (state.trackId == null || state.trackId.isEmpty()) {
                throw new AuthException("No track_id for the QR session");
            }
        }
    }

    /** Step 3: request the magic code, returns the QR URL for the user to scan. */
    private static String requestMagicCode(VotHttp http, SessionState state) throws IOException {
        RequestBody form = new FormBody.Builder()
                .add("location_id", "0")
                .add("magic_track_id", state.trackId)
                .add("track_id", "")
                .build();
        try (Response response = http.execute(PASSPORT_ORIGIN, USER_AGENT,
                "/pwl-yandex/api/passport/auth/magic/code", "POST", form,
                passportHeaders(state.csrfToken))) {
            ensureSuccessful(response, "magic/code");
            parseJson(response); // validate the reply, the QR URL is built from the track id
        }
        return PASSPORT_ORIGIN + "/auth/magic/code/?track_id="
                + URLEncoder.encode(state.trackId, "UTF-8");
    }

    /**
     * Step 4: poll until the user confirms on another device.
     *
     * @return false if disposed or the wait window elapsed (EXPIRED already emitted)
     */
    private static boolean awaitConfirmation(ObservableEmitter<Event> emitter, VotHttp http,
                                             SessionState state, long expiresAtMs)
            throws IOException, InterruptedException {
        while (System.currentTimeMillis() < expiresAtMs) {
            Thread.sleep(POLL_INTERVAL_MS);
            if (emitter.isDisposed()) {
                return false;
            }
            if (pollStatusPrimary(http, state) || pollStatusLegacy(http, state)) {
                return true;
            }
        }
        notifyTerminal(emitter, Event.expired());
        return false;
    }

    /** Primary status endpoint (YandexStation). @return true when the user has confirmed. */
    private static boolean pollStatusPrimary(VotHttp http, SessionState state) throws IOException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("csrf_token", state.csrfToken);
            payload.put("track_id", state.trackId);
        } catch (JSONException e) {
            throw new AuthException("Cannot build magic/status request");
        }
        try (Response response = http.execute(PASSPORT_ORIGIN, USER_AGENT,
                "/pwl-yandex/api/passport/auth/magic/code/status", "POST", jsonBody(payload),
                passportHeaders(state.csrfToken))) {
            if (!response.isSuccessful()) {
                return false;
            }
            JSONObject resp = parseJsonOrNull(response);
            if (resp == null) {
                return false;
            }
            if ("otp_auth_finished".equals(resp.optString("state"))) {
                // YandexStation uses the track id echoed back by the status endpoint.
                String trackId = resp.optString("trackId", null);
                if (trackId != null && !trackId.isEmpty()) {
                    state.trackId = trackId;
                }
                return true;
            }
            return false;
        }
    }

    /** Legacy status endpoint (Stmol/yandex-oauth-token). @return true when authorized. */
    private static boolean pollStatusLegacy(VotHttp http, SessionState state) throws IOException {
        RequestBody form = new FormBody.Builder()
                .add("csrf_token", state.csrfToken)
                .add("track_id", state.trackId)
                .build();
        try (Response response = http.execute(PASSPORT_ORIGIN, USER_AGENT, "/auth/new/magic/status/",
                "POST", form, passportHeaders(state.csrfToken))) {
            if (!response.isSuccessful()) {
                return false;
            }
            JSONObject resp = parseJsonOrNull(response);
            return resp != null && "ok".equals(resp.optString("status"));
        }
    }

    /** Step 5: finalize the Passport session, login cookies land in the jar. */
    private static void finalizeSession(VotHttp http, SessionState state) throws IOException {
        RequestBody form = new FormBody.Builder()
                .add("track_id", state.trackId)
                .build();
        try (Response response = http.execute(PASSPORT_ORIGIN, USER_AGENT,
                "/pwl-yandex/api/passport/sessions/get_session", "POST", form,
                passportHeaders(state.csrfToken))) {
            ensureSuccessful(response, "sessions/get_session");
        }
    }

    /** Step 6: exchange the Passport cookies for an OAuth access token. */
    private static String exchangeToken(VotHttp http, SessionState state) throws IOException {
        RequestBody form = new FormBody.Builder()
                .add("client_id", OAUTH_CLIENT_ID)
                .add("client_secret", OAUTH_CLIENT_SECRET)
                .build();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("ya-client-host", "passport.yandex.ru");
        headers.put("ya-client-cookie", serializeCookies(state));
        try (Response response = http.execute(MOBILE_PROXY_ORIGIN, USER_AGENT,
                "/1/bundle/oauth/token_by_sessionid", "POST", form, headers)) {
            JSONObject resp = parseJson(response);
            String token = resp.optString("access_token", null);
            if (token == null || token.isEmpty()) {
                throw new AuthException("No access_token in the exchange response");
            }
            return token;
        }
    }

    private static Map<String, String> passportHeaders(String csrfToken) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-csrf-token", csrfToken);
        headers.put("Origin", PASSPORT_ORIGIN);
        headers.put("Referer", PASSPORT_ORIGIN + "/");
        return headers;
    }

    private static RequestBody jsonBody(JSONObject payload) {
        return RequestBody.create(JSON_MEDIA_TYPE, payload.toString());
    }

    private static void ensureSuccessful(Response response, String stage) {
        if (!response.isSuccessful()) {
            throw new AuthException(stage + " failed: HTTP " + response.code());
        }
    }

    private static String responseBody(Response response) throws IOException {
        return response.body() != null ? response.body().string() : "";
    }

    private static JSONObject parseJson(Response response) throws IOException {
        String body = responseBody(response);
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new AuthException("Unexpected Passport response");
        }
    }

    private static JSONObject parseJsonOrNull(Response response) throws IOException {
        String body = responseBody(response);
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            return null;
        }
    }

    private static boolean looksLikeCaptcha(String body) {
        return body.toLowerCase(Locale.US).contains("captcha"); // covers smart-captcha markers too
    }

    private static String extractCsrfToken(String html) {
        Matcher matcher = CSRF_PRIMARY.matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = CSRF_FALLBACK.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String serializeCookies(SessionState state) {
        StringBuilder builder = new StringBuilder();
        synchronized (state.jar) {
            for (Map.Entry<String, LinkedHashMap<String, String>> hostEntry : state.jar.entrySet()) {
                if (!hostEntry.getKey().endsWith("yandex.ru")) {
                    continue;
                }
                for (Map.Entry<String, String> cookie : hostEntry.getValue().entrySet()) {
                    if (builder.length() > 0) {
                        builder.append("; ");
                    }
                    builder.append(cookie.getKey()).append('=').append(cookie.getValue());
                }
            }
        }
        return builder.toString();
    }

    private static void notifyTerminal(ObservableEmitter<Event> emitter, Event event) {
        if (!emitter.isDisposed()) {
            emitter.onNext(event);
            emitter.onComplete();
        }
    }

    private static void clearJar(SessionState state) {
        synchronized (state.jar) {
            state.jar.clear();
        }
    }

    /** In-memory only: never persisted, never logged, wiped on dispose and terminal events. */
    private static final class MemoryCookieJar implements CookieJar {
        private final SessionState mState;

        MemoryCookieJar(SessionState state) {
            mState = state;
        }

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            synchronized (mState.jar) {
                LinkedHashMap<String, String> host = mState.jar.get(url.host());
                if (host == null) {
                    host = new LinkedHashMap<>();
                    mState.jar.put(url.host(), host);
                }
                for (Cookie cookie : cookies) {
                    host.put(cookie.name(), cookie.value());
                }
            }
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            synchronized (mState.jar) {
                LinkedHashMap<String, String> host = mState.jar.get(url.host());
                if (host == null || host.isEmpty()) {
                    return Collections.emptyList();
                }
                List<Cookie> result = new ArrayList<>(host.size());
                for (Map.Entry<String, String> entry : host.entrySet()) {
                    Cookie cookie = Cookie.parse(url, entry.getKey() + "=" + entry.getValue());
                    if (cookie != null) {
                        result.add(cookie);
                    }
                }
                return result;
            }
        }
    }
}
