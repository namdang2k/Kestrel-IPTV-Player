package com.stbemu.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class XtreamClientTest {
    private MockWebServer server;

    @Before public void start() throws IOException { server = new MockWebServer(); server.start(); }
    @After public void stop() throws IOException { server.shutdown(); }

    private XtreamClient client() {
        return new XtreamClient(server.url("/").toString(), "us er", "p@ss/1", "UA-API");
    }

    private static String userInfo(String auth, String status, String exp, String formats) {
        return "{\"user_info\":{\"auth\":" + auth + ",\"status\":\"" + status + "\",\"exp_date\":" + exp
                + ",\"max_connections\":\"2\",\"active_cons\":\"1\",\"allowed_output_formats\":" + formats
                + "},\"server_info\":{}}";
    }

    private static String code(XtreamClient c, String action) {
        try {
            if (action.equals("auth")) c.authenticate(); else c.liveStreams();
            fail("expected XtreamException");
            return null;
        } catch (XtreamClient.XtreamException e) { return e.code; }
    }

    @Test public void authenticate_sendsCredentialsAndUserAgent() throws Exception {
        server.enqueue(new MockResponse().setBody(userInfo("1", "Active", "\"1767225600\"", "[\"m3u8\",\"ts\"]")));
        XtreamClient.Account a = client().authenticate();
        assertEquals("Active", a.status);
        assertEquals(1767225600L, a.expiresAt);
        assertEquals(2, a.maxConnections);
        assertEquals(1, a.activeConnections);
        assertEquals("ts", a.ext);
        RecordedRequest r = server.takeRequest();
        assertEquals("/player_api.php", r.getRequestUrl().encodedPath());
        assertEquals("us er", r.getRequestUrl().queryParameter("username"));
        assertEquals("p@ss/1", r.getRequestUrl().queryParameter("password"));
        assertNull(r.getRequestUrl().queryParameter("action"));
        assertEquals("UA-API", r.getHeader("User-Agent"));
    }

    @Test public void authenticate_noM3u8_orNoExpiry_usesTsAndZero() throws Exception {
        server.enqueue(new MockResponse().setBody(userInfo("\"1\"", "Active", "null", "[]")));
        XtreamClient.Account a = client().authenticate();
        assertEquals("ts", a.ext);
        assertEquals(0L, a.expiresAt);
    }

    @Test public void authenticate_m3u8Only_usesM3u8() throws Exception {
        server.enqueue(new MockResponse().setBody(userInfo("1", "Active", "0", "[\"m3u8\"]")));
        assertEquals("m3u8", client().authenticate().ext);
    }

    @Test public void authenticate_errors() {
        server.enqueue(new MockResponse().setBody(userInfo("0", "", "null", "[]")));
        assertEquals("AUTHENTICATION_REJECTED", code(client(), "auth"));
        server.enqueue(new MockResponse().setBody(userInfo("1", "Expired", "\"1767225600\"", "[]")));
        assertEquals("ACCOUNT_EXPIRED:2026-01-01", code(client(), "auth"));
        server.enqueue(new MockResponse().setBody(userInfo("1", "Banned", "null", "[]")));
        assertEquals("ACCOUNT_DISABLED", code(client(), "auth"));
        server.enqueue(new MockResponse().setBody("<html>login</html>"));
        assertEquals("INVALID_RESPONSE", code(client(), "auth"));
        server.enqueue(new MockResponse().setBody("{\"other\":1}"));
        assertEquals("INVALID_RESPONSE", code(client(), "auth"));
        server.enqueue(new MockResponse().setResponseCode(404));
        assertEquals("INVALID_RESPONSE", code(client(), "auth"));
        server.enqueue(new MockResponse().setResponseCode(503));
        assertEquals("HTTP_503", code(client(), "auth"));
        server.enqueue(new MockResponse().setResponseCode(403));
        assertEquals("FORBIDDEN", code(client(), "auth"));
    }

    @Test public void timeoutAndNetwork() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        OkHttpClient quick = new OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build();
        assertEquals("TIMEOUT", code(new XtreamClient(server.url("/").toString(), "u", "p", "UA", quick), "auth"));
        String url = server.url("/").toString();
        server.shutdown();
        assertEquals("NETWORK", code(new XtreamClient(url, "u", "p", "UA"), "auth"));
    }

    @Test public void liveLists_sendAction_andRequireArrays() throws Exception {
        server.enqueue(new MockResponse().setBody("[{\"category_id\":\"1\"}]"));
        server.enqueue(new MockResponse().setBody("[{\"stream_id\":1}]"));
        server.enqueue(new MockResponse().setBody("{\"user_info\":{}}"));
        XtreamClient c = client();
        assertEquals(1, c.liveCategories().length());
        assertEquals("get_live_categories", server.takeRequest().getRequestUrl().queryParameter("action"));
        assertEquals(1, c.liveStreams().length());
        assertEquals("get_live_streams", server.takeRequest().getRequestUrl().queryParameter("action"));
        assertEquals("INVALID_RESPONSE", code(c, "streams"));
    }

    @Test public void shortEpg_sendsStreamId_andToleratesMissingListings() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"epg_listings\":[{\"title\":\"VA==\"}]}"));
        server.enqueue(new MockResponse().setBody("{}"));
        XtreamClient c = client();
        JSONArray listings = c.shortEpg("42");
        assertEquals(1, listings.length());
        RecordedRequest r = server.takeRequest();
        assertEquals("get_short_epg", r.getRequestUrl().queryParameter("action"));
        assertEquals("42", r.getRequestUrl().queryParameter("stream_id"));
        assertEquals(0, c.shortEpg("42").length());
    }

    @Test public void streamUrl_encodesSegments() {
        String base = server.url("/").toString().replaceAll("/+$", "");
        assertEquals(base + "/live/us%20er/p%40ss%2F1/42.m3u8", client().streamUrl("42", "m3u8"));
    }

    @Test public void serverUrl_trailingSlashesRemoved() {
        assertEquals("http://h:8080/xc", new XtreamClient("http://h:8080/xc//", "u", "p", "UA").serverUrl);
    }
}
