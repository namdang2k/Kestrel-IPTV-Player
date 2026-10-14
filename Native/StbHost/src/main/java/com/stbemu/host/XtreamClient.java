package com.stbemu.host;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Blocking Xtream Codes player_api.php client. Never call on the UI thread. */
final class XtreamClient {
    static final class XtreamException extends Exception {
        final String code;
        XtreamException(String code) { super(code); this.code = code; }
    }

    /** user_info of an authenticated, active account. */
    static final class Account {
        final String status;
        final long expiresAt; // unix seconds; 0 = no expiry
        final int maxConnections, activeConnections;
        final String ext; // "m3u8" or "ts"

        Account(String status, long expiresAt, int maxConnections, int activeConnections, String ext) {
            this.status = status;
            this.expiresAt = expiresAt;
            this.maxConnections = maxConnections;
            this.activeConnections = activeConnections;
            this.ext = ext;
        }
    }

    private static final OkHttpClient BASE = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).build();

    final String serverUrl;
    private final HttpUrl api;
    private final String username, password, userAgent;
    private final OkHttpClient http;

    XtreamClient(String serverUrl, String username, String password, String userAgent) {
        this(serverUrl, username, password, userAgent, BASE);
    }

    /** @throws IllegalArgumentException when serverUrl is not an http(s) URL */
    XtreamClient(String serverUrl, String username, String password, String userAgent, OkHttpClient http) {
        this.serverUrl = serverUrl.replaceAll("/+$", "");
        this.api = HttpUrl.get(this.serverUrl + "/player_api.php");
        this.username = username;
        this.password = password;
        this.userAgent = userAgent;
        this.http = http;
    }

    Account authenticate() throws XtreamException {
        Object body = get(null, null);
        JSONObject user = body instanceof JSONObject ? ((JSONObject) body).optJSONObject("user_info") : null;
        if (user == null) throw new XtreamException("INVALID_RESPONSE");
        if (user.optInt("auth", 0) != 1) throw new XtreamException("AUTHENTICATION_REJECTED");
        String status = user.optString("status", "");
        long expiresAt = user.optLong("exp_date", 0);
        if ("Expired".equalsIgnoreCase(status))
            throw new XtreamException(expiresAt > 0 ? "ACCOUNT_EXPIRED:" + utcDate(expiresAt) : "ACCOUNT_EXPIRED");
        if (!"Active".equalsIgnoreCase(status)) throw new XtreamException("ACCOUNT_DISABLED");
        JSONArray formats = user.optJSONArray("allowed_output_formats");
        // ts first: one long connection. Many panels' HLS refreshes count as extra connections and hit 509 within a minute.
        boolean ts = formats == null || formats.length() == 0, m3u8 = false;
        for (int i = 0; formats != null && i < formats.length(); i++) {
            ts |= "ts".equalsIgnoreCase(formats.optString(i));
            m3u8 |= "m3u8".equalsIgnoreCase(formats.optString(i));
        }
        String ext = !ts && m3u8 ? "m3u8" : "ts";
        return new Account(status, expiresAt, user.optInt("max_connections", 0), user.optInt("active_cons", 0), ext);
    }

    JSONArray liveCategories() throws XtreamException { return array(get("get_live_categories", null)); }

    JSONArray liveStreams() throws XtreamException { return array(get("get_live_streams", null)); }

    /** epg_listings of get_short_epg; empty when the server has no guide for the channel. */
    JSONArray shortEpg(String streamId) throws XtreamException {
        Object body = get("get_short_epg", streamId);
        if (!(body instanceof JSONObject)) throw new XtreamException("INVALID_RESPONSE");
        JSONArray listings = ((JSONObject) body).optJSONArray("epg_listings");
        return listings == null ? new JSONArray() : listings;
    }

    String streamUrl(String streamId, String ext) {
        return serverUrl + "/live/" + segment(username) + "/" + segment(password) + "/" + segment(streamId) + "." + ext;
    }

    private Object get(String action, String streamId) throws XtreamException {
        HttpUrl.Builder url = api.newBuilder()
                .addQueryParameter("username", username)
                .addQueryParameter("password", password);
        if (action != null) url.addQueryParameter("action", action);
        if (streamId != null) url.addQueryParameter("stream_id", streamId);
        Request request = new Request.Builder().url(url.build()).header("User-Agent", userAgent).build();
        try (Response response = http.newCall(request).execute()) {
            int code = response.code();
            if (code == 401 || code == 403) throw new XtreamException("FORBIDDEN");
            if (code >= 500) throw new XtreamException("HTTP_" + code);
            if (!response.isSuccessful() || response.body() == null) throw new XtreamException("INVALID_RESPONSE");
            try {
                return new JSONTokener(response.body().string()).nextValue();
            } catch (JSONException e) {
                throw new XtreamException("INVALID_RESPONSE");
            }
        } catch (SocketTimeoutException e) {
            throw new XtreamException("TIMEOUT");
        } catch (IOException e) {
            throw new XtreamException("NETWORK");
        }
    }

    private static JSONArray array(Object body) throws XtreamException {
        if (!(body instanceof JSONArray)) throw new XtreamException("INVALID_RESPONSE");
        return (JSONArray) body;
    }

    private static String segment(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String utcDate(long seconds) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(seconds * 1000));
    }
}
