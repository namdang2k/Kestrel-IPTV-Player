# Xtream Live TV — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Thay hệ thống Stalker portal (WebView + MAC) bằng Xtream Codes API (Server URL + username + password) cho Live TV, giữ nguyên UI TV native, quảng cáo và Pro.

**Architecture:** Hai lớp Java mới trong `Native/StbHost` (`XtreamClient` gọi `player_api.php`, `XtreamCatalog` đổi JSON Xtream sang payload `channels.data` mà `TvScreen` đang dùng) thay cho WebView + `stb_facade.js`. `StbHost` giữ nguyên các sự kiện gửi sang C# (`portal.ready` / `portal.error` / `portal.unavailable` / `portal.phase` / `portal.catalog-error`, `ui.*`), nên `AppFlow`, quảng cáo và retry trong `AndroidPortalView` gần như không đổi. Phía C# đổi `Profile` sang URL/user/pass, `PortalSession` gửi snapshot mới, và bỏ lớp routing lệnh player của facade.

**Tech Stack:** Unity 6000.6.3f1 (C#, UniTask, Newtonsoft.Json, NUnit EditMode), Android library Java 17 (AGP 9.1.1, Media3 1.11.1 ExoPlayer, OkHttp 4.12 qua `media3-datasource-okhttp`, org.json), JUnit 4 + MockWebServer 4.12.0.

**Spec:** `Docs/superpowers/specs/2026-10-12-xtream-live-design.vi.md`

## Global Constraints

- Chỉ Live TV. Không VOD, Series, catch-up playback, M3U, cache đĩa, mã hoá password, migrate profile cũ.
- Tên nội bộ `Portal*` giữ nguyên (`IPortalView`, `PortalSession`, `AndroidPortalView`, `openPortal`, sự kiện `portal.*`). Chỉ đổi chữ hiển thị: "portal" → "server", "MAC" → "username"/"account".
- Chữ hiển thị trong app viết **tiếng Anh**, giống phần còn lại của UI.
- Snapshot JSON C# → Java (tham số `snapshotJson` của `StbHost.openPortal(url, snapshotJson, userAgent)`) có đúng các khoá: `sessionId`, `operationId`, `serverUrl`, `username`, `password`, `apiUserAgent`, `playerUserAgent`. `url` = `serverUrl`, `userAgent` = `apiUserAgent`.
- UA API mặc định: `Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250 stbapp ver: 2 rev: 250 Safari/533.3`. UA stream mặc định: `Lavf53.32.100`.
- Không bao giờ log password, ra Firebase hay logcat. URL stream chứa password nên cũng không được log.
- Profile JSON: `schemaVersion = 2`; file có schema nhỏ hơn 2 được đọc thành danh sách rỗng; schema lớn hơn 2 vẫn ném `ProfileStoreException` như cũ.
- URL stream: `{serverUrl}/live/{username}/{password}/{streamId}.{ext}`; `ext = "m3u8"` nếu `allowed_output_formats` chứa `m3u8`, ngược lại `"ts"`. Mỗi đoạn path được URL-encode.
- Timeout HTTP API: connect 15 s, read 15 s.
- Mã lỗi (giữa Java ↔ C#): `NETWORK`, `TIMEOUT`, `HTTP_<5xx>`, `FORBIDDEN`, `AUTHENTICATION_REJECTED`, `ACCOUNT_EXPIRED` hoặc `ACCOUNT_EXPIRED:<yyyy-MM-dd>`, `ACCOUNT_DISABLED`, `INVALID_RESPONSE`, `CONFIGURATION_INVALID`, `MAX_CONNECTIONS`; lỗi tải danh sách kênh đi qua `portal.catalog-error`, và C# nhận dưới dạng `CATALOG_<mã>`.
- Làm trên nhánh `feature/xtream-live` (tạo từ `master`). Mỗi commit chỉ `git add` đúng file của task, vì working tree đang có các file Firebase/ProjectSettings chưa commit không liên quan.

**Lệnh dùng chung:**
- Test JVM: `cd Native/StbHost && ./gradlew testDebugUnitTest` (Git Bash).
- Build `.aar`: `cd Native/StbHost && ./gradlew copyToUnity`.
- Test Unity EditMode (đóng Unity Editor trước):
  `"/c/Program Files/Unity/Hub/Editor/6000.6.3f1/Editor/Unity.exe" -batchmode -nographics -projectPath "C:/Unity Project/Kestrel-IPTV-Player" -runTests -testPlatform EditMode -testResults "C:/Users/Hello/AppData/Local/Temp/editmode-results.xml" -logFile -`
  Nếu Editor đang mở thì chạy qua Window → General → Test Runner → EditMode → Run All. Kết quả mong đợi: `result="Passed"` trong file XML và không có lỗi compile trong log.

## Review Focus

1. **Logo kênh không hiện**: Xtream trả `stream_icon` là URL đầy đủ, nhưng `TvScreen` chỉ tải logo khi `logoBase` khác rỗng. Mong đợi: logo URL tuyệt đối hiện bình thường. Test và sửa ở Task 3, Step 1-4.
2. **Password có ký tự đặc biệt** (`@`, `/`, `#`, khoảng trắng): URL stream và query API phải encode đúng; password không bị `trim()`. Test ở Task 1 (`streamUrl_encodesSegments`, `authenticate_sendsCredentialsAndUserAgent`), Task 4 (`formValue_keepsPasswordWhitespace`) và Task 5 (`Add_KeepsPasswordExactly`).
3. **Server URL dán kiểu khác nhau** (thiếu `http://`, dư `/`, dán cả link `get.php?...`): phải chuẩn hoá về cùng một địa chỉ, và không cho tạo trùng tài khoản. Test ở Task 5 (`ServerAddress_Normalize`, `Add_RejectsSameAccountTypedDifferently`).
4. **Kết quả mạng về trễ của session cũ** (bấm ⟳ rồi về Home, hoặc đổi profile khi đang tải): kết quả cũ không được ghi đè UI của session mới. Kiểm bằng `generation != portalGeneration` ở mọi callback của Task 3; kiểm tra tay ở Task 8 (mục "đổi profile khi đang tải").
5. **Danh sách kênh bẩn** (`num` null hoặc chuỗi, `stream_id` trùng hoặc thiếu, `category_id` null, tên rỗng): không được làm hỏng cả catalog. Test ở Task 2 (`channelsData_mapsAndCleansXtreamFields`).

---

### Task 0: Tạo nhánh

- [ ] **Step 1:** `git switch -c feature/xtream-live`
- [ ] **Step 2:** `git status --short`. Kết quả mong đợi: chỉ các file `.meta` Firebase / `google-services*` / `ProjectSettings/*` đã có từ trước. Không đụng tới chúng.

---

### Task 1: `XtreamClient` — gọi `player_api.php`

**Files:**
- Create: `Native/StbHost/src/main/java/com/stbemu/host/XtreamClient.java`
- Test: `Native/StbHost/src/test/java/com/stbemu/host/XtreamClientTest.java`

**Interfaces:**
- Produces:
  - `final class XtreamClient` với các thành phần sau:
    - constructor `XtreamClient(String serverUrl, String username, String password, String userAgent)` và một constructor nhận thêm `OkHttpClient http` để test
    - field `final String serverUrl` (không có `/` ở cuối)
    - `Account authenticate() throws XtreamException`
    - `JSONArray liveCategories() throws XtreamException`
    - `JSONArray liveStreams() throws XtreamException`
    - `JSONArray shortEpg(String streamId) throws XtreamException`
    - `String streamUrl(String streamId, String ext)`
  - `static final class XtreamClient.XtreamException extends Exception { final String code; }`
  - `static final class XtreamClient.Account { final String status; final long expiresAt; final int maxConnections, activeConnections; final String ext; }`, với constructor package-private theo đúng thứ tự đó.

- [ ] **Step 1: Viết test (sẽ fail)**

```java
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
        assertEquals("m3u8", a.ext);
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
```

- [ ] **Step 2: Chạy test, xác nhận fail**

Run: `cd Native/StbHost && ./gradlew testDebugUnitTest --tests com.stbemu.host.XtreamClientTest`
Expected: lỗi compile `cannot find symbol: class XtreamClient`.

- [ ] **Step 3: Viết code**

```java
package com.stbemu.host;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Blocking Xtream Codes player_api.php client. Never call on the UI thread. */
final class XtreamClient {
    static final class XtreamException extends Exception {
        final String code;
        XtreamException(String code) { super(code); this.code = code; }
    }

    /** user_info of an authenticated, active account. */
    static final class Account {
        final String status;
        final long expiresAt; // unix seconds; 0 = no expiry
        final int maxConnections, activeConnections;
        final String ext; // "m3u8" or "ts"

        Account(String status, long expiresAt, int maxConnections, int activeConnections, String ext) {
            this.status = status;
            this.expiresAt = expiresAt;
            this.maxConnections = maxConnections;
            this.activeConnections = activeConnections;
            this.ext = ext;
        }
    }

    private static final OkHttpClient BASE = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).build();

    final String serverUrl;
    private final HttpUrl api;
    private final String username, password, userAgent;
    private final OkHttpClient http;

    XtreamClient(String serverUrl, String username, String password, String userAgent) {
        this(serverUrl, username, password, userAgent, BASE);
    }

    /** @throws IllegalArgumentException when serverUrl is not an http(s) URL */
    XtreamClient(String serverUrl, String username, String password, String userAgent, OkHttpClient http) {
        this.serverUrl = serverUrl.replaceAll("/+$", "");
        this.api = HttpUrl.get(this.serverUrl + "/player_api.php");
        this.username = username;
        this.password = password;
        this.userAgent = userAgent;
        this.http = http;
    }

    Account authenticate() throws XtreamException {
        Object body = get(null, null);
        JSONObject user = body instanceof JSONObject ? ((JSONObject) body).optJSONObject("user_info") : null;
        if (user == null) throw new XtreamException("INVALID_RESPONSE");
        if (user.optInt("auth", 0) != 1) throw new XtreamException("AUTHENTICATION_REJECTED");
        String status = user.optString("status", "");
        long expiresAt = user.optLong("exp_date", 0);
        if ("Expired".equalsIgnoreCase(status))
            throw new XtreamException(expiresAt > 0 ? "ACCOUNT_EXPIRED:" + utcDate(expiresAt) : "ACCOUNT_EXPIRED");
        if (!"Active".equalsIgnoreCase(status)) throw new XtreamException("ACCOUNT_DISABLED");
        JSONArray formats = user.optJSONArray("allowed_output_formats");
        String ext = "ts";
        for (int i = 0; formats != null && i < formats.length(); i++)
            if ("m3u8".equalsIgnoreCase(formats.optString(i))) ext = "m3u8";
        return new Account(status, expiresAt, user.optInt("max_connections", 0), user.optInt("active_cons", 0), ext);
    }

    JSONArray liveCategories() throws XtreamException { return array(get("get_live_categories", null)); }

    JSONArray liveStreams() throws XtreamException { return array(get("get_live_streams", null)); }

    /** epg_listings of get_short_epg; empty when the server has no guide for the channel. */
    JSONArray shortEpg(String streamId) throws XtreamException {
        Object body = get("get_short_epg", streamId);
        if (!(body instanceof JSONObject)) throw new XtreamException("INVALID_RESPONSE");
        JSONArray listings = ((JSONObject) body).optJSONArray("epg_listings");
        return listings == null ? new JSONArray() : listings;
    }

    String streamUrl(String streamId, String ext) {
        return serverUrl + "/live/" + segment(username) + "/" + segment(password) + "/" + segment(streamId) + "." + ext;
    }

    private Object get(String action, String streamId) throws XtreamException {
        HttpUrl.Builder url = api.newBuilder()
                .addQueryParameter("username", username)
                .addQueryParameter("password", password);
        if (action != null) url.addQueryParameter("action", action);
        if (streamId != null) url.addQueryParameter("stream_id", streamId);
        Request request = new Request.Builder().url(url.build()).header("User-Agent", userAgent).build();
        try (Response response = http.newCall(request).execute()) {
            int code = response.code();
            if (code == 401 || code == 403) throw new XtreamException("FORBIDDEN");
            if (code >= 500) throw new XtreamException("HTTP_" + code);
            if (!response.isSuccessful() || response.body() == null) throw new XtreamException("INVALID_RESPONSE");
            try {
                return new JSONTokener(response.body().string()).nextValue();
            } catch (JSONException e) {
                throw new XtreamException("INVALID_RESPONSE");
            }
        } catch (SocketTimeoutException e) {
            throw new XtreamException("TIMEOUT");
        } catch (IOException e) {
            throw new XtreamException("NETWORK");
        }
    }

    private static JSONArray array(Object body) throws XtreamException {
        if (!(body instanceof JSONArray)) throw new XtreamException("INVALID_RESPONSE");
        return (JSONArray) body;
    }

    private static String segment(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String utcDate(long seconds) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(seconds * 1000));
    }
}
```

Lưu ý: `JSONTokener.nextValue()` trả về `String` (không ném lỗi) với body HTML, nên `authenticate()` / `array()` kiểm `instanceof` để chuyển thành `INVALID_RESPONSE`.

- [ ] **Step 4: Chạy test, xác nhận pass**

Run: `cd Native/StbHost && ./gradlew testDebugUnitTest --tests com.stbemu.host.XtreamClientTest`
Expected: `BUILD SUCCESSFUL`, 8 test pass.

- [ ] **Step 5: Commit**

```bash
git add Native/StbHost/src/main/java/com/stbemu/host/XtreamClient.java Native/StbHost/src/test/java/com/stbemu/host/XtreamClientTest.java
git commit -m "feat(xtream): add player_api.php client"
```

---

### Task 2: `XtreamCatalog` — đổi JSON Xtream sang payload của TV screen

**Files:**
- Create: `Native/StbHost/src/main/java/com/stbemu/host/XtreamCatalog.java`
- Test: `Native/StbHost/src/test/java/com/stbemu/host/XtreamCatalogTest.java`

**Interfaces:**
- Consumes: `XtreamClient.Account` (Task 1).
- Produces:
  - `static JSONObject channelsData(JSONArray categories, JSONArray streams) throws JSONException` trả về `{channels[{id,number,name,genreId,logo,archive}], genres[{id,title}], favIds:[], logoBase:""}`.
  - `static void validate(JSONObject data) throws JSONException`: chuyển nguyên từ `StbHost.validateCatalog`.
  - `static JSONArray programs(JSONArray listings, TimeZone zone) throws JSONException` trả về `[{title, description, time}]`.
  - `static void putInfo(JSONObject target, XtreamClient.Account account, String username, TimeZone zone) throws JSONException`, ghi các khoá `expiry`, `username`, `status`, `connections`.

- [ ] **Step 1: Viết test (sẽ fail)**

```java
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
```

- [ ] **Step 2: Chạy test, xác nhận fail**

Run: `cd Native/StbHost && ./gradlew testDebugUnitTest --tests com.stbemu.host.XtreamCatalogTest`
Expected: lỗi compile `cannot find symbol: class XtreamCatalog`.

- [ ] **Step 3: Viết code**

```java
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
```

- [ ] **Step 4: Chạy test, xác nhận pass**

Run: `cd Native/StbHost && ./gradlew testDebugUnitTest --tests com.stbemu.host.XtreamCatalogTest`
Expected: `BUILD SUCCESSFUL`, 4 test pass.

- [ ] **Step 5: Commit**

```bash
git add Native/StbHost/src/main/java/com/stbemu/host/XtreamCatalog.java Native/StbHost/src/test/java/com/stbemu/host/XtreamCatalogTest.java
git commit -m "feat(xtream): map Xtream live catalog, EPG and account info"
```

---

### Task 3: `StbHost` dùng Xtream thay WebView

Đây là task lớn nhất. `StbHost.java` có khoảng 2000 dòng; khoảng 76 chỗ dùng `webView`, `portalReply`, `facade*`, `channelCmds` và `fast*`. Làm lần lượt theo từng step; sau mỗi step build lại để bắt lỗi compile sớm.

**Files:**
- Modify: `Native/StbHost/src/main/java/com/stbemu/host/StbHost.java`
- Modify: `Native/StbHost/src/main/java/com/stbemu/host/TvScreen.java` (logo URL tuyệt đối tại dòng ~804; panel info tại dòng ~617-627; chữ "Portal info" tại dòng ~136; "Could not load portal data" tại dòng ~611)
- Modify: `Native/StbHost/build.gradle` (bỏ `androidx.webkit`)
- Modify: `Assets/Plugins/Android/mainTemplate.gradle:14` (bỏ `androidx.webkit`)
- Delete: `Native/StbHost/src/main/assets/stb_facade.js`, `Native/StbHost/test/` (cả thư mục: test Node của facade)
- Test: `Native/StbHost/src/test/java/com/stbemu/host/TvScreenLogoTest.java` (mới)

**Interfaces:**
- Consumes:
  - từ Task 1: `XtreamClient`, `XtreamClient.Account`, `XtreamClient.XtreamException`
  - từ Task 2: `XtreamCatalog.channelsData/validate/programs/putInfo`
- Produces (C# sẽ dùng ở Task 6):
  - Còn giữ: `openPortal(String url, String snapshotJson, String userAgent)`, `closePortal()`, `abortPortalReconnect(String)`, `portalReconnectFailed()`, `setPortalVisible(boolean)`.
  - **Bị xoá:** `publishToPortal(String)`, `playFromPortal(String, String)`, `stopFromPortal()`.
  - Sự kiện gửi sang C#: `portal.phase` (`<sid>|AUTHENTICATING`, `<sid>|LOADING`), `portal.ready` (`<sid>`), `portal.error` / `portal.unavailable` (`<sid>|<code>`), `portal.catalog-error` (`<sid>|<code>`), `error` (`<code>`, kể cả `MAX_CONNECTIONS`, `ACCOUNT_*`).
  - **Không còn gửi:** `portal.message`.

- [ ] **Step 1: Viết test logo (sẽ fail)**

Tách logic chọn URL logo của `TvScreen` thành một hàm static để test được.

```java
package com.stbemu.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TvScreenLogoTest {
    @Test public void absoluteLogoIgnoresBase() {
        assertEquals("http://l/a.png", TvScreen.logoUrl("", "http://l/a.png"));
        assertEquals("https://l/a.png", TvScreen.logoUrl("http://base/", "https://l/a.png"));
    }
    @Test public void relativeLogoNeedsBase() {
        assertEquals("http://base/a.png", TvScreen.logoUrl("http://base/", "a.png"));
        assertNull(TvScreen.logoUrl("", "a.png"));
    }
    @Test public void emptyOrMissingLogo() {
        assertNull(TvScreen.logoUrl("http://base/", ""));
        assertNull(TvScreen.logoUrl("http://base/", null));
    }
}
```

- [ ] **Step 2: Chạy test, xác nhận fail**

Run: `cd Native/StbHost && ./gradlew testDebugUnitTest --tests com.stbemu.host.TvScreenLogoTest`
Expected: lỗi compile `cannot find symbol: method logoUrl`.

- [ ] **Step 3: Sửa `TvScreen`**

Thêm vào `TvScreen`:

```java
    /** Xtream sends absolute icon URLs; Stalker-style relative names need logoBase. */
    static String logoUrl(String logoBase, String file) {
        if (file == null || file.isEmpty()) return null;
        if (file.startsWith("http://") || file.startsWith("https://")) return file;
        return logoBase.isEmpty() ? null : logoBase + file;
    }
```

Ở dòng ~804, thay
`logos.load(file == null || logoBase.isEmpty() ? null : logoBase + file, (ImageView) row.getChildAt(0));`
bằng
`logos.load(logoUrl(logoBase, file), (ImageView) row.getChildAt(0));`

Thay nhánh `info` trong `setDetails` (dòng ~623-627):

```java
        if ("info".equals(detailMode)) {
            detailText("Subscription Expiry Date:", 20, PortalStyle.TEXT);
            detailText(data.optString("expiry", "").isEmpty() ? "Not provided by server" : data.optString("expiry"), 20, PortalStyle.TEXT);
            detailText("Username: " + data.optString("username", ""), 20, PortalStyle.TEXT);
            detailText("Status: " + data.optString("status", "") + " · Connections: " + data.optString("connections", ""), 17, PortalStyle.MUTED);
        } else {
```

Đổi chữ hiển thị: `"Portal info"` → `"Account info"`; `"Could not load portal data. Tap the tab to retry."` → `"Could not load account data. Tap the tab to retry."`.

- [ ] **Step 4: Chạy test, xác nhận pass**

Run: `cd Native/StbHost && ./gradlew testDebugUnitTest --tests com.stbemu.host.TvScreenLogoTest`
Expected: PASS (3 test).

- [ ] **Step 5: Thêm state Xtream vào `StbHost`**

Thêm field cạnh `activeSessionId` (~dòng 136):

```java
    private final java.util.concurrent.ExecutorService xtreamIo = java.util.concurrent.Executors.newCachedThreadPool();
    private XtreamClient xtream; // current profile; null while closed
    private XtreamClient.Account account; // last successful authenticate() of xtream; null until then
    private String xtreamUsername = "";
    private String playerHeadersJson = "{}";
```

Trong `release()` (~dòng 1968) thêm `xtreamIo.shutdownNow();`.

- [ ] **Step 6: Viết lại `openPortal` và thêm luồng tải catalog**

Thay toàn bộ `openPortal(...)` (dòng ~674-781) bằng:

```java
    public void openPortal(String url, String snapshotJson, String userAgent) {
        ui.post(() -> {
            boolean preservingPlayback = player.getMediaItemCount() > 0;
            boolean reconnectingForChannel = preservingPlayback && portalChannelState.hasPendingChannel();
            closePortalOnUi(preservingPlayback || portalChannelState.hasPendingChannel()); // keep a queued channel
            portalChannelState.connecting();
            explicitPlaybackStopped = false;
            channelSessionId = "";
            channelRequestSent = false;
            suppressPortalStop = player.getMediaItemCount() > 0;
            if (reconnectingForChannel) {
                ui.removeCallbacks(channelSwitchTimeout);
                ui.postDelayed(channelSwitchTimeout, 30000);
            }
            final int generation = portalGeneration;
            portalOpenedAt = SystemClock.elapsedRealtime();
            Log.i(LOG_TAG, "Portal open generation=" + generation + (preservingPlayback ? " (stream kept)" : "")
                    + (reconnectingForChannel ? " (channel pending)" : ""));
            XtreamClient client;
            try {
                JSONObject snapshot = new JSONObject(snapshotJson);
                activeSessionId = snapshot.getString("sessionId");
                xtreamUsername = snapshot.getString("username");
                playerHeadersJson = new JSONObject().put("User-Agent", snapshot.getString("playerUserAgent")).toString();
                client = new XtreamClient(url, xtreamUsername, snapshot.getString("password"), userAgent);
            } catch (JSONException | IllegalArgumentException e) {
                onPortalUnavailable("CONFIGURATION_INVALID");
                return;
            }
            String favoritesKey = java.util.UUID.nameUUIDFromBytes(
                    (client.serverUrl + "\n" + xtreamUsername).getBytes(StandardCharsets.UTF_8)).toString();
            if (!favoritesKey.equals(accountConfigKey)) accountConfigKey = favoritesKey;
            channelPanel.setProfile(favoritesKey);
            tvScreen.setProfile(favoritesKey, favoritesKey);
            xtream = client;
            account = null;
            ui.removeCallbacks(portalLoadTimeout);
            ui.postDelayed(portalLoadTimeout, 180000);
            loadCatalog(client, generation, true);
        });
    }

    /** Background: authenticate (on connect) then categories + streams. Results apply only to the same generation. */
    private void loadCatalog(XtreamClient client, int generation, boolean authenticate) {
        if (authenticate) emit("portal.phase", activeSessionId + "|AUTHENTICATING");
        xtreamIo.execute(() -> {
            if (authenticate) {
                XtreamClient.Account info;
                try {
                    info = client.authenticate();
                } catch (XtreamClient.XtreamException e) {
                    failXtream(generation, e.code, false);
                    return;
                }
                ui.post(() -> {
                    if (generation != portalGeneration) return;
                    account = info;
                    emit("portal.phase", activeSessionId + "|LOADING");
                    // Reconnect fast path: the URL needs no channel list, only the account.
                    if (portalChannelState.hasPendingChannel()) sendChannelRequest(portalChannelState.pendingChannelId());
                });
            }
            try {
                JSONObject data = XtreamCatalog.channelsData(client.liveCategories(), client.liveStreams());
                ui.post(() -> { if (generation == portalGeneration) onCatalog(data); });
            } catch (XtreamClient.XtreamException e) {
                failXtream(generation, e.code, true);
            } catch (JSONException e) {
                failXtream(generation, "INVALID_RESPONSE", true);
            }
        });
    }

    private void failXtream(int generation, String code, boolean catalog) {
        ui.post(() -> {
            if (generation != portalGeneration) return;
            Log.w(LOG_TAG, (catalog ? "Catalog" : "Authentication") + " failed: " + code);
            if (!catalog) { onPortalUnavailable(code); return; }
            tvScreen.reloadFailed();
            emit("portal.catalog-error", activeSessionId + "|" + code);
        });
    }

    private void onCatalog(JSONObject payload) {
        try {
            XtreamCatalog.validate(payload); // validate the entire snapshot before mutating UI
        } catch (JSONException e) {
            tvScreen.reloadFailed();
            emit("portal.catalog-error", activeSessionId + "|INVALID_RESPONSE");
            return;
        }
        channelSessionId = activeSessionId;
        if (portalChannelState.portalState() != PortalChannelState.PortalState.READY)
            Log.i(LOG_TAG, "Portal ready (" + payload.optJSONArray("channels").length() + " channels) +" + sincePortalOpen() + " ms");
        portalChannelState.ready(channelSessionId);
        ui.removeCallbacks(portalLoadTimeout);
        updatePortalVisibility();
        channelPanel.setData(payload);
        tvScreen.setData(payload);
        channelButton.setVisibility(View.VISIBLE);
        emit("portal.ready", activeSessionId);
        if (!portalChannelState.hasPendingChannel()) suppressPortalStop = false;
        if (portalChannelState.canSendPending(channelSessionId)) sendChannelRequest(portalChannelState.pendingChannelId());
    }
```

Xoá `onChannelMessage(...)` và `validateCatalog(...)` (dòng ~976-1070). Phần việc của chúng nay nằm ở `onCatalog` và `XtreamCatalog.validate`.

- [ ] **Step 7: Viết lại `sendChannelRequest`, xoá fast-play**

Thay `sendChannelRequest` (dòng ~945-955) bằng:

```java
    /** Xtream links are deterministic: build the URL and play; no request round trip, so no switch timeout. */
    private void sendChannelRequest(String id) {
        if (xtream == null || account == null || channelRequestSent) return;
        Log.i(LOG_TAG, "Channel request " + id + " +" + sincePortalOpen() + " ms");
        channelRequestSent = true;
        switchingChannelId = id;
        ui.removeCallbacks(channelSwitchTimeout);
        play(xtream.streamUrl(id, account.ext), playerHeadersJson);
    }
```

Xoá `sendFastChannelRequest()`, các field `fastRequestSent`, `fastLinkArrived`, `channelCmds` và mọi dòng dùng chúng (kể cả trong `clearChannelSwitch`).

Trong `pickChannelAfterAds`, ở nhánh `else` (dòng ~1136-1140), thay bằng:

```java
        } else {
            tvScreen.setOpeningHint("Reconnecting to server…");
            if (portalChannelState.selectWhileUnavailable(id)) emit("ui.portal-reconnect", id);
            else sendChannelRequest(id); // server still connecting: plays as soon as the account is known
        }
```

- [ ] **Step 8: Chi tiết kênh, ⟳ và khôi phục stream**

Trong `TvScreen.Actions` (dòng ~381-419), thay `reconnectPortal()` và `requestDetails(...)`:

```java
            /** Live server: reload its channel list; otherwise reconnect, and the new session delivers the list. */
            @Override public void reconnectPortal() {
                if (xtream == null || portalChannelState.portalState() != PortalChannelState.PortalState.READY) { emit("ui.refresh", ""); return; }
                loadCatalog(xtream, portalGeneration, false);
            }
```

```java
            @Override public void requestDetails(String kind, String channelId, int requestId) {
                JSONObject payload;
                try {
                    payload = new JSONObject().put("kind", kind).put("id", channelId).put("requestId", requestId);
                } catch (JSONException e) { return; }
                XtreamClient client = xtream;
                XtreamClient.Account info = account;
                int generation = portalGeneration;
                try {
                    if (client == null || info == null) { tvScreen.setDetails(payload.put("error", true)); return; }
                    if ("info".equals(kind)) {
                        XtreamCatalog.putInfo(payload, info, xtreamUsername, java.util.TimeZone.getDefault());
                        tvScreen.setDetails(payload);
                        return;
                    }
                } catch (JSONException e) { return; }
                xtreamIo.execute(() -> {
                    try {
                        payload.put("programs", XtreamCatalog.programs(client.shortEpg(channelId), java.util.TimeZone.getDefault()));
                    } catch (XtreamClient.XtreamException | JSONException e) {
                        try { payload.put("error", true); } catch (JSONException ignored) { }
                    }
                    ui.post(() -> { if (generation == portalGeneration) tvScreen.setDetails(payload); });
                });
            }
```

Trong `resolveStreamLink()` (dòng ~1398-1402), đổi dòng cuối `sendChannelRequest(playingChannelId);` thành `verifyAccountThenReplay(playingChannelId);`, rồi thêm:

```java
    /** Stream refused: ask the server why before replaying the same URL (expired, too many connections, or still fine). */
    private void verifyAccountThenReplay(String id) {
        XtreamClient client = xtream;
        int generation = portalGeneration;
        xtreamIo.execute(() -> {
            try {
                XtreamClient.Account info = client.authenticate();
                ui.post(() -> {
                    if (generation != portalGeneration || !streamPortalRecovery) return;
                    account = info;
                    if (info.maxConnections > 0 && info.activeConnections >= info.maxConnections) {
                        failStream("MAX_CONNECTIONS");
                        return;
                    }
                    sendChannelRequest(id);
                });
            } catch (XtreamClient.XtreamException e) {
                ui.post(() -> {
                    if (generation != portalGeneration || !streamPortalRecovery) return;
                    if (e.code.startsWith("ACCOUNT_") || "AUTHENTICATION_REJECTED".equals(e.code)) failStream(e.code);
                    else reconnectPortalForStream("STREAM_LINK_REFUSED");
                });
            }
        });
    }
```

Trong `failStream`, đổi dòng `tvScreen.channelFailed("Playback stopped (" + code + ")...` thành `tvScreen.channelFailed(failureText(code), playingChannelId);`, rồi thêm:

```java
    private static String failureText(String code) {
        if ("MAX_CONNECTIONS".equals(code)) return "Too many connections on this account. Close another player and try again.";
        if (code.startsWith("ACCOUNT_EXPIRED")) return "The account has expired.";
        if ("ACCOUNT_DISABLED".equals(code)) return "The account is disabled.";
        if ("AUTHENTICATION_REJECTED".equals(code)) return "The server rejected the username or password.";
        return "Playback stopped (" + code + "). Select the channel to try again.";
    }
```

- [ ] **Step 9: Đơn giản hoá `onPortalUnavailable` / `closePortalOnUi`, xoá phần chỉ dành cho Stalker**

`onPortalUnavailable` (dòng ~872-922):
- Bỏ `portalReply = null;` và `webView.stopLoading();`.
- Thay khối toast bằng:

```java
            if (player.getMediaItemCount() == 0) {
                String text = "Server disconnected · reconnects when you pick a channel";
                boolean hint = "NETWORK".equals(reason) || reason.contains("TIMEOUT");
                Toast.makeText(activity, hint ? text + "\n" + DNS_HINT : text, hint ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            }
```

- Thay `retryable` và điều kiện liền sau bằng:

```java
            boolean retryable = "NETWORK".equals(reason) || "STREAM_LINK_REFUSED".equals(reason)
                    || reason.contains("TIMEOUT") || reason.startsWith("HTTP_5");
            if (pendingId != null && !retryable) {
                clearChannelSwitch();
                tvScreen.channelFailed("Server access failed - reconnect or check account settings", playingChannelId);
            }
```

`closePortalOnUi` (dòng ~828-870):
- Xoá các dòng liên quan tới `pendingViewportMessage`, `channelCmds`, `webView.*`, `facadeScript`, `WebViewCompat.removeWebMessageListener` và `portalReply`.
- Thêm `xtream = null; account = null;` ngay sau `activeSessionId = "";`.

Xoá hẳn các phần sau, cùng field và import của chúng:
- `BRIDGE_NAME`, `PORTAL_FULLSCREEN_JS`
- `webView`, `facadeJs`, `facadeScript`, `portalReply`, `pendingViewportMessage`
- `installPortalWebViewClient`, `publishToPortal`, `envPrefs`, `saveEnv`
- `onPortalAuthFailed` cùng `authFailures` / `authFailureWindowStart`
- `onViewportMessage`, `touchAsRemote`, `sendKeyOnUi`
- `playFromPortal`, `stopFromPortal`
- các import `android.webkit.*` và `androidx.webkit.*`

Dùng lệnh sau để tìm chỗ còn sót:
`grep -n "webView\|portalReply\|facade\|channelCmds\|fastRequest\|fastLink\|Viewport(Message\|pendingViewport\|sendKeyOnUi\|touchAsRemote\|BRIDGE_NAME\|env\(Prefs\|\.save\)\|AuthFailed\|authFailure" Native/StbHost/src/main/java/com/stbemu/host/StbHost.java`

Xử lý từng loại chỗ còn sót như sau:
- `webView.requestFocus()` (callback của `ChannelPanel`, dòng ~371-375) → `tvScreen.requestFocus()`.
- Trong `attach()`, dòng `stage.addView(webView, ...)` và đoạn tạo hay cấu hình `webView` → xoá. `stage` vẫn giữ, vì `videoView` nằm trong `tvScreen.playerBox` khi `nativeUi` là true.
- Trong `updatePortalVisibility()` / `setPortalVisible()`, các dòng đặt `webView.setVisibility(...)` → xoá, giữ phần cập nhật controls. Nếu `updatePortalVisibility()` trở thành rỗng thì giữ thân rỗng kèm comment `// native TV screen only; nothing to show`, vì nhiều chỗ còn gọi nó.
- Các key handler chuyển phím vào WebView → xoá nhánh WebView; phím Back trên màn TV phải để `TvScreen` tự xử lý (hiện đã gửi `ui.home`).
- Nếu đếm được vẫn còn `> 0` dòng khớp grep thì chưa xong step này.

Kiểm tra xem có log nào in URL stream (vì URL chứa password) không:
`grep -n "Log\.[iwde](.*\(playingUrl\|url\b\|stoppedUrl\)" Native/StbHost/src/main/java/com/stbemu/host/*.java`
Expected: không còn dòng nào. Nếu có thì bỏ URL khỏi log, chỉ log channel id.

Đổi các chữ hiển thị còn lại: `"Reconnecting to portal…"` → `"Reconnecting to server…"`, `"the portal is not responding"` → `"the server is not responding"`, `"the portal does not have this channel"` / `"the portal reported an error"` → xoá cùng nhánh `channels.error`, vì nhánh đó đã bị xoá ở Step 6. Kiểm bằng:
`grep -n "\"[^\"]*[Pp]ortal[^\"]*\"" Native/StbHost/src/main/java/com/stbemu/host/StbHost.java Native/StbHost/src/main/java/com/stbemu/host/TvScreen.java`
Chỉ được còn tên sự kiện (`portal.*`) và chuỗi log.

- [ ] **Step 10: Bỏ WebView khỏi build, xoá facade và test Node**

- `Native/StbHost/build.gradle`: xoá dòng `implementation 'androidx.webkit:webkit:1.17.1'`.
- `Assets/Plugins/Android/mainTemplate.gradle`: xoá dòng `implementation 'androidx.webkit:webkit:1.17.1'`.
- `git rm Native/StbHost/src/main/assets/stb_facade.js`
- `git rm -r Native/StbHost/test`

- [ ] **Step 11: Build và chạy toàn bộ test JVM**

Run: `cd Native/StbHost && ./gradlew testDebugUnitTest assembleRelease`
Expected: `BUILD SUCCESSFUL`. Toàn bộ test pass: `PortalChannelStateTest`, `ReloadPolicyTest`, `StreamRequestPolicyTest`, `ChannelFilterTest`, `XtreamClientTest`, `XtreamCatalogTest`, `TvScreenLogoTest`, …. `ProfileEditorDialogTest` vẫn pass vì Task 4 mới đổi nó.

- [ ] **Step 12: Commit**

```bash
git add Native/StbHost/src/main/java/com/stbemu/host/StbHost.java Native/StbHost/src/main/java/com/stbemu/host/TvScreen.java Native/StbHost/src/test/java/com/stbemu/host/TvScreenLogoTest.java Native/StbHost/build.gradle Assets/Plugins/Android/mainTemplate.gradle
git commit -m "feat(xtream): load live catalog and play via Xtream instead of the portal WebView"
```

(Các file `git rm` ở Step 10 đã được stage sẵn.)

---

### Task 4: Form profile và danh sách profile phía native

**Files:**
- Modify: `Native/StbHost/src/main/java/com/stbemu/host/ProfileEditorDialog.java`
- Modify: `Native/StbHost/src/main/java/com/stbemu/host/HomeScreen.java:95,272`
- Modify (thay nội dung): `Native/StbHost/src/test/java/com/stbemu/host/ProfileEditorDialogTest.java`

**Interfaces:**
- Produces: form JSON gửi qua `ui.profile.save` và nhận ở `showProfileEditor` có các khoá: `title`, `name`, `serverUrl`, `username`, `password`, `userAgent`, `playerUserAgent`.
- Danh sách profile ở `showProfileHome` có dạng `{id, name, host, username}`.
- Thêm `static String formValue(String key, String raw)`.

- [ ] **Step 1: Thay test (sẽ fail)**

```java
package com.stbemu.host;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Same rule as Assets/App/UI/ProfileForm.cs: every field is trimmed except the password. */
public class ProfileEditorDialogTest {
    @Test public void formValue_trimsFields() {
        assertEquals("http://h:8080", ProfileEditorDialog.formValue("serverUrl", "  http://h:8080 "));
        assertEquals("bob", ProfileEditorDialog.formValue("username", " bob "));
    }

    @Test public void formValue_keepsPasswordWhitespace() {
        assertEquals(" p w ", ProfileEditorDialog.formValue("password", " p w "));
    }
}
```

- [ ] **Step 2: Chạy test, xác nhận fail**

Run: `cd Native/StbHost && ./gradlew testDebugUnitTest --tests com.stbemu.host.ProfileEditorDialogTest`
Expected: lỗi compile `cannot find symbol: method formValue`.

- [ ] **Step 3: Sửa `ProfileEditorDialog`**

- Thay `KEYS` / `LABELS`:

```java
    private static final String[] KEYS = {"name", "serverUrl", "username", "password", "userAgent", "playerUserAgent"};
    private static final String[] LABELS = {"Name", "Server URL (http://host:port)", "Username", "Password",
            "User-Agent (empty = MAG250)", "Player User-Agent (empty = Lavf53.32.100)"};
```

- Javadoc của `show`: `/** {title, name, serverUrl, username, password, userAgent, playerUserAgent} */`.
- Bỏ field `sendDeviceId` và `forceLogin` (các `CheckBox`).
- Thay vòng lặp tạo field trong `show` (từ `for (int i = 0; i < KEYS.length; i++) {` tới hết `body.addView(forceLogin);`) bằng:

```java
        for (int i = 0; i < KEYS.length; i++) {
            fields[i] = field(form.optString(KEYS[i], ""), KEYS[i]);
            if (KEYS[i].equals("userAgent")) body.addView(label("Advanced"));
            body.addView(label(LABELS[i]));
            if (KEYS[i].equals("password")) {
                EditText password = fields[i];
                body.addView(withButton(password, "Show", v -> {
                    boolean hidden = (password.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
                    password.setInputType(InputType.TYPE_CLASS_TEXT | (hidden
                            ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                    ((Button) v).setText(hidden ? "Hide" : "Show");
                    password.setSelection(password.length());
                }));
            } else {
                body.addView(fields[i], new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
```

- `submit()`: thay phần dựng `form` bằng:

```java
            for (int i = 0; i < KEYS.length; i++) form.put(KEYS[i], formValue(KEYS[i], fields[i].getText().toString()));
```

- Thêm:

```java
    /** Passwords are sent exactly as typed; every other field is trimmed. */
    static String formValue(String key, String raw) {
        return "password".equals(key) ? raw : raw.trim();
    }
```

- Trong `field(...)`, thay khối chọn `setInputType` bằng:

```java
        if (key.equals("serverUrl")) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        else if (key.equals("name")) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        else if (key.equals("password")) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD); // no autocorrect
```

- Xoá `deviceIdFromMacField`, `generateMac`, `normalizeMac`, `deviceIdFromMac`, `i0`, `check(...)`, cùng các import không còn dùng (`CheckBox`, `MessageDigest`, `NoSuchAlgorithmException`, `SecureRandom`, `StandardCharsets`).

- [ ] **Step 4: Sửa `HomeScreen`**

- Dòng ~95 (javadoc): `profiles:[{id,name,host,username}]`.
- Dòng ~272: `((TextView) row.getChildAt(1)).setText(p.optString("host") + " · " + p.optString("username"));`

- [ ] **Step 5: Chạy test và build**

Run: `cd Native/StbHost && ./gradlew testDebugUnitTest assembleRelease`
Expected: `BUILD SUCCESSFUL`, `ProfileEditorDialogTest` có 2 test pass.

- [ ] **Step 6: Commit**

```bash
git add Native/StbHost/src/main/java/com/stbemu/host/ProfileEditorDialog.java Native/StbHost/src/main/java/com/stbemu/host/HomeScreen.java Native/StbHost/src/test/java/com/stbemu/host/ProfileEditorDialogTest.java
git commit -m "feat(xtream): server/username/password profile editor"
```

---

### Task 5: C# — profile v2 (URL/user/pass) và snapshot mới

Các file C# compile chung một assembly, nên task này phải đổi `Profile` cùng **mọi** chỗ dùng nó cho tới khi compile được.

**Files:**
- Modify: `Assets/App/Domain/Profiles/Profile.cs`
- Create: `Assets/App/Domain/Profiles/ServerAddress.cs`
- Delete: `Assets/App/Domain/Profiles/MacAddress.cs` (+ `.meta`)
- Modify: `Assets/App/Domain/Profiles/ProfileValidator.cs`
- Modify: `Assets/App/Domain/Profiles/ProfileManager.cs`
- Modify: `Assets/App/Infrastructure/Storage/JsonProfileRepository.cs`
- Modify: `Assets/App/Domain/Portal/PortalMessage.cs` (chỉ phần `PortalSession` → chuyển sang file mới)
- Create: `Assets/App/Domain/Portal/PortalSession.cs`
- Modify: `Assets/App/UI/ProfileForm.cs`
- Modify: `Assets/App/UI/ProfileEditScreen.cs`
- Modify: `Assets/App/Infrastructure/Android/AndroidProfileUi.cs:72`
- Modify: `Assets/App/UI/AppFlow.cs:369-383` (`TrackConnect`)
- Test: `Assets/App/Tests/EditMode/ProfileTests.cs`, `ProfileFormTests.cs`, `PortalMessageTests.cs` (bỏ các test về snapshot và force-login), `PortalSessionTests.cs` (mới)

**Interfaces:**
- Consumes: khoá form của Task 4; khoá snapshot trong Global Constraints.
- Produces:
  - `Profile { ProfileId, Name, ServerUrl, Username, Password, Compatibility { UserAgentOverride, PlayerUserAgentOverride }, Playback }`
  - `static class ServerAddress { static string Normalize(string) }`
  - `PortalSession(Profile)`, với `SessionId`, `PortalUrl`, `UserAgent`, `NextAttempt()`, `SnapshotJson()`
  - `PortalSession.DefaultApiUserAgent`, `PortalSession.DefaultPlayerUserAgent`

- [ ] **Step 1: Viết test (sẽ fail)**

`ProfileTests.cs`:
- Thay helper `NewProfile` và 5 test đầu (`MacNormalize`, `MacGenerate_IsCanonicalWithPrefix`, `DeviceIdFromMac_MatchesStbEmu`, `Add_GeneratesMac_AndSurvivesReload`, `Add_NormalizesEnteredMac_AndRejectsDuplicate`) bằng đoạn dưới.
- Trong `Add_RejectsInvalidUrl`, đổi `profile.PortalUrl = "portal.example.test";` thành `profile.ServerUrl = "ftp://example.test";`.
- Trong `Load_TypeNameInJson_IsIgnored`, đổi `\"schemaVersion\":1` thành `\"schemaVersion\":2`.
- Các test còn lại giữ nguyên.

```csharp
        static Profile NewProfile(string server = "http://xtream.example.test:8080", string user = "bob") => new Profile
        {
            Name = "Test server",
            ServerUrl = server,
            Username = user,
            Password = "secret",
        };

        [TestCase("  xtream.example.test:8080/ ", "http://xtream.example.test:8080")]
        [TestCase("http://h/player_api.php?username=a&password=b", "http://h")]
        [TestCase("https://h/xc/get.php?username=a&password=b&type=m3u", "https://h/xc")]
        [TestCase("http://h:80/PANEL_API.PHP", "http://h:80")]
        [TestCase("http://h:8080//", "http://h:8080")]
        [TestCase("", "")]
        [TestCase(null, "")]
        public void ServerAddress_Normalize(string input, string expected) => Assert.AreEqual(expected, ServerAddress.Normalize(input));

        [Test]
        public async Task Add_NormalizesUrl_AndSurvivesReload()
        {
            var manager = new ProfileManager(new JsonProfileRepository(dir));
            await manager.AddAsync(NewProfile("xtream.example.test:8080/"), CancellationToken.None);

            var reloaded = new ProfileManager(new JsonProfileRepository(dir));
            await reloaded.LoadAsync(CancellationToken.None);

            Assert.AreEqual(1, reloaded.Profiles.Count);
            Assert.AreEqual("http://xtream.example.test:8080", reloaded.Profiles[0].ServerUrl);
            Assert.AreEqual("bob", reloaded.Profiles[0].Username);
        }

        [Test]
        public async Task Add_KeepsPasswordExactly()
        {
            var manager = new ProfileManager(new JsonProfileRepository(dir));
            var profile = NewProfile();
            profile.Password = " p@ss/ w ";
            await manager.AddAsync(profile, CancellationToken.None);
            var reloaded = new ProfileManager(new JsonProfileRepository(dir));
            await reloaded.LoadAsync(CancellationToken.None);
            Assert.AreEqual(" p@ss/ w ", reloaded.Profiles[0].Password);
        }

        [Test]
        public async Task Add_RejectsSameAccountTypedDifferently()
        {
            var manager = new ProfileManager(new JsonProfileRepository(dir));
            await manager.AddAsync(NewProfile("http://xtream.example.test:8080"), CancellationToken.None);

            var ex = await Throws<ProfileValidationException>(
                manager.AddAsync(NewProfile("xtream.example.test:8080/"), CancellationToken.None).AsUniTask());
            StringAssert.Contains("already used", ex.Message);

            await manager.AddAsync(NewProfile("http://xtream.example.test:8080", "alice"), CancellationToken.None);
            Assert.AreEqual(2, manager.Profiles.Count);
        }

        [Test]
        public void Validate_RequiresUsernameAndPassword()
        {
            var errors = ProfileValidator.Validate(new Profile { Name = "n", ServerUrl = "http://h" }, Array.Empty<Profile>());
            Assert.That(errors, Has.Some.Contains("Username"));
            Assert.That(errors, Has.Some.Contains("Password"));
        }

        [Test]
        public async Task Load_OlderSchema_StartsEmpty()
        {
            File.WriteAllText(Path.Combine(dir, "profiles.json"),
                "{\"schemaVersion\":1,\"profiles\":[{\"name\":\"old\",\"portalUrl\":\"http://p/c/\"}]}");
            var manager = new ProfileManager(new JsonProfileRepository(dir));
            await manager.LoadAsync(CancellationToken.None);
            Assert.AreEqual(0, manager.Profiles.Count);
        }
```

`PortalSessionTests.cs` (mới):

```csharp
using Newtonsoft.Json.Linq;
using NUnit.Framework;
using StbEmu.Portal;
using StbEmu.Profiles;

namespace StbEmu.Tests
{
    public class PortalSessionTests
    {
        static Profile Account() => new Profile
        {
            ProfileId = "a", ServerUrl = "http://h:8080", Username = "bob", Password = "p\"</script>\u2028\u01B0",
        };

        [Test]
        public void Snapshot_HasXtreamKeys_AndIsAscii()
        {
            var session = new PortalSession(Account());
            var json = session.SnapshotJson();
            foreach (var c in json) Assert.Less(c, 128, "snapshot is ASCII-only");
            var parsed = JObject.Parse(json);
            Assert.AreEqual(session.SessionId, (string)parsed["sessionId"]);
            Assert.AreEqual("http://h:8080", (string)parsed["serverUrl"]);
            Assert.AreEqual("bob", (string)parsed["username"]);
            Assert.AreEqual("p\"</script>\u2028\u01B0", (string)parsed["password"]);
            Assert.AreEqual(PortalSession.DefaultApiUserAgent, (string)parsed["apiUserAgent"]);
            Assert.AreEqual(PortalSession.DefaultPlayerUserAgent, (string)parsed["playerUserAgent"]);
            Assert.AreEqual("http://h:8080", session.PortalUrl);
            Assert.AreEqual(PortalSession.DefaultApiUserAgent, session.UserAgent);
        }

        [Test]
        public void DefaultUserAgents()
        {
            StringAssert.Contains("MAG250", PortalSession.DefaultApiUserAgent);
            Assert.AreEqual("Lavf53.32.100", PortalSession.DefaultPlayerUserAgent);
        }

        [Test]
        public void Overrides_AreTrimmed_BlankUsesDefault()
        {
            var profile = Account();
            profile.Compatibility.UserAgentOverride = "  MyUA ";
            profile.Compatibility.PlayerUserAgentOverride = "   ";
            var parsed = JObject.Parse(new PortalSession(profile).SnapshotJson());
            Assert.AreEqual("MyUA", (string)parsed["apiUserAgent"]);
            Assert.AreEqual(PortalSession.DefaultPlayerUserAgent, (string)parsed["playerUserAgent"]);
        }

        [Test]
        public void Retry_KeepsConfiguration_AndOperation()
        {
            var profile = Account();
            var session = new PortalSession(profile);
            profile.Password = "changed";
            var first = JObject.Parse(session.SnapshotJson());
            var retry = JObject.Parse(session.NextAttempt().SnapshotJson());
            Assert.AreEqual((string)first["password"], (string)retry["password"]);
            Assert.AreEqual((string)first["operationId"], (string)retry["operationId"]);
            Assert.AreNotEqual((string)first["sessionId"], (string)retry["sessionId"]);
        }
    }
}
```

`PortalMessageTests.cs`: xoá `Snapshot_EscapesAndHidesDeviceIdUnlessSent`, `RetryKeepsImmutableConfigurationAndCacheOperation` và `ForceLoginMigratesLegacyValues`; xoá luôn `using StbEmu.Profiles;` nếu không còn dùng.

`ProfileFormTests.cs`: thay toàn bộ nội dung class bằng:

```csharp
        static Profile Stored() => new Profile
        {
            ProfileId = "p1",
            Name = "Nhà \"A\"",
            ServerUrl = "http://h:8080",
            Username = "bob",
            Password = "secret",
            Compatibility = { UserAgentOverride = "UA", PlayerUserAgentOverride = "PUA" },
            Playback = { MinBufferMs = 5000, Tunneling = true },
        };

        [Test]
        public void Apply_WritesFormFields_KeepsTheRest()
        {
            var profile = Stored();
            ProfileForm.Apply(profile, new JObject
            {
                ["name"] = "  Đổi tên ", ["serverUrl"] = " x.test:8080 ", ["username"] = " alice ", ["password"] = " p w ",
                ["userAgent"] = " UA2 ", ["playerUserAgent"] = "",
            });
            Assert.AreEqual("Đổi tên", profile.Name);
            Assert.AreEqual("x.test:8080", profile.ServerUrl); // normalization is the manager's job
            Assert.AreEqual("alice", profile.Username);
            Assert.AreEqual(" p w ", profile.Password);
            Assert.AreEqual("UA2", profile.Compatibility.UserAgentOverride);
            Assert.AreEqual("", profile.Compatibility.PlayerUserAgentOverride);
            Assert.AreEqual("p1", profile.ProfileId);
            Assert.AreEqual(5000, profile.Playback.MinBufferMs);
            Assert.IsTrue(profile.Playback.Tunneling);
        }

        [Test]
        public void Apply_MissingOrMistypedKeys_DoNotOverwrite()
        {
            var profile = Stored();
            ProfileForm.Apply(profile, new JObject { ["name"] = 5, ["password"] = true });
            Assert.AreEqual("Nhà \"A\"", profile.Name);
            Assert.AreEqual("secret", profile.Password);
        }

        [Test]
        public void ToForm_Apply_RoundTrips()
        {
            var source = Stored();
            var copy = ProfileForm.Copy(source);
            ProfileForm.Apply(copy, JObject.Parse(ProfileForm.ToForm(source, "Edit profile").ToString()));
            Assert.AreEqual(source.ServerUrl, copy.ServerUrl);
            Assert.AreEqual(source.Username, copy.Username);
            Assert.AreEqual(source.Password, copy.Password);
            Assert.AreEqual(source.Compatibility.UserAgentOverride, copy.Compatibility.UserAgentOverride);
            Assert.AreEqual(source.Compatibility.PlayerUserAgentOverride, copy.Compatibility.PlayerUserAgentOverride);
        }

        [Test]
        public void Copy_IsDeep_AndNullIsNewProfile()
        {
            var source = Stored();
            var copy = ProfileForm.Copy(source);
            copy.Username = "changed";
            Assert.AreEqual("bob", source.Username);
            var fresh = ProfileForm.Copy(null);
            Assert.AreEqual("", fresh.ProfileId);
            Assert.AreEqual("", fresh.Password);
        }
```

- [ ] **Step 2: Chạy test, xác nhận fail**

Run: lệnh Unity EditMode (Global Constraints).
Expected: compile error, ví dụ `'Profile' does not contain a definition for 'ServerUrl'`.

- [ ] **Step 3: Viết code domain**

`Assets/App/Domain/Profiles/Profile.cs` (thay toàn bộ):

```csharp
using System.Collections.Generic;

namespace StbEmu.Profiles
{
    /// <summary>Root of profiles.json.</summary>
    public sealed class ProfileDocument
    {
        public const int CurrentSchemaVersion = 2;

        public int SchemaVersion = CurrentSchemaVersion;
        public List<Profile> Profiles = new List<Profile>();
    }

    /// <summary>One Xtream Codes account.</summary>
    public sealed class Profile
    {
        public string ProfileId = "";
        public string Name = "";
        /// <summary>Server base without the API script, e.g. http://host:8080 — see <see cref="ServerAddress.Normalize"/>.</summary>
        public string ServerUrl = "";
        public string Username = "";
        /// <summary>Stored as typed (never trimmed); never logged.</summary>
        public string Password = "";
        public CompatibilitySettings Compatibility = new CompatibilitySettings();
        public PlaybackSettings Playback = new PlaybackSettings();
    }

    public sealed class CompatibilitySettings
    {
        /// <summary>player_api.php request UA. Empty uses the MAG250 default.</summary>
        public string UserAgentOverride = "";
        /// <summary>Stream request UA, independent of the API UA. Empty uses the SFVIP-compatible default.</summary>
        public string PlayerUserAgentOverride = "";
    }

    public sealed class PlaybackSettings
    {
        public int MinBufferMs = 2000;
        public bool Tunneling;
    }
}
```

`Assets/App/Domain/Profiles/ServerAddress.cs`:

```csharp
using System;

namespace StbEmu.Profiles
{
    /// <summary>Canonical Xtream server base: scheme added if missing, API script/query and trailing slashes removed.</summary>
    public static class ServerAddress
    {
        static readonly string[] ApiScripts = { "/player_api.php", "/get.php", "/panel_api.php" };

        public static string Normalize(string input)
        {
            var url = (input ?? "").Trim();
            if (url.Length == 0) return "";
            if (!url.Contains("://")) url = "http://" + url;
            foreach (var script in ApiScripts)
            {
                int at = url.IndexOf(script, StringComparison.OrdinalIgnoreCase);
                if (at > 0)
                {
                    url = url.Substring(0, at);
                    break;
                }
            }
            return url.TrimEnd('/');
        }
    }
}
```

`ProfileValidator.Validate` (thay thân hàm):

```csharp
            var errors = new List<string>();

            if (string.IsNullOrWhiteSpace(profile.Name))
                errors.Add("Name is required.");

            if (!Uri.TryCreate(profile.ServerUrl, UriKind.Absolute, out var uri)
                || (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps)
                || string.IsNullOrEmpty(uri.Host))
                errors.Add("Server URL must be a full http(s) URL, e.g. http://example.test:8080.");

            if (string.IsNullOrWhiteSpace(profile.Username))
                errors.Add("Username is required.");

            if (string.IsNullOrEmpty(profile.Password))
                errors.Add("Password is required.");

            foreach (var other in others)
                if (string.Equals(other.ServerUrl, profile.ServerUrl, StringComparison.OrdinalIgnoreCase)
                    && other.Username == profile.Username)
                    errors.Add($"This account is already used by profile \"{other.Name}\".");

            return errors;
```

`ProfileManager`:
- Trong `AddAsync`, thay hai dòng gán `profile.Identity.Mac = …` bằng `profile.ServerUrl = ServerAddress.Normalize(profile.ServerUrl);`, và sửa doc thành `/// <summary>Assigns ProfileId; normalizes the server URL.</summary>`.
- Trong `UpdateAsync`, thay dòng `profile.Identity.Mac = MacAddress.Normalize…` bằng `profile.ServerUrl = ServerAddress.Normalize(profile.ServerUrl);`, và sửa doc thành `/// <summary>Replaces the stored profile with the same ProfileId.</summary>`.
- Xoá `GenerateUnusedMac()`.

`JsonProfileRepository.ReadAsync`: ngay sau khối kiểm `SchemaVersion > CurrentSchemaVersion`, thêm:

```csharp
            // Schema 1 was the Stalker/MAC app; this app has no users of it to migrate.
            if (document.SchemaVersion < ProfileDocument.CurrentSchemaVersion) return new ProfileDocument();
```

Xoá `MacAddress.cs` và `MacAddress.cs.meta`: `git rm Assets/App/Domain/Profiles/MacAddress.cs Assets/App/Domain/Profiles/MacAddress.cs.meta`.

- [ ] **Step 4: `PortalSession` sang file riêng**

Xoá class `PortalSession` khỏi `PortalMessage.cs`, cùng `using StbEmu.Profiles;` nếu không còn dùng. Tạo `Assets/App/Domain/Portal/PortalSession.cs`:

```csharp
using System;
using Newtonsoft.Json;
using StbEmu.Profiles;

namespace StbEmu.Portal
{
    /// <summary>One connect attempt. A new SessionId per attempt makes every event from an older one invalid.</summary>
    public sealed class PortalSession
    {
        /// <summary>player_api.php UA unless the profile overrides it.</summary>
        public const string DefaultApiUserAgent = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250 stbapp ver: 2 rev: 250 Safari/533.3";
        /// <summary>Verified default PlayerUserAgent in SFVIP 1.2.5.7.</summary>
        public const string DefaultPlayerUserAgent = "Lavf53.32.100";

        public readonly string SessionId;
        public readonly string PortalUrl;
        public readonly string UserAgent;
        readonly string operationId;
        readonly string username;
        readonly string password;
        readonly string playerUserAgent;

        public PortalSession(Profile profile)
        {
            SessionId = Guid.NewGuid().ToString("N");
            operationId = SessionId;
            PortalUrl = profile.ServerUrl;
            username = profile.Username;
            password = profile.Password;
            UserAgent = Pick(profile.Compatibility?.UserAgentOverride, DefaultApiUserAgent);
            playerUserAgent = Pick(profile.Compatibility?.PlayerUserAgentOverride, DefaultPlayerUserAgent);
        }

        PortalSession(PortalSession source)
        {
            SessionId = Guid.NewGuid().ToString("N");
            operationId = source.operationId;
            PortalUrl = source.PortalUrl;
            UserAgent = source.UserAgent;
            username = source.username;
            password = source.password;
            playerUserAgent = source.playerUserAgent;
        }

        public PortalSession NextAttempt() => new PortalSession(this);

        /// <summary>Native openPortal input. ASCII-escaped (incl. U+2028/2029).</summary>
        public string SnapshotJson() => JsonConvert.SerializeObject(new
        {
            sessionId = SessionId,
            operationId,
            serverUrl = PortalUrl,
            username,
            password,
            apiUserAgent = UserAgent,
            playerUserAgent,
        }, new JsonSerializerSettings { StringEscapeHandling = StringEscapeHandling.EscapeNonAscii });

        static string Pick(string value, string fallback) => string.IsNullOrWhiteSpace(value) ? fallback : value.Trim();
    }
}
```

Tạo file `.meta` bằng cách để Unity import (mở Editor, hoặc chạy lệnh test batchmode). Phải commit kèm file `.meta`.

- [ ] **Step 5: Sửa UI và các chỗ còn dùng `Identity`**

`ProfileForm.cs`: sửa doc class ("…so settings the form does not show (Playback, future fields) survive an edit…"), rồi thay `ToForm`/`Apply`/helper:

```csharp
        /// <summary>Form payload for the native editor; keys match <see cref="Apply"/>.</summary>
        public static JObject ToForm(Profile profile, string title) => new JObject
        {
            ["title"] = title,
            ["name"] = profile.Name,
            ["serverUrl"] = profile.ServerUrl,
            ["username"] = profile.Username,
            ["password"] = profile.Password,
            ["userAgent"] = profile.Compatibility.UserAgentOverride,
            ["playerUserAgent"] = profile.Compatibility.PlayerUserAgentOverride,
        };

        /// <summary>Writes the keys present in <paramref name="form"/> (trimmed, except the password); missing or mistyped keys keep the current value.</summary>
        public static void Apply(Profile target, JObject form)
        {
            target.Name = Text(form, "name", target.Name);
            target.ServerUrl = Text(form, "serverUrl", target.ServerUrl);
            target.Username = Text(form, "username", target.Username);
            target.Password = Raw(form, "password", target.Password);
            target.Compatibility.UserAgentOverride = Text(form, "userAgent", target.Compatibility.UserAgentOverride);
            target.Compatibility.PlayerUserAgentOverride = Text(form, "playerUserAgent", target.Compatibility.PlayerUserAgentOverride);
        }

        static string Text(JObject form, string key, string current) => Raw(form, key, current)?.Trim();

        static string Raw(JObject form, string key, string current) =>
            form.TryGetValue(key, out var value) && value.Type == JTokenType.String ? (string)value : current;
```

Bỏ helper `Flag` vì không còn dùng.

`AndroidProfileUi.cs` dòng ~72: `["id"] = p.ProfileId, ["name"] = p.Name, ["host"] = Host(p.ServerUrl), ["username"] = p.Username,`

`ProfileEditScreen.cs` (form uGUI chỉ dùng trong Editor). Dùng lại các InputField sẵn có trong `AppCanvas.prefab`, không sửa prefab:
- `PortalUrl` → Server URL
- `Mac` → Username
- `Serial` → Password
- `Model` → User-Agent
- `DeviceId` → Player User-Agent

Ẩn đi: `DeviceId2`, `Signature`, `RandomMac`, `DeviceIdFromMac`, `SendDeviceId`. Thay toàn bộ class bằng:

```csharp
    /// <summary>Edits a copy of a profile; the caller saves it through ProfileManager and reports errors back.</summary>
    public sealed class ProfileEditScreen
    {
        public event Action<Profile> Saved;
        public event Action Cancelled;

        readonly RectTransform root;
        readonly Text title;
        readonly InputField name, url, username, password, userAgent, playerUserAgent;
        readonly Text errors;
        readonly ScrollRect scroll;
        Profile editing;

        public ProfileEditScreen(Transform canvas)
        {
            root = UiKit.Require<RectTransform>(canvas, "EditProfile");
            title = UiKit.Require<Text>(root, "Title");
            scroll = UiKit.Require<ScrollRect>(root, "Scroll");
            var form = scroll.content;
            name = UiKit.Require<InputField>(form, "Name");
            // The prefab still carries the portal-era field names; they are relabelled instead of rebuilt.
            url = Relabel(UiKit.Require<InputField>(form, "PortalUrl"), "Server URL (http://host:port)");
            username = Relabel(UiKit.Require<InputField>(form, "Mac"), "Username");
            password = Relabel(UiKit.Require<InputField>(form, "Serial"), "Password");
            password.contentType = InputField.ContentType.Password;
            userAgent = Relabel(UiKit.Require<InputField>(form, "Model"), "User-Agent (empty = MAG250)");
            playerUserAgent = Relabel(UiKit.Require<InputField>(form, "DeviceId"), "Player User-Agent (empty = Lavf53.32.100)");
            Hide(UiKit.Require<InputField>(form, "DeviceId2"));
            Hide(UiKit.Require<InputField>(form, "Signature"));
            Hide(UiKit.Require<Button>(form, "RandomMac"));
            Hide(UiKit.Require<Button>(form, "DeviceIdFromMac"));
            Hide(UiKit.Require<Button>(form, "SendDeviceId"));
            errors = UiKit.Require<Text>(form, "Errors");
            var actions = UiKit.Require<ProfileActionLayout>(root, "FixedActions");
            var cancel = UiKit.Require<Button>(actions.transform, "Cancel");
            var save = UiKit.Require<Button>(actions.transform, "Save");
            cancel.onClick.AddListener(() => Cancelled?.Invoke());
            save.onClick.AddListener(Save);
            actions.Initialize(root, cancel, save);
            Hide();
        }

        public void Show(Profile source)
        {
            editing = ProfileForm.Copy(source);
            title.text = source == null ? "Add profile" : "Edit profile";
            name.text = editing.Name;
            url.text = editing.ServerUrl;
            username.text = editing.Username;
            password.text = editing.Password;
            userAgent.text = editing.Compatibility.UserAgentOverride;
            playerUserAgent.text = editing.Compatibility.PlayerUserAgentOverride;
            errors.text = "";
            root.gameObject.SetActive(true);
            Canvas.ForceUpdateCanvases();
            scroll.StopMovement();
            scroll.verticalNormalizedPosition = 1;
        }

        public void Hide() => root.gameObject.SetActive(false);

        public void ShowErrors(IEnumerable<string> messages)
        {
            errors.text = string.Join("\n", messages);
            Canvas.ForceUpdateCanvases();
            scroll.StopMovement();
            scroll.verticalNormalizedPosition = 0;
        }

        void Save()
        {
            editing.Name = name.text.Trim();
            editing.ServerUrl = url.text.Trim();
            editing.Username = username.text.Trim();
            editing.Password = password.text;
            editing.Compatibility.UserAgentOverride = userAgent.text.Trim();
            editing.Compatibility.PlayerUserAgentOverride = playerUserAgent.text.Trim();
            Saved?.Invoke(editing);
        }

        /// <summary>Sets the placeholder and the caption Text right above the field, if any.</summary>
        static InputField Relabel(InputField field, string text)
        {
            if (field.placeholder is Text placeholder) placeholder.text = text;
            var caption = Caption(field);
            if (caption != null) caption.text = text;
            return field;
        }

        static void Hide(Component field)
        {
            var caption = Caption(field);
            if (caption != null) caption.gameObject.SetActive(false);
            field.gameObject.SetActive(false);
        }

        static Text Caption(Component field)
        {
            int index = field.transform.GetSiblingIndex();
            return index > 0 && field.transform.parent.GetChild(index - 1).TryGetComponent<Text>(out var label)
                && label.GetComponent<Selectable>() == null ? label : null;
        }
    }
```

Đổi `using`: bỏ `Newtonsoft.Json` nếu không còn dùng.

`AppFlow.TrackConnect` (dòng ~369-383), thay thân hàm bằng:

```csharp
            string url = profile?.ServerUrl ?? "";
            string host = Uri.TryCreate(url, UriKind.Absolute, out var uri) ? uri.Host : url;
            FirebaseController.Instance?.LogEvent("portal_connect", // host only: never username or password
                new Parameter("host", Clip(host)),
                new Parameter("status", error == null ? "success" : "fail"),
                new Parameter("error", Clip(error)));
```

Kiểm tra không còn chỗ nào dùng tên cũ:
`grep -rn "Identity\|MacAddress\|ForceLoginPortal\|PortalLanguage\|\.PortalUrl\b" Assets/App --include=*.cs`
Expected: chỉ còn `PortalSession.PortalUrl` (trong `PortalSession.cs` và `AndroidPortalView.cs`) và test `PortalSessionTests`.

- [ ] **Step 6: Chạy test, xác nhận pass**

Run: lệnh Unity EditMode.
Expected: không có lỗi compile; `ProfileTests`, `ProfileFormTests`, `PortalSessionTests`, `PortalMessageTests` (phần còn lại) và mọi test khác pass.

- [ ] **Step 7: Kiểm tra form trong Editor**

Mở scene Main trong Editor rồi Play → Add profile. Kiểm tra:
- 6 ô có nhãn đúng (Name, Server URL, Username, Password, User-Agent, Player User-Agent).
- Không còn ô hay nút MAC/Device ID nào hiện ra.
- Save với `xtream.example.test:8080/`: danh sách hiện profile, và khi Edit lại thì URL đã là `http://xtream.example.test:8080`.

Nếu nhãn phía trên ô vẫn là chữ cũ (do prefab đặt nhãn ở chỗ khác), sửa text nhãn trực tiếp trong `AppCanvas.prefab` cho đúng 6 ô này và commit kèm.

- [ ] **Step 8: Commit**

```bash
git add Assets/App/Domain/Profiles Assets/App/Domain/Portal/PortalSession.cs Assets/App/Domain/Portal/PortalSession.cs.meta Assets/App/Domain/Portal/PortalMessage.cs Assets/App/Infrastructure/Storage/JsonProfileRepository.cs Assets/App/UI/ProfileForm.cs Assets/App/UI/ProfileEditScreen.cs Assets/App/Infrastructure/Android/AndroidProfileUi.cs Assets/App/UI/AppFlow.cs Assets/App/Tests/EditMode/ProfileTests.cs Assets/App/Tests/EditMode/ProfileFormTests.cs Assets/App/Tests/EditMode/PortalMessageTests.cs Assets/App/Tests/EditMode/PortalSessionTests.cs Assets/App/Tests/EditMode/PortalSessionTests.cs.meta
git commit -m "feat(xtream): profiles are server URL + username + password (schema v2)"
```

---

### Task 6: C# — bỏ lớp lệnh player của facade

Native nay tự phát kênh, nên `PortalMessage` / `PortalEvent` / `PortalController` / `PlayCommand` / `IPlayer` / `AndroidPlayer` không còn ai dùng.

**Files:**
- Delete (+ `.meta`):
  - `Assets/App/Domain/Portal/PortalMessage.cs`, `PortalController.cs`, `PlayCommand.cs`, `IPlayer.cs`
  - `Assets/App/Infrastructure/Android/AndroidPlayer.cs`
  - `Assets/App/Tests/EditMode/PortalMessageTests.cs`, `PortalControllerTests.cs`, `PlayCommandTests.cs`
- Modify: `Assets/App/Domain/Portal/IPortalView.cs`
- Modify: `Assets/App/Infrastructure/Android/AndroidPortalView.cs`
- Modify: `Assets/App/Infrastructure/Android/AndroidStbHost.cs`
- Modify: `Assets/App/UI/AppFlow.cs`

**Interfaces:**
- Consumes: Task 3 đã xoá `publishToPortal`, `playFromPortal` và `stopFromPortal` ở phía Java.
- Produces: `IPortalView { event Action<string> Error; UniTask OpenAsync(PortalSession, CancellationToken); UniTask CloseAsync(CancellationToken); }`.

- [ ] **Step 1: Xoá file**

```bash
git rm Assets/App/Domain/Portal/PortalMessage.cs Assets/App/Domain/Portal/PortalMessage.cs.meta \
  Assets/App/Domain/Portal/PortalController.cs Assets/App/Domain/Portal/PortalController.cs.meta \
  Assets/App/Domain/Portal/PlayCommand.cs Assets/App/Domain/Portal/PlayCommand.cs.meta \
  Assets/App/Domain/Portal/IPlayer.cs Assets/App/Domain/Portal/IPlayer.cs.meta \
  Assets/App/Infrastructure/Android/AndroidPlayer.cs Assets/App/Infrastructure/Android/AndroidPlayer.cs.meta \
  Assets/App/Tests/EditMode/PortalMessageTests.cs Assets/App/Tests/EditMode/PortalMessageTests.cs.meta \
  Assets/App/Tests/EditMode/PortalControllerTests.cs Assets/App/Tests/EditMode/PortalControllerTests.cs.meta \
  Assets/App/Tests/EditMode/PlayCommandTests.cs Assets/App/Tests/EditMode/PlayCommandTests.cs.meta
```

- [ ] **Step 2: Sửa `IPortalView`**

Xoá `event Action<PortalMessage> MessageReceived;` và `UniTask PublishAsync(PortalEvent message, CancellationToken ct);`. Đổi doc của `PortalLoadException.Reason` thành `/// <summary>Raw native code, e.g. "NETWORK", "AUTHENTICATION_REJECTED", "CATALOG_TIMEOUT".</summary>`.

- [ ] **Step 3: Sửa `AndroidPortalView`**

- Xoá `public event Action<PortalMessage> MessageReceived;` và toàn bộ method `PublishAsync`.
- Trong `OnNativeEvent`, xoá `case "portal.message": … break;`.
- Xoá `using UnityEngine;` nếu không còn dùng (trước đây chỉ dùng cho `Debug.LogWarning` của message bị loại).

- [ ] **Step 4: Sửa `AndroidStbHost`**

- Xoá `PublishToPortal`, `PlayFromPortal` và `StopFromPortal`.
- Doc của `NativeEvent`: `/// <summary>(type, data) from native, e.g. ("portal.ready", sid), ("portal.error", "sid|NETWORK"), ("state", "3").</summary>`.
- Doc của class: `/// C# handle to the native com.stbemu.host.StbHost (Xtream client + ExoPlayer). One per app.`

- [ ] **Step 5: Sửa `AppFlow`**

- Xoá các field `Android.AndroidPlayer player;` và `PortalController controller;` (khai báo khoảng dòng 40-42).
- Xoá `player = new Android.AndroidPlayer(host);` (~121) và khối `portal.MessageReceived += m => { … };` (~130-133).
- Xoá `controller?.Dispose();` trong `OnDestroy` (~211) và hai dòng `controller?.Dispose(); controller = new PortalController(...)` trong `ConnectAsync` (~308-309).
- Trong `PortalPhase`, đổi label (~126): `phase == "AUTHENTICATING" ? "Signing in" : phase.StartsWith("Retrying ") ? phase : "Loading channels"`.
- Ở `#else` của `ConnectAsync` (~364): `ShowError("Xtream playback only runs on an Android device.");`.
- Comment ~68 (`// The portal WebView is transparent…`) và ~181 (`// Android Back outside the portal (in the portal the WebView has focus…`): đổi thành `// Black around the TV layout, not the skybox.` và `// Android Back outside the TV screen (the native TV screen handles its own Back).`.
- Kiểm tra: `grep -rn "PortalMessage\|PortalEvent\|PortalController\|PlayCommand\|IPlayer\|AndroidPlayer\|PlayFromPortal\|StopFromPortal\|PublishToPortal" Assets/App --include=*.cs`. Expected: không còn dòng nào.

Lưu ý: `playerIsPlaying` của `AppFlow` được cập nhật từ native event (`isPlaying`) trong `OnNativeEvent`, không qua `AndroidPlayer`. Kiểm bằng `grep -n "playerIsPlaying" Assets/App/UI/AppFlow.cs`. Nếu nó chỉ được gán trong handler của `player.StateChanged` thì chuyển phép gán đó vào `OnNativeEvent`:
`if (type == "isPlaying") playerIsPlaying = data == "true";`

- [ ] **Step 6: Chạy test**

Run: lệnh Unity EditMode.
Expected: không có lỗi compile; mọi test còn lại pass.

- [ ] **Step 7: Commit**

```bash
git add Assets/App/Domain/Portal/IPortalView.cs Assets/App/Infrastructure/Android/AndroidPortalView.cs Assets/App/Infrastructure/Android/AndroidStbHost.cs Assets/App/UI/AppFlow.cs
git commit -m "refactor(xtream): drop the portal facade's player command layer"
```

---

### Task 7: C# — mã lỗi Xtream: retry và thông báo

**Files:**
- Modify: `Assets/App/Domain/Portal/PortalFailurePolicy.cs`
- Modify: `Assets/App/UI/ErrorText.cs`
- Test: `Assets/App/Tests/EditMode/PortalFailurePolicyTests.cs`, `Assets/App/Tests/EditMode/ErrorTextTests.cs` (thay nội dung)

**Interfaces:**
- Consumes: các mã lỗi trong Global Constraints; C# nhận lỗi catalog dạng `CATALOG_<mã>` (do `AndroidPortalView` thêm tiền tố).
- Produces: `PortalFailurePolicy.TryGetProfileRetryDelay(code, retries, out delay)`, `PortalFailurePolicy.CanRetry(code)`, `ErrorText.For(code)`. Tên hàm không đổi.

- [ ] **Step 1: Viết test (sẽ fail)**

`PortalFailurePolicyTests.cs` (thay thân class):

```csharp
        [TestCase("NETWORK")]
        [TestCase("TIMEOUT")]
        [TestCase("HTTP_503")]
        [TestCase("CATALOG_NETWORK")]
        [TestCase("CATALOG_TIMEOUT")]
        [TestCase("CATALOG_HTTP_502")]
        public void TransientErrors_RetryWithBackoff(string code)
        {
            for (int retry = 0; retry < 3; retry++)
            {
                Assert.IsTrue(PortalFailurePolicy.TryGetProfileRetryDelay(code, retry, out var delay), code);
                Assert.AreEqual(1 << retry, delay.TotalSeconds);
            }
            Assert.IsFalse(PortalFailurePolicy.TryGetProfileRetryDelay(code, 3, out _));
        }

        [TestCase("AUTHENTICATION_REJECTED")]
        [TestCase("ACCOUNT_EXPIRED")]
        [TestCase("ACCOUNT_EXPIRED:2026-01-01")]
        [TestCase("ACCOUNT_DISABLED")]
        [TestCase("FORBIDDEN")]
        [TestCase("INVALID_RESPONSE")]
        [TestCase("CATALOG_INVALID_RESPONSE")]
        [TestCase("CONFIGURATION_INVALID")]
        [TestCase("HTTP_404")]
        [TestCase(null)]
        public void AccountAndConfigErrors_NeverRetryOnConnect(string code)
            => Assert.IsFalse(PortalFailurePolicy.TryGetProfileRetryDelay(code, 0, out _));

        [TestCase("NETWORK", true)]
        [TestCase("TIMEOUT", true)]
        [TestCase("RECONNECT_TIMEOUT", true)]
        [TestCase("PORTAL_READY_TIMEOUT", true)]
        [TestCase("HTTP_503", true)]
        [TestCase("STREAM_LINK_REFUSED", true)]
        [TestCase("AUTHENTICATION_REJECTED", false)]
        [TestCase("ACCOUNT_EXPIRED:2026-01-01", false)]
        [TestCase("ACCOUNT_DISABLED", false)]
        [TestCase("FORBIDDEN", false)]
        [TestCase("INVALID_RESPONSE", false)]
        [TestCase("HTTP_404", false)]
        [TestCase(null, false)]
        public void ReconnectOnlyTransientFailures(string code, bool expected)
            => Assert.AreEqual(expected, PortalFailurePolicy.CanRetry(code));
```

`ErrorTextTests.cs` (thay thân class):

```csharp
        [TestCase("NETWORK", "Cannot reach the server")]
        [TestCase(ErrorText.Timeout, "not responding")]
        [TestCase("PORTAL_READY_TIMEOUT", "not responding")]
        [TestCase("HTTP_503", "server error")]
        [TestCase("FORBIDDEN", "denied")]
        [TestCase("AUTHENTICATION_REJECTED", "username or password")]
        [TestCase("ACCOUNT_EXPIRED", "expired")]
        [TestCase("ACCOUNT_EXPIRED:2026-01-01", "2026-01-01")]
        [TestCase("ACCOUNT_DISABLED", "disabled")]
        [TestCase("INVALID_RESPONSE", "Xtream")]
        [TestCase("CONFIGURATION_INVALID", "account settings")]
        [TestCase("MAX_CONNECTIONS", "connections")]
        [TestCase("CATALOG_TIMEOUT", "channel list")]
        public void KnownCodes(string code, string expectedFragment) => StringAssert.Contains(expectedFragment, ErrorText.For(code));

        [TestCase("NETWORK")]
        [TestCase(ErrorText.Timeout)]
        [TestCase("RECONNECT_TIMEOUT")]
        public void NetworkFailures_SuggestCloudflareDns(string code) => StringAssert.Contains("1.1.1.1", ErrorText.For(code));

        [Test]
        public void AccountFailures_NoDnsHint() => StringAssert.DoesNotContain("1.1.1.1", ErrorText.For("AUTHENTICATION_REJECTED"));

        [TestCase("SOMETHING_NEW")]
        public void UnknownCodes_ShowRawCode(string code) => StringAssert.Contains(code, ErrorText.For(code));

        [Test]
        public void NullOrEmpty_IsGeneric() => Assert.IsNotEmpty(ErrorText.For(null));
```

- [ ] **Step 2: Chạy test, xác nhận fail**

Run: lệnh Unity EditMode.
Expected: FAIL. Ví dụ `TransientErrors_RetryWithBackoff("NETWORK")` trả về false, `KnownCodes("AUTHENTICATION_REJECTED", …)` sai chuỗi.

- [ ] **Step 3: Viết code**

`PortalFailurePolicy.cs` (thay toàn bộ):

```csharp
namespace StbEmu.Portal
{
    /// <summary>Only transient transport failures allow an automatic retry or reconnect; account and configuration errors never do.</summary>
    public static class PortalFailurePolicy
    {
        public const int MaxProfileRetries = 3;

        public static bool TryGetProfileRetryDelay(string code, int retries, out System.TimeSpan delay)
        {
            delay = System.TimeSpan.Zero;
            if (retries >= MaxProfileRetries || retries < 0 || code == null) return false;
            if (code.StartsWith("CATALOG_")) code = code.Substring(8);
            if (code != "NETWORK" && code != "TIMEOUT" && !code.StartsWith("HTTP_5")) return false;
            delay = System.TimeSpan.FromSeconds(1 << retries);
            return true;
        }

        public static bool CanRetry(string code)
        {
            if (string.IsNullOrEmpty(code)) return false;
            return code == "NETWORK" || code == "STREAM_LINK_REFUSED" || code.Contains("TIMEOUT") || code.StartsWith("HTTP_5");
        }
    }
}
```

`ErrorText.cs` (thay toàn bộ):

```csharp
namespace StbEmu.UI
{
    /// <summary>User-facing text for Xtream connect errors (native codes, <see cref="Timeout"/>, "CATALOG_&lt;code&gt;").</summary>
    public static class ErrorText
    {
        public const string Timeout = "TIMEOUT";

        /// <summary>ISP DNS often blocks or slows IPTV servers.</summary>
        const string DnsHint = "\nTry again with Cloudflare DNS (1.1.1.1).";

        public static string For(string code)
        {
            if (string.IsNullOrEmpty(code)) return "Unknown error.";
            if (code.StartsWith("CATALOG_")) return "Could not load the channel list. " + For(code.Substring(8));
            if (code == "CONFIGURATION_INVALID") return "Check the server URL, username and password in the account settings.";
            if (code == "AUTHENTICATION_REJECTED") return "The server rejected the username or password.";
            if (code.StartsWith("ACCOUNT_EXPIRED:")) return "The account expired on " + code.Substring(16) + ".";
            if (code == "ACCOUNT_EXPIRED") return "The account has expired.";
            if (code == "ACCOUNT_DISABLED") return "The account is disabled or banned.";
            if (code == "MAX_CONNECTIONS") return "Too many connections on this account. Close another player and try again.";
            if (code == "INVALID_RESPONSE") return "This address is not an Xtream server. Check the server URL.";
            if (code == "FORBIDDEN") return "The server denied access.";
            if (code.StartsWith("HTTP_5")) return "The server has a server error (" + code + "). Please try again later.";
            if (code == "NETWORK") return "Cannot reach the server. Check your connection and the server URL." + DnsHint;
            if (code.Contains(Timeout)) return "The server is not responding. Try again later." + DnsHint;
            return "Error: " + code;
        }
    }
}
```

Kiểm tra: `grep -rn "AuthFailedRepeated" Assets/App --include=*.cs`. Expected: không còn dòng nào. Nếu `AppFlow` còn dùng thì xoá nhánh đó; native không còn gửi mã này.

- [ ] **Step 4: Chạy test, xác nhận pass**

Run: lệnh Unity EditMode.
Expected: PASS toàn bộ.

- [ ] **Step 5: Commit**

```bash
git add Assets/App/Domain/Portal/PortalFailurePolicy.cs Assets/App/UI/ErrorText.cs Assets/App/Tests/EditMode/PortalFailurePolicyTests.cs Assets/App/Tests/EditMode/ErrorTextTests.cs
git commit -m "feat(xtream): retry rules and messages for Xtream errors"
```

---

### Task 8: Build, ghi chú docs, kiểm tra trên thiết bị

**Files:**
- Modify (chỉ thêm 1 dòng ghi chú ở đầu file):
  - `Docs/stbemu-connect-implementation-spec.md`
  - `Docs/STREAMING_IMPLEMENTATION.vi.md`
  - `Docs/ANDROID_PORTAL_CONNECT_RELOAD_DESIGN.vi.md`
  - `Docs/ANDROID_PORTAL_CONNECT_RELOAD_IMPLEMENTATION.vi.md`
  - `Docs/stalker-request-playback-audit.md`
  - `Docs/AppStartup.md`
- Modify: `Assets/Plugins/Android/StbHost.aar` (build lại)

- [ ] **Step 1: Ghi chú docs Stalker**

Thêm dòng đầu (trước tiêu đề) cho mỗi file trong danh sách:

```markdown
> **Lưu ý (2026-10-12):** Stalker portal/WebView đã được thay bằng Xtream Codes API — xem `Docs/superpowers/specs/2026-10-12-xtream-live-design.vi.md`. Tài liệu này chỉ còn giá trị lịch sử.
```

`AppStartup.md` chỉ cần sửa câu nói về WebView, nếu có, thay vì thêm ghi chú lịch sử. Kiểm bằng `grep -n -i "webview\|portal" Docs/AppStartup.md`.

- [ ] **Step 2: Build `.aar` và APK**

```bash
cd Native/StbHost && ./gradlew testDebugUnitTest copyToUnity
```

Expected: `BUILD SUCCESSFUL`, và `Assets/Plugins/Android/StbHost.aar` có thời gian sửa mới.

Sau đó build Android trong Unity (File → Build Profiles → Android → Build) hoặc build qua unity-mcp. Expected: build thành công, không có lỗi Gradle về `webkit` hay class bị thiếu.

- [ ] **Step 3: Kiểm tra tay trên thiết bị**

Dùng tài khoản Xtream do người dùng cung cấp. **Không ghi tài khoản vào repo, commit hay log.** Đánh dấu từng mục:

- [ ] Thêm profile, dán `host:port/` (thiếu `http://`): lưu được, danh sách hiện `host · username`.
- [ ] Kết nối: màn loading hiện "Signing in" rồi "Loading channels", sau đó vào TV screen có thể loại kênh, logo kênh và số kênh.
- [ ] Chọn kênh: phát được; quảng cáo xen kẽ vẫn hiện theo đúng nhịp như trước (tài khoản không Pro).
- [ ] Chuyển 5 kênh liên tiếp nhanh: kênh cuối cùng được phát.
- [ ] Tab EPG: hiện chương trình (hoặc "No EPG available"), tiêu đề có dấu tiếng Việt đọc đúng.
- [ ] Account info: ngày hết hạn, username, trạng thái và số kết nối đúng với panel nhà cung cấp.
- [ ] Tìm kiếm và yêu thích: thêm ★, thoát về Home, vào lại thì ★ vẫn còn.
- [ ] ⟳: danh sách kênh tải lại, không mất kênh đang phát.
- [ ] Sai password: màn lỗi hiện "The server rejected the username or password.", không retry.
- [ ] URL không phải Xtream (ví dụ `http://example.com`): hiện lỗi "not an Xtream server".
- [ ] Tắt Wi-Fi khi đang xem rồi bật lại: stream tự hồi phục, hoặc chọn lại kênh thì phát được.
- [ ] Đổi profile khi đang tải danh sách kênh: profile mới không bị lẫn kênh của profile cũ.
- [ ] Back trên TV screen: về Home.
- [ ] `adb logcat | grep -i "<password>"`: không có dòng nào chứa password.

- [ ] **Step 4: Commit**

```bash
git add Docs/stbemu-connect-implementation-spec.md Docs/STREAMING_IMPLEMENTATION.vi.md Docs/ANDROID_PORTAL_CONNECT_RELOAD_DESIGN.vi.md Docs/ANDROID_PORTAL_CONNECT_RELOAD_IMPLEMENTATION.vi.md Docs/stalker-request-playback-audit.md Docs/AppStartup.md Assets/Plugins/Android/StbHost.aar
git commit -m "chore(xtream): rebuild StbHost.aar and mark Stalker docs as historical"
```
