package com.stbemu.host;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/** Immutable item headers, scoped to the resolved origin even across redirects/segments/keys. */
final class StreamRequestPolicy {
    private static final OkHttpClient BASE = new OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).build();
    private final HttpUrl origin;
    private final Map<String, String> headers;

    StreamRequestPolicy(String uri, Map<String, String> source) {
        origin = HttpUrl.get(uri);
        TreeMap<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        copy.putAll(source);
        // Transport owns these; a portal cannot redirect routing with a Host header.
        copy.remove("Host");
        copy.remove("Content-Length");
        copy.remove("Connection");
        for (Map.Entry<String, String> header : copy.entrySet())
            new Request.Builder().url(origin).header(header.getKey(), header.getValue());
        headers = java.util.Collections.unmodifiableMap(copy);
    }

    private boolean sameOrigin(HttpUrl url) {
        return origin.scheme().equals(url.scheme()) && origin.host().equals(url.host()) && origin.port() == url.port();
    }

    Request apply(Request input) {
        Request.Builder request = input.newBuilder();
        boolean allowed = sameOrigin(input.url());
        for (Map.Entry<String, String> header : headers.entrySet()) {
            request.removeHeader(header.getKey());
            // Player user agent identifies the engine and is safe for CDN requests.
            if (allowed || "User-Agent".equalsIgnoreCase(header.getKey()))
                request.header(header.getKey(), header.getValue());
        }
        if (!allowed) {
            request.removeHeader("Authorization");
            request.removeHeader("Proxy-Authorization");
            request.removeHeader("Cookie");
            request.removeHeader("Cookie2");
        }
        return request.build();
    }

    OkHttpClient client() {
        return client(message -> {});
    }

    OkHttpClient client(java.util.function.Consumer<String> log) {
        // Network interceptors run for every redirect, unlike application interceptors.
        // No process-global cookie jar: account cookies cannot leak into another item.
        java.util.concurrent.atomic.AtomicInteger sequence = new java.util.concurrent.atomic.AtomicInteger();
        return BASE.newBuilder().addNetworkInterceptor(chain -> {
            okhttp3.Request request = apply(chain.request());
            int id = sequence.incrementAndGet();
            java.util.Set<String> removed = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (String name : headers.keySet()) if (request.header(name) == null) removed.add(name);
            log.accept("http.request request=" + id + " origin=" + originLabel(request.url())
                    + " sameOrigin=" + sameOrigin(request.url()) + " headers=" + request.headers().names()
                    + " removedHeaders=" + removed);
            try {
                okhttp3.Response response = chain.proceed(request);
                String location = response.header("Location");
                HttpUrl redirect = location == null ? null : request.url().resolve(location);
                log.accept("http.response request=" + id + " status=" + response.code()
                        + (response.isRedirect() && redirect != null ? " redirectOrigin=" + originLabel(redirect)
                        + " redirectSameOrigin=" + sameOrigin(redirect) : ""));
                return response;
            } catch (java.io.IOException failure) {
                log.accept("http.failure request=" + id + " type=" + failure.getClass().getSimpleName());
                throw failure;
            }
        }).build();
    }

    private static String originLabel(HttpUrl url) {
        return url.scheme() + "://" + url.host() + ":" + url.port();
    }
}
