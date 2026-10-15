# Live `.ts` bị ngắt định kỳ — nguyên nhân và cách khắc phục

Ngày: 2026-10-14 · Áp dụng: `Native/StbHost/.../StbHost.java` (Kestrel), port được sang StbEmu (cùng engine).

## Tóm tắt

Kênh live phát được 20–50 s rồi đen/khựng 2–5 s, lặp lại; sau vài lần app dừng hẳn
(`RECOVERY_RATE_LIMIT`) hoặc báo "Too many connections" (`MAX_CONNECTIONS`).

Nguyên nhân gốc nằm ở server (đóng kết nối định kỳ), nhưng engine có 3 lỗi làm nó lộ ra thành
gián đoạn. Sau khi sửa: 4 phút phát, server đóng kết nối 8 lần, **0 lần gián đoạn**.

**Cập nhật 2026-10-15:** cách "nối lại trước" bằng playlist (B/C bản cũ) làm video **tua lại** mỗi lần nối:
server gửi lại 4–18 s đã phát (mục 7). Thay bằng `ReconnectingTsDataSource` (mục B mới), bỏ mục C.

## Lỗi hiện tại

### 1. Server đóng response live `.ts` mỗi 20–40 s (phía server, không sửa được)

Log thật (server Xtream: nginx phía trước, nhiều node Apache2 phía sau):

```
Load completed 36086 ms 13015932 B buffered=7913 ms Connection=[close] Server=[Apache2]
Load completed 22499 ms 10864876 B buffered=11091 ms Connection=[close] Server=[Apache2]
```

- Response không có `Content-Length`, kết thúc bằng `Connection: close` → ExoPlayer coi là **hết stream**
  (`STATE_ENDED`), không phải lỗi.
- Mỗi lần stream đi qua node khác (185.223.214.99, .120, 149.57.5.178, 45.155.90.157).
- Tài khoản `max_connections=1`; ngay sau khi đóng, server còn đếm kết nối cũ nên kết nối mới bị từ chối
  vài giây đến ~15 s: `HTTP 502` (nginx), `407` (`ProtocolException: Received HTTP_PROXY_AUTH`),
  hoặc 200 nhưng không gửi byte nào (`SocketTimeoutException` sau 10 s).
- Đã kiểm tra `player_api` ngay lúc đóng: `active=1 max=1` và đó là kết nối của chính app —
  không có client khác dùng chung tài khoản.

### 2. Engine: loader ngừng đọc socket 10–45 s (lỗi app — StbEmu cũng có)

```java
.setBufferDurationsMs(5_000, 15_000, 1_500, 3_000)
```

`DefaultLoadControl.shouldContinueLoading` (Media3 1.11.1, dòng ~805) có hysteresis: buffer ≥ max (15 s)
→ ngừng tải, chỉ tải lại khi < min (5 s). Live `.ts` là một response HTTP dài, server đẩy liên tục;
app không đọc ~10 s thì server đá kết nối. Log chứng minh:

```
14:53:32 Loading=false buffered=15443 ms   ← ngừng đọc
14:53:42 Loading=true  buffered=5300 ms    ← đọc lại sau 10.5 s
14:53:42 Load completed ... Connection=[close]   ← EOF: server đã đóng trong lúc nghỉ
```

Khi server burst (gửi dồn ~50 s dữ liệu trong 28 s), nếu chỉ nâng max lên 50 s thì khoảng nghỉ thành ~45 s.

### 3. Engine: chỉ nối lại sau khi phát hết buffer

Server đóng khi buffer còn 3–15 s, nhưng app phát hết rồi mới `STATE_ENDED` → `stop/prepare` → đen ~2.5 s.
Thời gian còn lại trong buffer bị lãng phí.

### 4. Engine: mỗi lần `ENDED` tính là một sự cố phục hồi

`scheduleRetry()` → `beginRecovery()`: tối đa 3 sự cố / 5 phút. Server đóng 4 lần trong 5 phút →
`failStream("RECOVERY_RATE_LIMIT")`, app dừng hẳn.

### 5. Media3: live progressive `.ts` cũng có `isCurrentMediaItemLive() == true`

`ProgressiveMediaPeriod` báo live khi không biết độ dài → không dùng được `isCurrentMediaItemLive()` để
tách HLS khỏi `.ts`. Phải dùng `player.getCurrentManifest() != null` (chỉ HLS/DASH có manifest).

### 6. (Chỉ Xtream) `.m3u8` bị 509 mỗi ~50 s

Xtream HLS: mỗi lần tải lại playlist bị tính thêm kết nối → `HTTP 509` rồi `502`. Dùng `.ts`.

### 7. Response mới gửi lại 4–18 s đã phát — kể cả khi đổi node

Đo trên kênh Stalker (tvportal4k, SM-A066B) bằng cách ghi bytes từng kết nối rồi so frame video:

| Nối lại | Frame trùng nội dung | PTS |
|---|---|---|
| conn_0→1 | 590 (~14 s) | cùng gốc |
| conn_2→3 | 192 | **node khác**, lệch +3623 s |
| conn_3→4 | 173 | node khác, lệch −36.7 s |
| conn_4→5 | 655 (~18 s) | cùng gốc |

- Cùng nội dung thì bytes ES giống hệt nhau, kể cả khác node → so khớp theo nội dung frame được, theo PTS thì không.
- Mỗi response mới là một item playlist mới, phát từ frame đầu → người xem thấy tua lại.
- Chờ trước khi nối (thử 3 s) không giúp: lúc server đóng, buffer có khi chỉ còn 2 s.
- Luồng gốc của server tự có lỗ 1–9 s trong một response (PTS nhảy tới) → ExoPlayer log
  `UnexpectedDiscontinuityException` và nhảy tới; không phải do app.

## Cách khắc phục

`StbHost.java` và `ReconnectingTsDataSource.java`. A, B, D, E là engine, port nguyên sang StbEmu; F–G chỉ cho Xtream.

### A. Buffer không hysteresis

```java
// min = max: with 5/15 s the loader stopped at 15 s until 5 s were left (~10 s, after a server burst ~45 s
// at 50 s) without reading, and IPTV servers drop a live .ts response nobody reads. Equal thresholds only
// pause it briefly. Startup still waits bufferForPlaybackMs; 32 MB still caps memory.
.setBufferDurationsMs(50_000, 50_000, 1_500, 3_000)
.setTargetBufferBytes(32 * 1024 * 1024)
```

`minBufferMs` chỉ dùng cho quyết định tải; thời gian vào kênh vẫn theo `bufferForPlaybackMs` (1.5 s).
**Tác dụng phụ:** khi server burst, buffer lên ~50 s → hình trễ live ~50 s. Muốn ít trễ hơn: 20–30 s
(min = max), đổi lại ít thời gian dự phòng để nối lại.

### B. Một luồng liền: `ReconnectingTsDataSource`

`play()` bọc DataSource của live `.ts` (HLS/DASH giữ nguyên — `Util.inferContentType == CONTENT_TYPE_OTHER`):

```java
DataSource.Factory source = Util.inferContentType(Uri.parse(url)) == C.CONTENT_TYPE_OTHER
        ? () -> new ReconnectingTsDataSource(http.createDataSource(), message -> Log.i(LOG_TAG, message))
        : http;
```

ExoPlayer chỉ thấy **một** response không bao giờ hết. Bên trong:

1. Server đóng (EOF) hoặc mạng rớt giữa chừng → tự mở lại cùng URL. Bị từ chối (502/407/timeout) → thử lại
   mỗi 1 s, tối đa 20 s rồi ném lỗi cho luồng phục hồi cũ. 401/403/404/509 ném ngay (`handlePlayerError` →
   `resolveStreamLink` xin link mới).
2. Ghép theo PES hoàn chỉnh; PES dở dang cuối response cũ bị bỏ (bản đầy đủ có trong response mới).
3. Frame video của response mới có hash nội dung đã phát → bỏ; frame lạ đầu tiên là điểm nối, và độ lệch
   PTS = PTS đã phát − PTS mới của frame khớp cuối. Không khớp frame nào → nối tiếp theo DTS + 1 frame.
4. Audio (và PES khác có PTS) giữ lại tới khi biết độ lệch; bỏ gói có PTS ≤ gói cùng PID đã phát.
5. Viết lại PTS/DTS và continuity counter → decoder chạy tiếp từ frame cuối, không cần keyframe, không tua,
   không item mới. Node mới đổi PID trong PMT → ném lỗi (TsExtractor chỉ đọc PMT đầu).

Response có độ dài (VOD) đi thẳng, không đụng tới. Bỏ toàn bộ connect-ahead cũ: `playingSource`,
`aheadRetries`, `queueLiveReconnect`, `retryQueuedReconnect`, `dropQueuedReconnect`, `onLoadCompleted`,
`onMediaItemTransition`. `onLoadError` chỉ còn log.

Hạn chế: PCR không viết lại (TsExtractor không dùng cho progressive); lần nối "không khớp" có thể lệch audio
vài trăm ms (ExoPlayer tự nhảy theo).

### D. `ENDED` sau khi phát ổn định: nối lại ngay, ngoài budget sự cố

Dự phòng khi `ReconnectingTsDataSource` bỏ cuộc (bị từ chối quá 20 s) hoặc nguồn không qua nó. Field + `stablePlayback`:

```java
private final Runnable stablePlayback = () -> {
    if (player != null && player.isPlaying()) {
        reloadPolicy.reset();
        recoveryStartedAt = -1;
        playedStably = true;
    }
};
// IPTV servers close live .ts responses every 20-40 s: an end after stable playback reconnects at once, outside the
// incident budget; an end soon after that takes scheduleRetry.
private boolean playedStably;
```

`onPlaybackStateChanged`:

```java
if (state == Player.STATE_ENDED && isLive()) {
    if (playedStably && canRecoverStream() && !waitingForChannel && !streamPortalRecovery) {
        playedStably = false;
        Log.i(LOG_TAG, "Live stream ended by server; reconnecting");
        player.stop();
        player.seekToDefaultPosition();
        player.prepare();
    } else scheduleRetry();
}
```

`playedStably = false;` trong `play()` (xem B). Một `ENDED` ngay sau khi nối lại (chưa phát ổn định 10 s)
vẫn đi `scheduleRetry` có giới hạn → không có vòng nối lại vô hạn.

### E. Log mã lỗi khi dừng phát

```java
private void failStream(String code) {
    Log.w(LOG_TAG, "Stream failed: " + code + " channel=" + playingChannelId);
    ...
```

### F. (Xtream) Ưu tiên `.ts` — `XtreamClient.authenticate()`

```java
// ts first: one long connection. Many panels' HLS refreshes count as extra connections and hit 509 within a minute.
boolean ts = formats == null || formats.length() == 0, m3u8 = false;
for (int i = 0; formats != null && i < formats.length(); i++) {
    ts |= "ts".equalsIgnoreCase(formats.optString(i));
    m3u8 |= "m3u8".equalsIgnoreCase(formats.optString(i));
}
String ext = !ts && m3u8 ? "m3u8" : "ts";
```

### G. (Xtream) 509 = giới hạn kết nối: kiểm tra tài khoản thay vì retry mù — `handlePlayerError`

```java
// 509: Xtream's connection/bandwidth limit; ask the account why instead of retrying the same server.
if (status == 401 || status == 403 || status == 509) {
    resolveStreamLink();
    return;
}
```

## Port sang StbEmu

Engine giống Kestrel; điểm neo trong `StbEmu/Native/StbHost/src/main/java/com/stbemu/host/StbHost.java`:

| Sửa | Vị trí (khoảng) |
|---|---|
| A | `setBufferDurationsMs(5_000, 15_000, …)` ~dòng 480 |
| B | chép `ReconnectingTsDataSource.java` (+ test); trong `play()` bọc `http` ở `player.setMediaSource(new DefaultMediaSourceFactory(http)…` ~dòng 1587 |
| D field | cạnh `stablePlayback` ~dòng 170 |
| D `STATE_ENDED` | ~dòng 516 |
| E | `failStream` |

F, G không áp dụng (Stalker không có `allowed_output_formats`/`player_api`).

**Riêng Stalker:** link từ `create_link` có thể có `play_token` dùng một lần. Nếu mở lại cùng URL bị 401/403,
`ReconnectingTsDataSource` ném lỗi ngay và `handlePlayerError` → `resolveStreamLink()` xin link mới (có gián
đoạn). Kênh đã đo (tvportal4k) mở lại cùng URL được.

## Kiểm chứng

Log mong đợi khi server đóng kết nối:

```
I/StbPortal: Live response closed after 38 s, 14550 KB; reconnecting
I/StbPortal: Live stream resumed: dropped 518 replayed frames
```

Khi server tạm từ chối:

```
I/StbPortal: Reconnect refused: HttpDataSourceException
I/StbPortal: Live stream resumed: dropped 0 replayed frames, none matched
```

Kết quả `ReconnectingTsDataSource` trên SM-A066B, kênh Stalker, 4 phút (ghi bytes sau DataSource rồi phân tích):

| | Playlist connect-ahead | Một luồng liền |
|---|---|---|
| Server đóng kết nối | mỗi 15–60 s | mỗi 32–60 s (5 lần) |
| Nội dung bị phát lại mỗi lần nối | 4–18 s | **0 frame** (bỏ 327–680 frame) |
| Continuity counter đứt | — | 0 |
| Bị từ chối ~10 s | đen/ENDED khi hết buffer | 2 lần, nối tiếp không stall |

- JVM: `gradlew testDebugUnitTest` — 79/79 pass (`ReconnectingTsDataSourceTest`: gửi lại từ node khác,
  từ chối rồi thử lại, PES dở dang, response nối tiếp không trùng).
- Chưa kiểm: kênh Xtream sau thay đổi, HLS live, Android TV.
