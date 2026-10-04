package com.stbemu.host;

import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class MpvStreamGatewayTest {
    private final OkHttpClient player = new OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).build();
    private Map<String, String> headers() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer fixture");
        headers.put("Cookie", "session=fixture");
        headers.put("Referer", "https://portal.test/");
        headers.put("User-Agent", "fixture-player");
        return headers;
    }
    private String get(String url) throws Exception {
        try (Response response = player.newCall(new Request.Builder().url(url).build()).execute()) {
            assertEquals(200, response.code());
            return response.body().string();
        }
    }

    @Test public void progressiveTsAndRangeRetainOriginHeaders() throws Exception {
        try (MockWebServer origin = new MockWebServer()) {
            origin.start();
            origin.enqueue(new MockResponse().setResponseCode(206).setBody("ts-payload")
                    .addHeader("Content-Range", "bytes 10-19/20").addHeader("Content-Type", "video/mp2t"));
            String url = origin.url("/live.ts?token=private").toString();
            try (MpvStreamGateway gateway = new MpvStreamGateway(url, headers())) {
                String local = gateway.route(url);
                assertFalse(local.contains("private"));
                assertTrue(local.endsWith(".ts"));
                try (Response response = player.newCall(new Request.Builder().url(local).header("Range", "bytes=10-").build()).execute()) {
                    assertEquals(206, response.code());
                    assertEquals("bytes 10-19/20", response.header("Content-Range"));
                    assertEquals("ts-payload", response.body().string());
                }
                RecordedRequest request = origin.takeRequest(2, TimeUnit.SECONDS);
                assertNotNull(request);
                assertEquals("bytes=10-", request.getHeader("Range"));
                assertEquals("Bearer fixture", request.getHeader("Authorization"));
                assertEquals("session=fixture", request.getHeader("Cookie"));
                assertEquals("https://portal.test/", request.getHeader("Referer"));
            }
        }
    }

    @Test public void retryUsesSameResolvedUrlAndHeadersAndPreservesHttpFailure() throws Exception {
        try (MockWebServer origin = new MockWebServer()) {
            origin.start();
            String url = origin.url("/resolved/live.ts?token=fixture").toString();
            origin.enqueue(new MockResponse().setResponseCode(403).setBody("denied"));
            origin.enqueue(new MockResponse().setBody("ts-payload"));
            try (MpvStreamGateway gateway = new MpvStreamGateway(url, headers())) {
                String local = gateway.route(url);
                try (Response response = player.newCall(new Request.Builder().url(local).build()).execute()) {
                    assertEquals(403, response.code());
                    assertTrue(gateway.failure() instanceof MpvStreamGateway.HttpFailure);
                    assertEquals(403, ((MpvStreamGateway.HttpFailure) gateway.failure()).status);
                }
                assertEquals("ts-payload", get(local));
                assertNull(gateway.failure());
                for (int i = 0; i < 2; i++) {
                    RecordedRequest request = origin.takeRequest(2, TimeUnit.SECONDS);
                    assertNotNull(request);
                    assertEquals("/resolved/live.ts?token=fixture", request.getPath());
                    assertEquals("Bearer fixture", request.getHeader("Authorization"));
                    assertEquals("session=fixture", request.getHeader("Cookie"));
                }
            }
        }
    }

    @Test public void redirectedHlsRewritesRelativeSegmentsAndKeysWithoutLeakingHeaders() throws Exception {
        try (MockWebServer origin = new MockWebServer(); MockWebServer cdn = new MockWebServer()) {
            origin.start(); cdn.start();
            origin.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", cdn.url("/channel/list")));
            cdn.enqueue(new MockResponse().setBody("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:5,\nseg.ts?q=1\n"));
            cdn.enqueue(new MockResponse().setBody("key-payload"));
            cdn.enqueue(new MockResponse().setBody("segment-payload"));
            String url = origin.url("/play.ts").toString();
            try (MpvStreamGateway gateway = new MpvStreamGateway(url, headers())) {
                String playlist = get(gateway.route(url));
                String key = playlist.split("URI=\"")[1].split("\"")[0];
                String segment = playlist.split("\n")[3];
                assertEquals("key-payload", get(key));
                assertEquals("segment-payload", get(segment));
                assertEquals("Bearer fixture", origin.takeRequest(2, TimeUnit.SECONDS).getHeader("Authorization"));
                for (String path : new String[]{"/channel/list", "/channel/key.bin", "/channel/seg.ts?q=1"}) {
                    RecordedRequest request = cdn.takeRequest(2, TimeUnit.SECONDS);
                    assertNotNull(request);
                    assertEquals(path, request.getPath());
                    assertNull(request.getHeader("Authorization"));
                    assertNull(request.getHeader("Cookie"));
                    assertNull(request.getHeader("Referer"));
                    assertEquals("fixture-player", request.getHeader("User-Agent"));
                }
            }
        }
    }

    @Test public void variantAndSameOriginKeysKeepHeadersAndRoutesAreStable() throws Exception {
        try (MockWebServer origin = new MockWebServer()) {
            origin.start();
            String url = origin.url("/master.m3u8").toString();
            origin.enqueue(new MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nvariant.m3u8\n"));
            origin.enqueue(new MockResponse().setBody("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:5,\nsegment.m4s\n"));
            origin.enqueue(new MockResponse().setBody("init"));
            try (MpvStreamGateway gateway = new MpvStreamGateway(url, headers())) {
                assertEquals(gateway.route(url), gateway.route(url));
                String variant = get(gateway.route(url)).split("\n")[2];
                String playlist = get(variant);
                assertEquals("init", get(playlist.split("URI=\"")[1].split("\"")[0]));
                for (int i = 0; i < 3; i++) assertEquals("Bearer fixture", origin.takeRequest(2, TimeUnit.SECONDS).getHeader("Authorization"));
            }
        }
    }

    @Test public void invalidGatewayTokenCannotReachOrigin() throws Exception {
        try (MpvStreamGateway gateway = new MpvStreamGateway("https://example.test/live.ts", headers())) {
            HttpUrl route = HttpUrl.get(gateway.route("https://example.test/live.ts"));
            try (Response response = player.newCall(new Request.Builder().url(route.newBuilder().encodedPath("/invalid").build()).build()).execute()) {
                assertEquals(404, response.code());
            }
        }
    }
}
