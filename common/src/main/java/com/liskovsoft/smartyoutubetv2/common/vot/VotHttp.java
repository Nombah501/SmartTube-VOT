package com.liskovsoft.smartyoutubetv2.common.vot;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.CookieJar;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class VotHttp {
    private static final MediaType PROTOBUF = MediaType.parse("application/x-protobuf");
    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient mClient;

    public VotHttp() {
        this(null);
    }

    /** Client with an external cookie jar (QR-login flow owns an in-memory session jar). */
    public VotHttp(@Nullable CookieJar cookieJar) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS);
        if (cookieJar != null) {
            builder.cookieJar(cookieJar);
        }
        mClient = builder.build();
    }

    public byte[] postProtobuf(String path, byte[] body, Map<String, String> headers) throws IOException {
        return postProtobuf("https://" + VotConfig.HOST, VotConfig.USER_AGENT, path, body, headers);
    }

    public byte[] postProtobuf(String origin, String userAgent, String path, byte[] body,
                               Map<String, String> headers) throws IOException {
        return executeBytes(origin + path, userAgent, "POST",
                RequestBody.create(PROTOBUF, body), "application/x-protobuf", headers);
    }

    public byte[] putProtobuf(String path, byte[] body, Map<String, String> headers) throws IOException {
        return putProtobuf("https://" + VotConfig.HOST, VotConfig.USER_AGENT, path, body, headers);
    }

    public byte[] putProtobuf(String origin, String userAgent, String path, byte[] body,
                              Map<String, String> headers) throws IOException {
        return executeBytes(origin + path, userAgent, "PUT", RequestBody.create(PROTOBUF, body),
                "application/x-protobuf", headers);
    }

    public byte[] putJson(String path, String json, Map<String, String> headers) throws IOException {
        return putJson("https://" + VotConfig.HOST, VotConfig.USER_AGENT, path, json, headers);
    }

    public byte[] putJson(String origin, String userAgent, String path, String json,
                          Map<String, String> headers) throws IOException {
        return executeBytes(origin + path, userAgent, "PUT",
                RequestBody.create(JSON, json.getBytes(StandardCharsets.UTF_8)), "application/x-protobuf", headers);
    }

    /** Generic request for auxiliary flows (QR login). The caller closes the returned response. */
    Response execute(String origin, String userAgent, String path, String method,
                     @Nullable RequestBody requestBody, @Nullable Map<String, String> headers) throws IOException {
        return executeInternal(origin + path, userAgent, method, requestBody, null, headers);
    }

    @Nullable
    private byte[] executeBytes(String url, String userAgent, String method, RequestBody requestBody,
                                String accept, Map<String, String> headers) throws IOException {
        try (Response response = executeInternal(url, userAgent, method, requestBody, accept, headers)) {
            if (response.body() == null) {
                return null;
            }
            return response.body().bytes();
        }
    }

    private Response executeInternal(String url, String userAgent, String method,
                                     @Nullable RequestBody requestBody, @Nullable String accept,
                                     @Nullable Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .method(method, requestBody);

        if (requestBody != null && requestBody.contentType() != null) {
            builder.header("Content-Type", requestBody.contentType().toString());
        }
        if (accept != null) {
            builder.header("Accept", accept);
        }
        builder.header("Accept-Language", "en")
                .header("User-Agent", userAgent)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache");

        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                builder.header(e.getKey(), e.getValue());
            }
        }

        return mClient.newCall(builder.build()).execute();
    }
}
