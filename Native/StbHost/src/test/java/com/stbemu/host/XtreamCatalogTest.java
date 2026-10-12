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
                + "{\"category_id\":\"1\",\"category_name\":\"Dup\"},{\"category_name\":\"NoId\"}]");
        JSONArray streams = new JSONArray("["
                + "{\"num\":1,\"name\":\"CNN\",\"stream_id\":101,\"stream_icon\":\"http://l/cnn.png\",\"category_id\":\"1\",\"tv_archive\":1},"
                + "{\"num\":null,\"name\":\" BBC \",\"stream_id\":\"102\",\"stream_icon\":\"\",\"category_id\":null,\"tv_archive\":0},"
                + "{\"num\":\"3\",\"name\":\"Dup\",\"stream_id\":101},"
                + "{\"name\":\"NoId\"},"
                + "{\"stream_id\":103,\"name\":\"\"},"
                + "{\"num\":\"x\",\"name\":\"VTV\",\"stream_id\":104}]");
        JSONObject data = XtreamCatalog.channelsData(categories, streams);

        JSONArray genres = data.getJSONArray("genres");
        assertEquals(1, genres.length());
        assertEquals("1", genres.getJSONObject(0).getString("id"));
        assertEquals("News", genres.getJSONObject(0).getString("title"));

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
}
