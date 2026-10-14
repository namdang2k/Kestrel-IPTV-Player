# Live `.ts` bị ngắt định kỳ — nguyên nhân và cách khắc phục

Ngày: 2026-10-14 · Áp dụng: `Native/StbHost/.../StbHost.java` (Kestrel), port được sang StbEmu (cùng engine).

## Tóm tắt

Kênh live phát được 20–50 s rồi đen/khựng 2–5 s, lặp lại; sau vài lần app dừng hẳn
(`RECOVERY_RATE_LIMIT`) hoặc báo "Too many connections" (`MAX_CONNECTIONS`).

Nguyên nhân gốc nằm ở server (đóng kết nối định kỳ), nhưng engine có 3 lỗi làm nó lộ ra thành
gián đoạn. Sau khi sửa: 4 phút phát, server đóng kết nối 8 lần, **0 lần gián đoạn**.

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

## Cách khắc phục

Tất cả trong `StbHost.java`. A–E là engine, port nguyên sang StbEmu; F–G chỉ cho Xtream.

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

### B. Nối lại trước (connect ahead) khi server đóng response

Ý tưởng: ngay khi response đóng (`onLoadCompleted`), thêm **cùng URL làm item kế tiếp** trong playlist.
ExoPlayer tải trước item kế tiếp trong lúc phát nốt buffer, rồi chuyển tiếp không đen màn.

Field:

```java
private DefaultMediaSourceFactory playingSource; // the playing URL's headers/policy, for queueLiveReconnect
private int aheadRetries; // refused connections ahead since the last switch-over
```

Trong `play()` — giữ factory thay vì dùng một lần, reset bộ đếm:

```java
playedStably = false;
aheadRetries = 0;
emit("firstFrame", "false");
playingSource = new DefaultMediaSourceFactory(http).setLoadErrorHandlingPolicy(new StreamLoadErrorPolicy());
player.setMediaSource(playingSource.createMediaSource(mediaItem));
```

`AnalyticsListener` (cạnh `onVideoDecoderInitialized`):

```java
@Override public void onLoadCompleted(EventTime eventTime, androidx.media3.exoplayer.source.LoadEventInfo info,
        androidx.media3.exoplayer.source.MediaLoadData data) {
    queueLiveReconnect(eventTime.windowIndex, false);
}
@Override public void onLoadError(EventTime eventTime, androidx.media3.exoplayer.source.LoadEventInfo info,
        androidx.media3.exoplayer.source.MediaLoadData data, java.io.IOException error, boolean wasCanceled) {
    // Host only: the path holds the credentials.
    Log.i(LOG_TAG, "Load error " + describe(error) + " after " + info.loadDurationMs + " ms, " + info.bytesLoaded
            + " B, host=" + info.uri.getHost());
    retryQueuedReconnect(eventTime.windowIndex);
}
```

`Player.Listener` — bỏ item đã phát xong khi chuyển tiếp; bỏ item đang chờ khi có lỗi:

```java
@Override
public void onMediaItemTransition(MediaItem mediaItem, int reason) {
    if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || player.getCurrentMediaItemIndex() == 0) return;
    player.removeMediaItems(0, player.getCurrentMediaItemIndex()); // the closed response's item
    aheadRetries = 0;
    Log.i(LOG_TAG, "Live stream continued on the new connection");
}

@Override
public void onPlayerError(@NonNull PlaybackException error) {
    dropQueuedReconnect();
    handlePlayerError(error);
}
```

Các hàm (đặt sau `handlePlayerError`):

```java
private void queueLiveReconnect(int windowIndex, boolean retry) {
    // A live progressive .ts also reports isCurrentMediaItemLive(); only HLS/DASH have a manifest.
    if (playingSource == null || playingChannelId.isEmpty() || player.getCurrentManifest() != null
            // The last item, current or still queued: a queued connection can close before it plays too.
            || windowIndex < player.getCurrentMediaItemIndex() || windowIndex != player.getMediaItemCount() - 1
            || !canRecoverStream()) return;
    if (retry) Log.i(LOG_TAG, "Connecting ahead, retry " + aheadRetries + ", " + player.getTotalBufferedDuration() + " ms buffered");
    else Log.i(LOG_TAG, "Server closed the live response; connecting ahead with " + player.getTotalBufferedDuration() + " ms buffered");
    player.addMediaSource(playingSource.createMediaSource(new MediaItem.Builder().setUri(playingUrl).build()));
}

/** Recovery restarts the current item; a connection queued behind it would only be a second one. */
private void dropQueuedReconnect() {
    int count = player.getMediaItemCount(), current = player.getCurrentMediaItemIndex();
    if (current + 1 < count) player.removeMediaItems(current + 1, count);
}
```

Lưu ý điều kiện:

- `windowIndex < current` loại response cũ; `== count - 1` chỉ nối sau item cuối → luôn ≤ 1 kết nối mở
  (quan trọng với `max_connections=1`).
- Cho phép item **đang chờ** (chưa phát) cũng nối tiếp: response chờ có thể bị đóng trước khi tới lượt.
- HLS/DASH bỏ qua: chúng tự tải lại playlist, `onLoadCompleted` bắn cho từng segment.
- Item lỗi chỉ ném `PlaybackException` khi ExoPlayer đọc tới nó, nên phải gỡ trước (mục C).

### C. Thử lại kết nối ahead khi bị từ chối

```java
/**
 * The queued connection was refused (407/502 while the server still counts the closed one): drop it before ExoPlayer
 * reaches it, and try again in 1 s while enough is buffered. Otherwise the ENDED path reconnects.
 */
private void retryQueuedReconnect(int windowIndex) {
    if (windowIndex != player.getCurrentMediaItemIndex() + 1 || windowIndex != player.getMediaItemCount() - 1) return;
    player.removeMediaItem(windowIndex);
    // The server can refuse for ~10 s after closing: keep trying while the buffer still covers a connect.
    if (aheadRetries >= 10 || player.getTotalBufferedDuration() < 2000) { aheadRetries = 0; return; }
    aheadRetries++;
    MediaItem item = player.getCurrentMediaItem();
    Log.i(LOG_TAG, "Connection ahead refused; retry " + aheadRetries + " in 1 s");
    ui.postDelayed(() -> {
        // Skipped after a channel change, stop, or the item running out: those take their own path.
        if (player.getCurrentMediaItem() == item && player.getPlaybackState() == Player.STATE_READY)
            queueLiveReconnect(player.getCurrentMediaItemIndex(), true);
    }, 1000);
}

/** The innermost cause, e.g. "HTTP 502", "ProtocolException: Received HTTP_PROXY_AUTH (407)…", "SocketTimeoutException". */
private static String describe(java.io.IOException error) {
    Throwable root = error;
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
        if (cause instanceof HttpDataSource.InvalidResponseCodeException)
            return "HTTP " + ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
        root = cause;
    }
    String message = root.getMessage();
    return root.getClass().getSimpleName() + (message == null || message.contains("://") ? "" : ": " + message);
}
```

Không rút ngắn `readTimeout` cho kết nối ahead: nó sẽ thành kết nối chính, và ExoPlayer không chuyển tiếp
qua item bị lỗi (lỗi ném ra thay vì `ENDED`).

### D. `ENDED` sau khi phát ổn định: nối lại ngay, ngoài budget sự cố

Dự phòng khi ahead không kịp (bị từ chối lâu hơn buffer). Field + `stablePlayback`:

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
| B field, D field | cạnh `playingHeaders` ~dòng 160, `stablePlayback` ~dòng 170 |
| B `onLoadCompleted/onLoadError` | `addAnalyticsListener` ~dòng 498 |
| B `onMediaItemTransition`, `onPlayerError` | `Player.Listener` ~dòng 541 |
| D `STATE_ENDED` | ~dòng 516 |
| B trong `play()` | `player.setMediaSource(new DefaultMediaSourceFactory(http)…` ~dòng 1587 |
| E | `failStream` |

F, G không áp dụng (Stalker không có `allowed_output_formats`/`player_api`).

**Riêng Stalker:** link từ `create_link` thường có `play_token` dùng một lần/hết hạn nhanh. Nếu mở lại
cùng URL bị 401/403: kết nối ahead sẽ bị từ chối (C thử lại rồi bỏ), rồi `ENDED` (D) phát lại URL cũ, lỗi
401/403 đi `resolveStreamLink()` xin link mới như hiện nay — vẫn đúng nhưng còn gián đoạn. Nếu thấy
`Load error HTTP 401/403` ở kết nối ahead, cần cho `queueLiveReconnect` xin link mới (`create_link`) thay
vì dùng lại `playingUrl`. Kiểm tra bằng log trước khi làm.

Media3 phải ≥ 1.x có `AnalyticsListener.onLoadCompleted/onLoadError(EventTime, LoadEventInfo, MediaLoadData, …)`
(đã kiểm với 1.11.1).

## Kiểm chứng

Log mong đợi khi server đóng kết nối:

```
I/StbPortal: Server closed the live response; connecting ahead with 26092 ms buffered
I/StbPortal: Live stream continued on the new connection
```

Khi server tạm từ chối:

```
I/StbPortal: Load error HTTP 502 after 300 ms, 0 B, host=…
I/StbPortal: Connection ahead refused; retry 1 in 1 s
I/StbPortal: Connecting ahead, retry 1, 9014 ms buffered
```

Kết quả trên Samsung SM-A066B, kênh live Xtream, 4 phút:

| | Trước | Sau |
|---|---|---|
| Server đóng kết nối | mỗi 20–40 s | mỗi 21–35 s (không đổi) |
| Gián đoạn mỗi lần | đen 2–5 s; lần 4/5 phút → dừng hẳn | 0 |
| Buffer lúc server đóng | 3–15 s | 7 → 52 s |
| Loader ngừng đọc | 10–45 s | không |

- JVM: `gradlew testDebugUnitTest` — 77/77 pass (không có test JVM cho phần ExoPlayer; kiểm chứng bằng thiết bị).
- Chưa kiểm: HLS live thật sau thay đổi (logic bỏ qua khi có manifest), Android TV, đổi kênh nhanh khi
  đang có item chờ (`setMediaSource` thay cả playlist; retry kiểm tra item hiện tại trước khi nối — cần test tay).
