package com.stbemu.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public class XtreamCatalogTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    @Test public void channelsData_mapsAndCleansXtreamFields() throws Exception {
        JSONArray categories = new JSONArray("[{\"category_id\":\"1\",\"category_name\":\"News\"},"
                + "{\"category_id\":\"1\",\"category_name\":\"Dup\"},{\"category_name\":\"NoId\"},"
                + "{\"category_id\":\"2\",\"category_name\":null}]");
        JSONArray streams = new JSONArray("["
                + "{\"num\":1,\"name\":\"CNN\",\"stream_id\":101,\"stream_icon\":\"http://l/cnn.png\",\"category_id\":\"1\",\"tv_archive\":1},"
                + "{\"num\":null,\"name\":\" BBC \",\"stream_id\":\"102\",\"stream_icon\":null,\"category_id\":null,\"tv_archive\":0},"
                + "{\"num\":\"3\",\"name\":\"Dup\",\"stream_id\":101},"
                + "{\"name\":\"NoId\"},"
                + "{\"stream_id\":103,\"name\":\"\"},"
                + "{\"num\":\"x\",\"name\":\"VTV\",\"stream_id\":104}]");
        JSONObject data = XtreamCatalog.channelsData(categories, streams);

        JSONArray genres = data.getJSONArray("genres");
        assertEquals(2, genres.length());
        assertEquals("1", genres.getJSONObject(0).getString("id"));
        assertEquals("News", genres.getJSONObject(0).getString("title"));
        assertEquals("2", genres.getJSONObject(1).getString("title")); // null name falls back to id

        JSONArray channels = data.getJSONArray("channels");
        assertEquals(3, channels.length());
        JSONObject cnn = channels.getJSONObject(0);
        assertEquals("101", cnn.getString("id"));
        assertEquals(1, cnn.getInt("number"));
        assertEquals("CNN", cnn.getString("name"));
        assertEquals("1", cnn.getString("genreId"));
        assertEquals("http://l/cnn.png", cnn.getString("logo"));
        assertEquals(1, cnn.getInt("archive"));
        JSONObject bbc = channels.getJSONObject(1);
        assertEquals("102", bbc.getString("id"));
        assertTrue(bbc.isNull("number"));
        assertEquals("BBC", bbc.getString("name"));
        assertEquals("", bbc.getString("genreId"));
        assertEquals("", bbc.getString("logo")); // JSON null icon, not the literal "null"
        assertEquals(0, bbc.getInt("archive"));
        assertTrue(channels.getJSONObject(2).isNull("number"));

        assertEquals(0, data.getJSONArray("favIds").length());
        assertEquals("", data.getString("logoBase"));
        XtreamCatalog.validate(data); // the shape StbHost accepts
    }

    @Test(expected = JSONException.class)
    public void validate_rejectsDuplicateIds() throws Exception {
        XtreamCatalog.validate(new JSONObject("{\"channels\":[{\"id\":\"1\",\"name\":\"a\"},{\"id\":\"1\",\"name\":\"b\"}],"
                + "\"genres\":[],\"favIds\":[]}"));
    }

    @Test public void programs_decodeBase64AndFormatTime() throws Exception {
        JSONArray listings = new JSONArray("[{\"title\":\"TmV3cw==\",\"description\":\"\",\"start_timestamp\":\"1767225600\","
                + "\"stop_timestamp\":\"1767229200\"},{\"title\":\"@@ not base64\",\"start\":\"2026-01-01 02:00:00\"}]");
        JSONArray programs = XtreamCatalog.programs(listings, UTC);
        assertEquals("News", programs.getJSONObject(0).getString("title"));
        assertEquals("", programs.getJSONObject(0).getString("description"));
        assertEquals("00:00 - 01:00", programs.getJSONObject(0).getString("time"));
        assertEquals("@@ not base64", programs.getJSONObject(1).getString("title"));
        assertEquals("2026-01-01 02:00:00", programs.getJSONObject(1).getString("time"));
    }

    @Test public void putInfo_formatsAccount() throws Exception {
        JSONObject info = new JSONObject();
        XtreamCatalog.putInfo(info, new XtreamClient.Account("Active", 1767225600L, 2, 1, "ts"), "bob", UTC);
        assertEquals("2026-01-01", info.getString("expiry"));
        assertEquals("bob", info.getString("username"));
        assertEquals("Active", info.getString("status"));
        assertEquals("1/2", info.getString("connections"));
        JSONObject unlimited = new JSONObject();
        XtreamCatalog.putInfo(unlimited, new XtreamClient.Account("Active", 0, 0, 0, "ts"), "bob", UTC);
        assertEquals("Unlimited", unlimited.getString("expiry"));
        assertEquals("0/unlimited", unlimited.getString("connections"));
    }

    @Test public void addVod_appendsPrefixedMoviesInTheirOwnGenres() throws Exception {
        JSONObject data = XtreamCatalog.channelsData(new JSONArray("[{\"category_id\":\"1\",\"category_name\":\"News\"}]"),
                new JSONArray("[{\"stream_id\":5,\"name\":\"Live\",\"category_id\":\"1\"}]"));
        java.util.Map<String, String> ext = XtreamCatalog.addVod(data,
                new JSONArray("[{\"category_id\":\"1\",\"category_name\":\"Action\"}]"),
                new JSONArray("[{\"stream_id\":5,\"name\":\"Film\",\"category_id\":\"1\",\"container_extension\":\"mkv\"},"
                        + "{\"stream_id\":6,\"name\":\"NoExt\",\"category_id\":\"9\"},{\"stream_id\":5,\"name\":\"Dup\"}]"));
        JSONArray channels = data.getJSONArray("channels");
        assertEquals(3, channels.length()); // 1 live + 2 movies; the same id as a live channel does not collide
        assertEquals("vod:5", channels.getJSONObject(1).getString("id"));
        assertEquals("vod:1", channels.getJSONObject(1).getString("genreId"));
        assertEquals("", channels.getJSONObject(2).getString("genreId")); // unknown category
        assertEquals("Movies · Action", data.getJSONArray("genres").getJSONObject(1).getString("title"));
        assertEquals("mkv", ext.get("vod:5"));
        assertEquals("mp4", ext.get("vod:6"));
        XtreamCatalog.validate(data);
    }

    @Test public void series_listedAndEpisodesFlattenedInSeasonOrder() throws Exception {
        JSONObject data = XtreamCatalog.channelsData(new JSONArray(), new JSONArray());
        XtreamCatalog.addSeries(data, new JSONArray("[{\"category_id\":\"2\",\"category_name\":\"Drama\"}]"),
                new JSONArray("[{\"series_id\":7,\"name\":\"Show\",\"category_id\":\"2\",\"cover\":\"http://c\"},{\"series_id\":7,\"name\":\"Dup\"}]"));
        assertEquals("series:7", data.getJSONArray("channels").getJSONObject(0).getString("id"));
        assertEquals(1, data.getJSONArray("channels").length());
        assertEquals("series:2", data.getJSONArray("channels").getJSONObject(0).getString("genreId"));
        XtreamCatalog.validate(data);

        JSONObject info = new JSONObject("{\"episodes\":{\"10\":[{\"id\":\"c\",\"episode_num\":1}],"
                + "\"2\":[{\"id\":\"b\",\"episode_num\":\"1\",\"title\":\"Two\",\"container_extension\":\"mkv\"}],\"1\":[{\"id\":\"a\"}]}}");
        java.util.List<XtreamCatalog.Episode> list = XtreamCatalog.episodes(info);
        assertEquals("a", list.get(0).id);
        assertEquals("S1 E1", list.get(0).label);
        assertEquals("S2 E1 · Two", list.get(1).label);
        assertEquals("mkv", list.get(1).ext);
        assertEquals("c", list.get(2).id); // season 10 after 2, numerically
        assertEquals(1, XtreamCatalog.episodes(new JSONObject("{\"episodes\":[[{\"id\":\"x\"}]]}")).size()); // array form
        assertEquals(0, XtreamCatalog.episodes(new JSONArray()).size());
    }
}
