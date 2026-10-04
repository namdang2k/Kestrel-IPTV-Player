package com.stbemu.host;

import java.util.HashMap;
import java.util.Map;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;
import static org.junit.Assert.*;

public class StreamRequestPolicyTest {
    @Test public void diagnosticsShowRedirectAndHeaderNamesWithoutSecrets() throws Exception {
        try (MockWebServer origin = new MockWebServer(); MockWebServer cdn = new MockWebServer()) {
            origin.start(); cdn.start();
            origin.enqueue(new MockResponse().setResponseCode(302)
                    .addHeader("Location", cdn.url("/private-path?token=secret-query")));
            cdn.enqueue(new MockResponse().setResponseCode(401));
            java.util.List<String> logs = new java.util.ArrayList<>();
            String url = origin.url("/secret-path?token=private-query").toString();
            StreamRequestPolicy policy = new StreamRequestPolicy(url, headers());
            try (Response response = policy.client(logs::add).newCall(new Request.Builder().url(url).build()).execute()) {
                assertEquals(401, response.code());
            }
            String log = String.join("\n", logs);
            assertTrue(log.contains("status=302"));
            assertTrue(log.contains("status=401"));
            assertTrue(log.contains("redirectSameOrigin=false"));
            assertTrue(log.contains("removedHeaders=[authorization, Cookie, X-Portal-Token]"));
            assertTrue(log.contains("User-Agent"));
            for (String secret : new String[]{"Bearer fixture", "session=fixture", "fixture-player",
                    "secret-query", "private-query", "private-path", "secret-path"}) assertFalse(log.contains(secret));
        }
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new HashMap<>();
        headers.put("authorization", "Bearer fixture");
        headers.put("Cookie", "session=fixture");
        headers.put("X-Portal-Token", "fixture");
        headers.put("User-Agent", "fixture-player");
        return headers;
    }

    @Test public void originIncludesSchemeAndPortAndHeadersAreImmutable() {
        Map<String, String> source = headers();
        StreamRequestPolicy policy = new StreamRequestPolicy("https://stream.test/live", source);
        source.put("authorization", "changed");
        Request same = policy.apply(new Request.Builder().url("https://stream.test/key").build());
        assertEquals("Bearer fixture", same.header("Authorization"));
        for (String target : new String[]{"http://stream.test/key", "https://stream.test:444/key", "https://cdn.test/key"}) {
            Request other = policy.apply(new Request.Builder().url(target).build());
            assertNull(other.header("Authorization"));
            assertNull(other.header("Cookie"));
            assertNull(other.header("X-Portal-Token"));
            assertEquals("fixture-player", other.header("User-Agent"));
        }
    }

    @Test public void redirectsDoNotForwardCredentialsToOtherOrigin() throws Exception {
        try (MockWebServer origin = new MockWebServer(); MockWebServer cdn = new MockWebServer()) {
            origin.start(); cdn.start();
            origin.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", cdn.url("/segment")));
            cdn.enqueue(new MockResponse().setBody("fixture"));
            StreamRequestPolicy policy = new StreamRequestPolicy(origin.url("/live").toString(), headers());
            try (Response response = policy.client().newCall(new Request.Builder().url(origin.url("/live")).build()).execute()) {
                assertEquals(200, response.code());
            }
            assertEquals("Bearer fixture", origin.takeRequest().getHeader("Authorization"));
            okhttp3.mockwebserver.RecordedRequest redirected = cdn.takeRequest();
            assertNull(redirected.getHeader("Authorization"));
            assertNull(redirected.getHeader("Cookie"));
            assertNull(redirected.getHeader("X-Portal-Token"));
        }
    }
}
