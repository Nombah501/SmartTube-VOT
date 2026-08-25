package com.liskovsoft.smartyoutubetv2.common.vot;

import androidx.annotation.Nullable;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.schedulers.Schedulers;

/**
 * Relay-based sign-in: the TV shows a short URL + code, the user opens the URL on the phone,
 * signs in there (real browser — no captcha) and the token is relayed back by code.
 *
 * Relay endpoints (see relay/worker.js in the repo root):
 *   POST {base}/api/start        -> {"code":"AB12CD","expiresAt":...}
 *   GET  {base}/api/poll/<code>  -> {"status":"pending"} | {"status":"ok","token":"..."} | {"status":"expired"}
 *
 * Cold observable on Schedulers.io(); unsubscribing stops polling.
 * The token is never logged or persisted here — the caller stores it via VotData.
 */
public final class VotCodeAuth {
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final long POLL_INTERVAL_MS = TimeUnit.SECONDS.toMillis(2);
    private static final long DEFAULT_TTL_MS = TimeUnit.MINUTES.toMillis(10);

    public enum Phase { WAITING, CONFIRMED_EXCHANGING, SUCCESS, EXPIRED, NETWORK_ERROR }

    public static final class Event {
        public final Phase phase;
        @Nullable
        public final String token;      // non-null only on SUCCESS
        @Nullable
        public final String detail;     // sign-in page URL while WAITING; error text otherwise
        @Nullable
        public final String code;       // non-null while WAITING
        public final long expiresAtMs;  // valid while WAITING

        private Event(Phase phase, @Nullable String token, @Nullable String detail,
                      @Nullable String code, long expiresAtMs) {
            this.phase = phase;
            this.token = token;
            this.detail = detail;
            this.code = code;
            this.expiresAtMs = expiresAtMs;
        }

        public static Event waiting(String pageUrl, String code, long expiresAtMs) {
            return new Event(Phase.WAITING, null, pageUrl, code, expiresAtMs);
        }

        public static Event confirmed() {
            return new Event(Phase.CONFIRMED_EXCHANGING, null, null, null, 0L);
        }

        public static Event success(String token) {
            return new Event(Phase.SUCCESS, token, null, null, 0L);
        }

        public static Event expired() {
            return new Event(Phase.EXPIRED, null, null, null, 0L);
        }

        public static Event networkError(@Nullable String detail) {
            return new Event(Phase.NETWORK_ERROR, null, detail, null, 0L);
        }
    }

    private VotCodeAuth() {
    }

    /** @param relayBaseUrl e.g. https://smarttube-vot-auth.example.workers.dev */
    public static Observable<Event> create(String relayBaseUrl) {
        return Observable.<Event>create(emitter -> runFlow(emitter, relayBaseUrl))
                .subscribeOn(Schedulers.io());
    }

    private static void runFlow(ObservableEmitter<Event> emitter, String relayBaseUrl) {
        VotHttp http = new VotHttp();
        try {
            String base = normalizeBase(relayBaseUrl);
            if (base == null) {
                notifyTerminal(emitter, Event.networkError("Relay URL is not configured"));
                return;
            }

            JSONObject start = executeJson(http, base, "/api/start", "POST",
                    RequestBody.create(JSON_MEDIA_TYPE, "{}"));
            if (start == null) {
                notifyTerminal(emitter, Event.networkError("Relay start failed"));
                return;
            }
            String code = start.optString("code", null);
            long expiresAtMs = start.optLong("expiresAt",
                    System.currentTimeMillis() + DEFAULT_TTL_MS);
            if (code == null || code.isEmpty()) {
                notifyTerminal(emitter, Event.networkError("Relay returned no code"));
                return;
            }

            String pageUrl = base + "/t/" + code;
            emitter.onNext(Event.waiting(pageUrl, code, expiresAtMs));

            while (System.currentTimeMillis() < expiresAtMs) {
                Thread.sleep(POLL_INTERVAL_MS);
                if (emitter.isDisposed()) {
                    return;
                }
                JSONObject poll = executeJson(http, base, "/api/poll/" + code, "GET", null);
                if (poll == null) {
                    continue; // transient relay error — keep polling
                }
                String status = poll.optString("status", "");
                if ("ok".equals(status)) {
                    emitter.onNext(Event.confirmed());
                    String token = poll.optString("token", null);
                    if (token == null || token.isEmpty()) {
                        notifyTerminal(emitter, Event.networkError("Relay returned empty token"));
                        return;
                    }
                    notifyTerminal(emitter, Event.success(token));
                    return;
                }
                if ("expired".equals(status)) {
                    notifyTerminal(emitter, Event.expired());
                    return;
                }
            }
            notifyTerminal(emitter, Event.expired());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            notifyTerminal(emitter, Event.networkError(
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private static String normalizeBase(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            trimmed = "https://" + trimmed;
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    @Nullable
    private static JSONObject executeJson(VotHttp http, String base, String path, String method,
                                          @Nullable RequestBody body) throws Exception {
        try (Response response = http.execute(base, USER_AGENT, path, method, body, null)) {
            if (response.code() != 200 || response.body() == null) {
                return null;
            }
            String text = response.body().string();
            return new JSONObject(text);
        }
    }

    private static void notifyTerminal(ObservableEmitter<Event> emitter, Event event) {
        if (!emitter.isDisposed()) {
            emitter.onNext(event);
            emitter.onComplete();
        }
    }
}
