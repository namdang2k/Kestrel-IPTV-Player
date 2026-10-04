package com.stbemu.host;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import okhttp3.*;

/** Loopback-only transport for libavformat, retaining the portal's per-origin header policy.
 * HLS playlists are rewritten so variants, segments and keys also pass through OkHttp.
 * Never exposes upstream URLs/credentials to native logs. One gateway belongs to one item.
 */
final class MpvStreamGateway implements AutoCloseable {
    private final ServerSocket server;
    private final OkHttpClient client;
    private final String token = UUID.randomUUID().toString();
    private final Map<String, HttpUrl> routes = Collections.synchronizedMap(new LinkedHashMap<String, HttpUrl>(256, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, HttpUrl> entry) { return size() > 4096; }
    });
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private volatile boolean closed;
    private final java.util.function.Consumer<String> log;
    private final Map<HttpUrl, IOException> failures = new ConcurrentHashMap<>();
    static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status) { super("MPV upstream HTTP " + status); this.status = status; }
    }

    IOException failure() { return failures.values().stream().findFirst().orElse(null); }
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]+)\"");
    private static final int MAX_PLAYLIST = 2 * 1024 * 1024;

    MpvStreamGateway(String url, Map<String, String> headers) throws IOException {
        this(url, headers, message -> {});
    }

    MpvStreamGateway(String url, Map<String, String> headers, java.util.function.Consumer<String> log) throws IOException {
        this.log = log;
        client = new StreamRequestPolicy(url, headers).client(log);
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        Thread accept = new Thread(() -> {
            while (!closed) {
                try {
                    Socket socket = server.accept();
                    sockets.add(socket);
                    workers.execute(() -> serve(socket));
                } catch (IOException | RejectedExecutionException ignored) {
                    if (!closed) close();
                }
            }
        }, "mpv-http");
        accept.setDaemon(true);
        accept.start();
    }

    String route(String url) {
        HttpUrl remote = HttpUrl.get(url);
        String id = UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)).toString();
        // Preserve the extension for demuxer detection; all other URL data remains in memory.
        String name = remote.pathSegments().get(remote.pathSegments().size() - 1);
        String suffix = name.matches(".*\\.[a-zA-Z0-9]{1,8}") ? name.substring(name.lastIndexOf('.')) : "";
        String path = "/" + token + "/" + id + suffix;
        routes.put(path, remote);
        return "http://127.0.0.1:" + server.getLocalPort() + path;
    }

    private String resolve(HttpUrl base, String reference) throws IOException {
        HttpUrl target = base.resolve(reference);
        if (target == null) throw new IOException("Unsupported playlist URI");
        return route(target.toString());
    }

    String rewritePlaylist(HttpUrl base, String text) throws IOException {
        StringBuilder output = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            if (!line.isEmpty() && !line.startsWith("#")) line = resolve(base, line.trim());
            else {
                Matcher matcher = URI_ATTRIBUTE.matcher(line);
                StringBuffer replaced = new StringBuffer();
                while (matcher.find()) matcher.appendReplacement(replaced,
                        Matcher.quoteReplacement("URI=\"" + resolve(base, matcher.group(1)) + "\""));
                matcher.appendTail(replaced);
                line = replaced.toString();
            }
            output.append(line).append('\n');
        }
        return output.toString();
    }

    private void serve(Socket socket) {
        Call call = null;
        HttpUrl remote = null;
        boolean responseReceived = false;
        try (Socket owned = socket) {
            socket.setSoTimeout(10000);
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String first = input.readLine();
            if (first == null || first.length() > 8192) return;
            String[] request = first.split(" ");
            OutputStream output = socket.getOutputStream();
            remote = request.length == 3 ? routes.get(request[1]) : null;
            if (remote == null || !(request[0].equals("GET") || request[0].equals("HEAD"))) {
                status(output, 404, "text/plain", 0, null); return;
            }
            Request.Builder upstream = new Request.Builder().url(remote).method(request[0], null);
            String line;
            int size = 0;
            while ((line = input.readLine()) != null && !line.isEmpty()) {
                if ((size += line.length()) > 32768) return;
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("Range"))
                    upstream.header("Range", line.substring(colon + 1).trim());
            }
            call = client.newCall(upstream.build());
            calls.add(call);
            if (closed) return;
            try (Response response = call.execute()) {
                responseReceived = true;
                if (!response.isSuccessful()) failures.put(remote, new HttpFailure(response.code()));
                else failures.remove(remote);
                ResponseBody body = response.body();
                if (body == null) { status(output, 502, "text/plain", 0, null); return; }
                String type = response.header("Content-Type", "application/octet-stream");
                BufferedInputStream bytes = new BufferedInputStream(body.byteStream());
                bytes.mark(512);
                byte[] prefix = new byte[256];
                int count = bytes.read(prefix);
                bytes.reset();
                String sniff = count <= 0 ? "" : new String(prefix, 0, count, StandardCharsets.UTF_8).trim();
                boolean playlist = sniff.startsWith("#EXTM3U");
                // DASH needs XML BaseURL/template rewriting. Fail explicitly instead of bypassing header isolation.
                if (type.contains("dash+xml") || sniff.contains("<MPD")) {
                    status(output, 415, "text/plain", 0, null); return;
                }
                if (playlist && response.isSuccessful()) {
                    ByteArrayOutputStream data = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = bytes.read(buffer)) != -1) {
                        if (data.size() + n > MAX_PLAYLIST) throw new IOException("Playlist too large");
                        data.write(buffer, 0, n);
                    }
                    byte[] rewritten = rewritePlaylist(response.request().url(),
                            data.toString(StandardCharsets.UTF_8.name())).getBytes(StandardCharsets.UTF_8);
                    status(output, response.code(), "application/vnd.apple.mpegurl", rewritten.length, null);
                    if (!request[0].equals("HEAD")) output.write(rewritten);
                } else {
                    status(output, response.code(), type, body.contentLength(), response.header("Content-Range"));
                    if (!request[0].equals("HEAD")) {
                        byte[] buffer = new byte[32768];
                        int n;
                        while (!closed && (n = bytes.read(buffer)) != -1) output.write(buffer, 0, n);
                    }
                }
            }
        } catch (IOException failure) {
            if (!closed && call != null && !call.isCanceled()) {
                log.accept("gateway.io-failure phase=" + (responseReceived ? "transfer" : "connect")
                        + " type=" + failure.getClass().getSimpleName());
                if (!responseReceived && remote != null)
                    failures.put(remote, new IOException("MPV upstream " + failure.getClass().getSimpleName()));
            }
        } finally {
            if (call != null) { calls.remove(call); call.cancel(); }
            sockets.remove(socket);
        }
    }

    private static void status(OutputStream output, int code, String type, long length, String range) throws IOException {
        String headers = "HTTP/1.1 " + code + " Stream\r\nConnection: close\r\nContent-Type: " + type + "\r\n"
                + (length >= 0 ? "Content-Length: " + length + "\r\n" : "")
                + (range != null ? "Content-Range: " + range + "\r\n" : "") + "\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    @Override public void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) {}
        for (Call call : calls) call.cancel();
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) {}
        workers.shutdownNow();
        routes.clear();
    }
}
