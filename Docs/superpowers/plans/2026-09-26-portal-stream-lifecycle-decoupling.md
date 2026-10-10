# Portal/stream lifecycle decoupling plan

## Hiện trạng 2026-10-10

Đối chiếu code hiện tại; dùng cùng [README](../../../README.md) trước khi sửa luồng này.

Đã triển khai tách lifecycle portal và stream. Phần “Kết luận review” trong bản gốc mô tả lỗi trước khi sửa.

- Native đánh dấu portal unavailable khi WebView lỗi trong lúc vẫn có media, giữ playback và controls; C# nhận portal.unavailable.
- Khi chọn kênh cần portal: giữ yêu cầu, phát ui.portal-reconnect và chạy ReconnectPortalAsync; không gọi ConnectAsync vốn dừng player.
- Reconnect có timeout 180 giây ở AppFlow. Thất bại báo về native và giữ stream đang chạy; callback bị thay thế được chặn bằng reconnectEpoch/session.
- Connect ban đầu/chuyển profile và Home chủ động dừng player. Khi chưa có stream, OnPortalError vẫn có nhánh retry/Error.
- Đổi kênh khi portal down phối hợp với ads: lấy link mới, giữ playback gate khi cần interstitial, rồi mở gate qua channel.ready. Callback cũ không được mở gate của yêu cầu mới.
- Recovery lỗi CDN/decoder theo policy player; không tự reload portal cho mọi lỗi playback.
- Ads/background giữ media source và buffer, hoãn xử lý player error cho tới resume, không tính thời gian pause vào deadline recovery. Resume thử phát phần giữ lại; live window cũ hoặc link lỗi đi qua auto-recovery. Nếu portal đã unavailable khi cần link mới thì reconnect tự động. Đây là buffer bộ nhớ, không phải cache video trên đĩa.

Điểm sửa chính: AppFlow.ConnectAsync/ReconnectPortalAsync/OnPortalError, AndroidPortalView, StbHost và facade. Đọc [streaming hiện tại](../../STREAMING_IMPLEMENTATION.vi.md). Kết quả test lịch sử không chứng minh trạng thái thiết bị sau thay đổi mới.

## Bản kế hoạch gốc và lịch sử triển khai

> Phần dưới giữ để tra cứu. Khi có khác biệt, dùng hiện trạng ở đầu tài liệu và README; không thực thi lại checklist hoặc chỉ dẫn công cụ/agent cũ mặc định.

## Kết luận review

Portal (`WebView`) và live stream (`ExoPlayer`) đã là hai runtime riêng trong `StbHost`, nhưng lifecycle ở tầng điều phối chưa tách hoàn toàn:

- `StbHost.play()` cấp media source trực tiếp cho ExoPlayer; stream không phụ thuộc WebView sau khi đã nhận URL.
- `WebViewClient.onReceivedError()` chỉ phát `portal.error`; bản thân callback không dừng player.
- `AppFlow.OnPortalError()` hiện gọi `CloseAndShow()`, mà `CloseAndShow()` gọi `host.Stop()`. Đây là nguyên nhân portal mất kết nối làm live stream đang chạy bị dừng.
- Chọn kênh từ `ChannelPanel` cần portal: native gửi `channels.play` vào facade, rồi facade gọi hàm portal để tạo URL stream mới. Nếu bridge portal đã hỏng, lựa chọn này không thể hoàn tất và cần reconnect portal.
- `ConnectAsync()` hiện luôn `host.Stop()` trước `OpenAsync()`, nên không thể tái kết nối portal mà giữ stream cũ.

Hành vi đích:

1. Portal lỗi khi ExoPlayer đang có media: giữ nguyên stream và controls; đánh dấu portal unavailable.
2. Người dùng tiếp tục xem kênh hiện tại: không reconnect portal.
3. Người dùng chọn kênh khác khi portal unavailable: lưu channel id đang chờ, reconnect portal mà không dừng stream cũ, rồi gửi lại yêu cầu đổi kênh khi facade và channel source sẵn sàng.
4. Reconnect thất bại: stream cũ vẫn chạy; báo lỗi ngắn và cho phép thử chọn/reconnect lại.
5. Lỗi ở lần connect ban đầu, khi chưa có stream: giữ luồng Error hiện tại.

## Phase 0 — API và pattern đã xác minh

Allowed APIs/patterns:

- Portal load: `AndroidPortalView.OpenAsync(PortalSession, CancellationToken)` trong `Assets/App/Infrastructure/Android/AndroidPortalView.cs`.
- Player độc lập: `StbHost.play(...)`, `stop()` và `player.getMediaItemCount()` trong `Native/StbHost/src/main/java/com/stbemu/host/StbHost.java`.
- Portal bridge ready: `bridge.hello` từ `Native/StbHost/src/main/assets/stb_facade.js`; `AndroidPortalView` hoàn tất `OpenAsync` khi nhận hello.
- Native channel request: callback `ChannelPanel` gửi `channels.play`; `channels.data` cho biết facade đã đọc được nguồn channel.
- Native-to-C# lifecycle event: `emit(type, data)` → `AndroidStbHost.NativeEvent` → `AppFlow.OnNativeEvent`.

Không thêm API Media3 giả định và không dùng player state `READY` làm dấu hiệu portal sẵn sàng. Hai trạng thái phải được theo dõi riêng.

## Phase 1 — Mô hình hóa trạng thái portal riêng với playback

**Files:**

- Modify `Native/StbHost/src/main/java/com/stbemu/host/StbHost.java`
- Add/modify unit-testable helper under `Native/StbHost/src/main/java/com/stbemu/host/` và `src/test/` nếu state transition đủ phức tạp.

Thêm trạng thái rõ ràng cho portal (`CONNECTING`, `READY`, `UNAVAILABLE`) và `pendingChannelId`. Khi main-frame load error xảy ra:

- đánh dấu `UNAVAILABLE`, vô hiệu reply bridge cũ;
- nếu player còn media, phát event phục hồi được (ví dụ `portal.unavailable`) và không gọi `stop()`, không xóa media;
- nếu chưa có media, tiếp tục phát lỗi connect như hiện tại.

Giữ dữ liệu channel đã cache và nút 📺 khi stream đang chạy, để người dùng vẫn có thể yêu cầu kênh khác. Không coi `portalReply != null` là health check vì reply proxy có thể còn object sau khi mạng mất.

**Verification:** test transition lỗi portal có/không có active media; grep bảo đảm nhánh WebView error không gọi `stop()`/`clearMediaItems()`.

## Phase 2 — Tách initial connect và portal reconnect trong AppFlow

**Files:**

- Modify `Assets/App/UI/AppFlow.cs`
- Modify `Assets/App/Infrastructure/Android/AndroidPortalView.cs` nếu cần expose lifecycle có kiểu rõ hơn.
- Add EditMode tests cho state policy nếu tách policy thành lớp C# thuần.

Tạo hai đường gọi có chủ đích:

- initial/profile connect: hành vi cũ, được phép dừng player;
- reconnect portal for channel switch: gọi `OpenAsync(new PortalSession(current), ...)` nhưng không gọi `host.Stop()`, không chuyển sang màn Error che playback, và không reset playback controls.

`OnPortalError` khi đang phát chỉ ghi nhận portal unavailable. `OnNativeEvent("ui.portal-reconnect", channelId)` khởi động reconnect giữ playback. Timeout/load failure của reconnect chỉ báo lỗi không phá stream.

Không tái sử dụng nguyên trạng `ConnectAsync()` với một boolean mơ hồ; tách method hoặc options enum để call site thể hiện rõ có được dừng playback hay không.

**Verification:** EditMode policy tests cho initial failure, background portal failure, reconnect success/failure; xác nhận `host.Stop()` chỉ còn ở profile connect, Home/explicit Stop.

## Phase 3 — Hàng đợi đổi kênh qua reconnect

**Files:**

- Modify `Native/StbHost/src/main/java/com/stbemu/host/StbHost.java`
- Modify `Native/StbHost/src/main/assets/stb_facade.js` chỉ khi cần acknowledgement rõ ràng.
- Extend `Native/StbHost/test/facade_check.js` và Java tests.

Khi chọn channel lúc portal `UNAVAILABLE`:

1. lưu/ghi đè `pendingChannelId` bằng lựa chọn mới nhất;
2. emit `ui.portal-reconnect` một lần (debounce các lần bấm);
3. vẫn phát stream cũ trong lúc chờ;
4. sau session mới nhận `bridge.hello`, đợi `channels.data` của đúng session để chứng minh source portal đã sẵn sàng;
5. gửi `channels.play` với pending id và session id mới;
6. chỉ xóa pending khi facade xác nhận nhận/xử lý yêu cầu. Nếu chưa có ack, thêm message nhỏ `channels.play.accepted`/`channels.error`; không dùng việc ExoPlayer đổi state làm ack vì stream cũ vẫn có thể phát state events.

Nếu channel id không còn tồn tại, hiện Toast và giữ stream cũ. Reconnect mới không được gọi `closePortalOnUi()` theo cách reset `waitingForChannel`, cache channel hoặc playback state ngoài phần portal thực sự cần reset.

**Verification:** tests cho latest-selection-wins, chỉ một reconnect đang chạy, stale session bị bỏ, reconnect xong replay đúng một lần, channel-not-found giữ stream cũ.

## Phase 4 — Build và kiểm thử thiết bị

1. Chạy Node facade tests và Java unit tests; build lại `StbHost.aar`, copy sang `Assets/Plugins/Android/StbHost.aar` theo quy trình hiện có.
2. Chạy Unity EditMode tests liên quan portal/controller/AppFlow.
3. Device matrix bằng logcat:
   - mở live → làm portal main-frame mất mạng nhưng stream CDN vẫn tới được → hình/âm thanh tiếp tục;
   - không thao tác → không có portal reload tự động;
   - chọn channel khác → một reconnect portal, stream cũ tiếp tục cho đến khi URL mới được phát;
   - reconnect thành công → chuyển đúng channel;
   - reconnect timeout/403 → stream cũ tiếp tục, có thông báo ngắn;
   - lỗi portal ở initial connect khi chưa có media → vẫn vào Error screen;
   - Home/Stop/profile khác → player vẫn dừng đúng chủ đích.
4. Kiểm tra log theo generation/session id để bảo đảm callback portal cũ không đổi kênh sau reconnect.

## Implementation status (2026-09-26)

- Implemented portal state/generation tracking, channel request recovery, session-gated replay, and separate initial-connect/reconnect paths.
- Added Java state-policy tests and rebuilt/copied `StbHost.aar` with `gradlew test copyToUnity` (pass).
- Facade suite passed using the bundled Unity 2021 Node runtime with command-only compatibility shims (`TextEncoder`, `btoa`, `assert.match`, `globalThis`); coverage includes the channel accepted acknowledgement. The playback suite passed unmodified.
- Final review fixes preserve the latest queued channel across in-flight failures/timeouts, suppress startup autoplay and late playback after explicit Stop, and restore stream retries only when the player has an error. State regression tests cover latest-selection persistence, periodic channel updates, and cancelled pending replay.
- Unity EditMode/compile checks were not started because this project is already open in running Unity processes and no standalone .NET SDK is installed.
- Android device/logcat matrix remains required before release; it cannot be exercised in this workspace session without a connected device.

## Guard rails

### Recovery policy update (2026-09-28, user-requested)

The user requested automatic recovery when both portal and stream disconnect. This supersedes the original prohibition on reconnecting the portal after stream failures: a healthy stream still never triggers a reconnect solely because the portal failed.

- Auto reload is now the default. Retry the existing source after 2, 4, and 8 seconds; only executed retries consume the budget. A failure after retry three requests one portal reconnect.
- Buffering for 30 seconds also enters recovery; live/unknown-duration stream termination does too. Normal finite-media completion does not. Playback must remain active for 10 seconds to reset an outage budget; repeated commands for the same URL do not reset it.
- Preserve media against portal Stop commands during failed playback/retries. Explicit Stop/Home cancel recovery timers. Keep the loading portal hidden during automatic stream recovery.
- Replay a remembered native-selected channel through the new portal session for a fresh URL. If playback originated only in the portal and no channel ID is known, allow the new portal's startup autoplay. A portal that does not autoplay is bounded by the recovery deadline, not assumed to have restored playback.
- A portal/channel recovery failure starts another bounded stream-retry cycle after 15 seconds. Preserve the latest queued channel across failures. The total automatic portal recovery deadline is 45 seconds, including channel resolution.
- Validation: Java unit tests (including retry count, debounce, cancelled timer budget, mode overrides, and budget reset), facade and playback checks passed; rebuilt/copied Unity AAR. ADB reported no connected devices, so simultaneous disconnect/network restoration remains a device validation requirement. No APK was built in this change.

### Follow-up verification (2026-09-28)

- Found an uncovered path: portal-initiated main-frame navigation did not enter CONNECTING or hide its loading UI. A successful bridge hello could also reveal a reconnecting portal before channel discovery completed.
- Added main-frame navigation handling, a 30-second readiness watchdog while media exists, presentation gating until channels.data, and deferred startup viewport updates. Failure hides the portal without clearing media; idle viewing does not trigger a native reload.
- Manual portal reload now uses the playback-preserving reconnect path and preserves cached channel state even without a pending selection.
- Added three presentation/navigation policy regression tests. `gradlew test copyToUnity --offline`, facade checks (with the previously documented Node compatibility shims), and playback checks passed. Updated the Unity AAR.
- Unity EditMode tests were not run with the project open in Unity. No ADB device was connected. The reported brown-screen incident remains unconfirmed without device logs; exercise self-navigation, stalled channel discovery, manual reload, and reconnect failure on device.

- Không reload portal chỉ vì một lần stream buffer/error; chỉ escalate sau 3 lần retry stream thất bại theo yêu cầu cập nhật ngày 28/09.
- Không dừng hoặc clear ExoPlayer trong portal error/reconnect path.
- Không gửi pending channel qua reply proxy/session cũ.
- Không xóa channel cache ngay khi portal lỗi nếu đang phát; cache chỉ là UI snapshot, request đổi kênh vẫn phải qua portal session mới.
- Không biến lỗi subresource/HTTP phụ thành portal disconnect; chỉ main-frame failure hoặc bridge health failure đã xác định mới đổi portal state.
