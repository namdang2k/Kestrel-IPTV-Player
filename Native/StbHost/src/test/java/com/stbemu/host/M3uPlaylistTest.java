package com.stbemu.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.StringReader;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class M3uPlaylistTest {
    private static M3uPlaylist parse(String text) throws Exception { return M3uPlaylist.parse(new StringReader(text)); }

    @Test public void parse_mapsGroupsLogosAndTitlesWithCommasInAttributes() throws Exception {
        M3uPlaylist list = parse("#EXTM3U\r\n"
                + "#EXTINF:-1 tvg-id=\"a\" tvg-logo=\"http://l/cnn.png\" group-title=\"News, World\",CNN HD\r\n"
                + "http://h/1.ts\r\n"
                + "#EXTINF:-1,Bare\n#EXTGRP:Movies\nhttp://h/2.m3u8\n"
                + "#EXTINF:-1 tvg-name=\"Fallback\" group-title=\"News, World\",\nhttp://h/3.ts\n"
                + "http://h/4.ts\n"
                + "http://h/1.ts\n");
        JSONArray channels = list.data.getJSONArray("channels");
        assertEquals(5, channels.length());
        JSONObject cnn = channels.getJSONObject(0);
        assertEquals("CNN HD", cnn.getString("name"));
        assertEquals("News, World", cnn.getString("genreId"));
        assertEquals("http://l/cnn.png", cnn.getString("logo"));
        assertEquals(1, cnn.getInt("number"));
        assertEquals("http://h/1.ts", list.urls.get(cnn.getString("id")));
        assertEquals("Movies", channels.getJSONObject(1).getString("genreId")); // #EXTGRP
        assertEquals("Fallback", channels.getJSONObject(2).getString("name")); // tvg-name when the title is empty
        assertEquals("http://h/4.ts", channels.getJSONObject(3).getString("name")); // bare URL shows itself
        assertEquals(M3uPlaylist.UNCATEGORIZED, channels.getJSONObject(3).getString("genreId"));
        assertEquals(3, list.data.getJSONArray("genres").length());
        assertEquals(5, list.urls.size()); // the repeated URL got its own id
        XtreamCatalog.validate(list.data); // StbHost accepts the same shape as Xtream
    }

    @Test public void parse_rejectsTextWithoutEntries() throws Exception {
        try {
            parse("<html>403</html>".replace("<", "#")); // only comment lines
            throw new AssertionError("expected LoadException");
        } catch (M3uPlaylist.LoadException e) {
            assertEquals("INVALID_RESPONSE", e.code);
        }
        assertTrue(true);
    }
}
