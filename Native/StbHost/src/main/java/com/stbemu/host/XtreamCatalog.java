package com.stbemu.host;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Xtream JSON → the channels.data / channels.details payloads TvScreen and ChannelPanel render. */
final class XtreamCatalog {
    private XtreamCatalog() {}

    static JSONObject channelsData(JSONArray categories, JSONArray streams) throws JSONException {
        JSONArray genres = new JSONArray();
        HashSet<String> genreIds = new HashSet<>();
        for (int i = 0; i < categories.length(); i++) {
            JSONObject category = categories.optJSONObject(i);
            String id = category == null ? "" : category.optString("category_id", "");
            if (id.isEmpty() || !genreIds.add(id)) continue;
            genres.put(new JSONObject().put("id", id).put("title", category.optString("category_name", id)));
        }
        JSONArray channels = new JSONArray();
        HashSet<String> ids = new HashSet<>();
        for (int i = 0; i < streams.length(); i++) {
            JSONObject stream = streams.optJSONObject(i);
            if (stream == null) continue;
            String id = stream.optString("stream_id", "");
            String name = stream.optString("name", "").trim();
            if (id.isEmpty() || name.isEmpty() || !ids.add(id)) continue;
            channels.put(new JSONObject()
                    .put("id", id)
                    .put("number", number(stream.opt("num")))
                    .put("name", name)
                    .put("genreId", stream.optString("category_id", ""))
                    .put("logo", stream.optString("stream_icon", ""))
                    .put("archive", stream.optInt("tv_archive", 0) == 1 ? 1 : 0));
        }
        return new JSONObject().put("channels", channels).put("genres", genres)
                .put("favIds", new JSONArray()).put("logoBase", "");
    }

    /** Validates the whole snapshot before StbHost mutates UI state. */
    static void validate(JSONObject snapshot) throws JSONException {
        JSONArray channels = snapshot.getJSONArray("channels");
        HashSet<String> ids = new HashSet<>();
        for (int i = 0; i < channels.length(); i++) {
            JSONObject channel = channels.getJSONObject(i);
            String id = channel.getString("id");
            channel.getString("name");
            if (id.isEmpty() || !ids.add(id)) throw new JSONException("Invalid channel ID");
            if (!channel.isNull("number")) channel.getInt("number");
        }
        JSONArray genres = snapshot.getJSONArray("genres");
        for (int i = 0; i < genres.length(); i++) genres.getJSONObject(i).getString("id");
        JSONArray favorites = snapshot.getJSONArray("favIds");
        for (int i = 0; i < favorites.length(); i++) favorites.getString(i);
    }

    static JSONArray programs(JSONArray listings, TimeZone zone) throws JSONException {
        SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.US);
        clock.setTimeZone(zone);
        JSONArray programs = new JSONArray();
        for (int i = 0; i < listings.length(); i++) {
            JSONObject listing = listings.optJSONObject(i);
            if (listing == null) continue;
            long start = listing.optLong("start_timestamp", 0), stop = listing.optLong("stop_timestamp", 0);
            String time = start > 0 && stop > 0
                    ? clock.format(new Date(start * 1000)) + " - " + clock.format(new Date(stop * 1000))
                    : listing.optString("start", "");
            programs.put(new JSONObject()
                    .put("title", decode(listing.optString("title", "")))
                    .put("description", decode(listing.optString("description", "")))
                    .put("time", time));
        }
        return programs;
    }

    static void putInfo(JSONObject target, XtreamClient.Account account, String username, TimeZone zone) throws JSONException {
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        day.setTimeZone(zone);
        target.put("expiry", account.expiresAt > 0 ? day.format(new Date(account.expiresAt * 1000)) : "Unlimited")
                .put("username", username)
                .put("status", account.status)
                .put("connections", account.activeConnections + "/"
                        + (account.maxConnections > 0 ? String.valueOf(account.maxConnections) : "unlimited"));
    }

    /** Xtream sends EPG text base64-encoded; anything that is not valid base64 is shown as-is. */
    private static String decode(String value) {
        if (value.isEmpty()) return value;
        try {
            return new String(Base64.getDecoder().decode(value.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static Object number(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try { return Integer.parseInt(((String) value).trim()); } catch (NumberFormatException ignored) { }
        }
        return JSONObject.NULL;
    }
}
