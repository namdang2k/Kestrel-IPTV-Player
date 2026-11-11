package com.stbemu.host;

import android.content.Context;
import android.net.Uri;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** M3U / M3U8 playlist (http(s) URL or content:// document) → the channels.data payload plus an id → stream URL map. */
final class M3uPlaylist {
    /** Same error codes as XtreamClient so StbHost and C# handle both sources alike. */
    static final class LoadException extends Exception {
        final String code;
        LoadException(String code) { super(code); this.code = code; }
    }

    static final String UNCATEGORIZED = "Uncategorized";
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");
    private static final Pattern STREAM_URL = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://\\S+$");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).build();

    final JSONObject data;
    final HashMap<String, String> urls;

    private M3uPlaylist(JSONObject data, HashMap<String, String> urls) {
        this.data = data;
        this.urls = urls;
    }

    /** Blocking; never call on the UI thread. */
    static M3uPlaylist load(Context context, String source, String userAgent) throws LoadException {
        Uri uri = Uri.parse(source);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        try {
            if ("content".equals(scheme)) {
                InputStream in = context.getContentResolver().openInputStream(uri);
                if (in == null) throw new LoadException("NETWORK");
                try (InputStream stream = in) { return parse(new InputStreamReader(stream, StandardCharsets.UTF_8)); }
            }
            if (!"http".equals(scheme) && !"https".equals(scheme)) throw new LoadException("CONFIGURATION_INVALID");
            Request request = new Request.Builder().url(source).header("User-Agent", userAgent).build();
            try (Response response = HTTP.newCall(request).execute()) {
                int code = response.code();
                if (code == 401 || code == 403) throw new LoadException("FORBIDDEN");
                if (code >= 500) throw new LoadException("HTTP_" + code);
                if (!response.isSuccessful() || response.body() == null) throw new LoadException("INVALID_RESPONSE");
                return parse(response.body().charStream());
            }
        } catch (SocketTimeoutException e) {
            throw new LoadException("TIMEOUT");
        } catch (IOException | SecurityException e) { // SecurityException: persisted document permission was revoked
            throw new LoadException("NETWORK");
        } catch (IllegalArgumentException e) { // okhttp rejects a malformed URL
            throw new LoadException("CONFIGURATION_INVALID");
        }
    }

    /** @throws LoadException INVALID_RESPONSE when the text holds no playable entry (not an M3U, e.g. an HTML error page). */
    static M3uPlaylist parse(Reader reader) throws IOException, LoadException {
        LinkedHashMap<String, String> groups = new LinkedHashMap<>();
        HashMap<String, String> urls = new HashMap<>();
        JSONArray channels = new JSONArray();
        String name = null, logo = "", group = "";
        BufferedReader lines = new BufferedReader(reader);
        try {
            for (String line; (line = lines.readLine()) != null; ) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXTINF")) {
                    HashMap<String, String> attributes = new HashMap<>();
                    Matcher m = ATTRIBUTE.matcher(line);
                    while (m.find()) attributes.put(m.group(1).toLowerCase(java.util.Locale.ROOT), m.group(2));
                    // Quoted attribute values may hold commas: the title starts at the first comma after the last quote.
                    int comma = line.indexOf(',', Math.max(line.lastIndexOf('"'), 0));
                    name = comma < 0 ? "" : line.substring(comma + 1).trim();
                    if (name.isEmpty()) name = attributes.getOrDefault("tvg-name", "").trim();
                    logo = attributes.getOrDefault("tvg-logo", "").trim();
                    group = attributes.getOrDefault("group-title", "").trim();
                } else if (line.startsWith("#EXTGRP:")) {
                    if (group.isEmpty()) group = line.substring(8).trim();
                } else if (line.charAt(0) != '#' && STREAM_URL.matcher(line).find()) { // other lines (e.g. an HTML page) are not entries
                    if (name == null) name = line; // bare URL list: show the URL
                    String id = Integer.toHexString(line.hashCode());
                    for (int n = 2; urls.containsKey(id); n++) id = Integer.toHexString(line.hashCode()) + "_" + n; // same URL listed twice
                    urls.put(id, line);
                    if (group.isEmpty()) group = UNCATEGORIZED;
                    groups.putIfAbsent(group, group);
                    try {
                        channels.put(new JSONObject().put("id", id).put("number", channels.length() + 1)
                                .put("name", name.isEmpty() ? line : name).put("genreId", group).put("logo", logo).put("archive", 0));
                    } catch (JSONException e) {
                        throw new IllegalStateException(e);
                    }
                    name = null; logo = ""; group = "";
                }
            }
        } finally {
            lines.close();
        }
        if (channels.length() == 0) throw new LoadException("INVALID_RESPONSE");
        try {
            JSONArray genres = new JSONArray();
            for (String g : groups.values()) genres.put(new JSONObject().put("id", g).put("title", g));
            JSONObject data = new JSONObject().put("channels", channels).put("genres", genres)
                    .put("favIds", new JSONArray()).put("logoBase", "");
            return new M3uPlaylist(data, urls);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
