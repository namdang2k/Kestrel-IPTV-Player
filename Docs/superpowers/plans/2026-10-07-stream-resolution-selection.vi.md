# Plan: giới hạn xem HD và thông báo nâng cấp VIP cho kênh vượt HD

## Hiện trạng 2026-10-10

Đối chiếu code hiện tại; dùng cùng [README](../../../README.md) trước khi sửa luồng này.

**Chưa triển khai resolution gate.** Runtime không chặn kênh vượt 1280 × 720 theo quyền Pro và không có menu chọn quality theo plan này. StreamInfoDialog hiển thị resolution để chẩn đoán, không phải bộ kiểm tra entitlement.

- Pro billing đã được triển khai độc lập: Monthly/Yearly dùng premium_monthly; Forever dùng remove_ads. Nhận định “chưa có subscription/billing” của đề xuất gốc đã lỗi thời.
- IsPro hiện cấp quyền không quảng cáo. Không suy rằng app đang giới hạn HD cho user free hoặc đã mở khóa FHD/4K bằng resolution policy.
- Nếu triển khai gate, dùng entitlement từ InAppPurchaseController và bridge native; không tạo thêm hệ VIP hay mock trạng thái đã mua.
- Giữ semantics billing: startup/resume chỉ query SUBS; hủy gia hạn nhưng chưa hết hạn vẫn Pro; Restore lấy cả lifetime và subscription. Không thêm fetch Pro Forever vào startup để phục vụ gate.
- Đề xuất về coded dimensions, audio-only, timeout metadata và chống callback cũ bên dưới chưa phải hành vi app hiện tại. Cần chốt phạm vi và đối chiếu player trước khi triển khai.

Đọc [Pro plans](../../ProSubscriptionPlans.md) và [streaming hiện tại](../../STREAMING_IMPLEMENTATION.vi.md). Không đánh dấu plan này hoàn tất chỉ vì billing hoặc màn Pro đã tồn tại.

## Bản kế hoạch gốc và lịch sử triển khai

> Phần dưới giữ để tra cứu. Khi có khác biệt, dùng hiện trạng ở đầu tài liệu và README; không thực thi lại checklist hoặc chỉ dẫn công cụ/agent cũ mặc định.

Ngày: 2026-10-07. Trạng thái: plan, chưa sửa runtime.

Plan này thay thế toàn bộ đề xuất chọn Auto/manual resolution trước đó. Giữ tên file để các liên kết cũ vẫn mở được.

## 1. Mục tiêu và phạm vi

Theo giả định portal cung cấp các kênh HD/FHD riêng, mỗi channel thường chỉ có một video track, app áp dụng giới hạn độ phân giải theo quyền xem. V1 mặc định chỉ được xem HD; kênh vượt HD bị chặn và hiện thông báo nâng cấp VIP. Chưa triển khai subscription, billing, purchase hay xác thực VIP.

- HD mặc định: tối đa 1280 × 720 pixel của video nguồn, không phải kích thước màn hình/SurfaceView. Đây là quy ước của tính năng, cấu hình ở một policy duy nhất.
- Chuẩn hoá chiều: cạnh dài ≤ 1280 và cạnh ngắn ≤ 720 được phép, gồm SD/720p và video portrait 720 × 1280. 1920 × 1080, 1440 × 1080 và 3840 × 2160 bị chặn.
- Dùng coded dimensions Format.width/height; không lấy tên kênh, bitrate, viewport, rotation màn hình hay pixel aspect ratio làm bằng chứng quyền xem.
- Không có menu Quality, không tự downscale/transcode FHD thành HD, không tự chuyển sang kênh khác mang tên tương tự.
- Các kênh vẫn hiện trong danh sách. Không gắn khoá HD/FHD dựa trên tên; chỉ gắn nhãn nếu có metadata độ phân giải đã xác minh cho nguồn hiện tại.
- Giới hạn này là policy phía app để chuẩn bị subscription. Quyền VIP thật và xác thực entitlement sẽ được bổ sung ở dự án subscription sau.

## 2. Hành vi người dùng

| Trường hợp | Hành vi |
|---|---|
| Chọn kênh, chưa biết resolution | Hiện “Đang kiểm tra độ phân giải…”; video/audio chưa được trình chiếu |
| Nguồn SD/HD trong giới hạn | Cho phát nếu các điều kiện playback/quảng cáo khác cũng cho phép |
| Nguồn vượt HD | Dừng tải/phát nguồn bị chặn; giữ danh sách kênh; hiện popup VIP |
| Không xác định được dimensions | Chờ metadata trong thời hạn hữu hạn; hết hạn báo lỗi kiểm tra, không tự cho phát và không gán nhầm thành VIP |
| Nguồn audio-only đã xác định | Cho nghe; phân biệt với nguồn chưa có metadata video |
| Codec không hỗ trợ, nhưng biết nguồn vượt HD | Ưu tiên thông báo giới hạn HD; không yêu cầu decode thành công mới biết quyền xem |
| Codec/mạng lỗi khi resolution chưa biết | Hiện lỗi kỹ thuật phù hợp, không báo người dùng phải mua VIP để sửa lỗi |
| Bấm lại kênh bị chặn | Kiểm tra request hiện tại; không dùng callback cũ, không tự phát do Resume/Reload |

Popup native:

```text
Cần VIP để xem kênh này

Kênh này có độ phân giải 1920 × 1080 (FHD).
Chế độ hiện tại hỗ trợ tối đa HD (720p).
Nâng cấp VIP để xem các kênh có độ phân giải cao hơn.

[Chọn kênh khác]
```

V1 chỉ có nút đóng/chọn kênh khác và Back; không tạo nút “Mua VIP” chưa hoạt động. Sau đóng popup, giữ placeholder bị chặn, không resume stream. Setting vẫn chỉ có thông tin app và phiên bản theo plan đã chốt. Kênh cũ không cần tự resume sau khi nguồn mới bị chặn; người dùng chọn lại kênh được phép.

## 3. Phase 0 — Discovery bắt buộc

### Nguồn code cần đọc/copy

Các file Java dưới đây thuộc `Native/StbHost/src/main/java/com/stbemu/host/`.

| Nguồn | Pattern/điểm tích hợp |
|---|---|
| `Native/StbHost/build.gradle:10` | Media3 1.11.1, HLS/DASH; giữ dependencies hiện tại |
| `StbHost.java:423–443` | Player builder, analytics/listener trên UI looper |
| `StbHost.java:1450–1515` | Play/source/prepare hiện đang gọi play ngay; cần preflight trước presentation |
| `StbHost.java:190–205`, `543–571` | Background resume và decoder retry có thể bật play lại |
| `StbHost.java:1578–1609` | Ad gate/pause, không được mở lại stream đang bị HD gate chặn |
| `StbHost.java:1630–1709` | Stop/replay/release cleanup |
| `TvScreen.java` | Placeholder/channelFailed/list focus và fullscreen |
| `StreamInfoDialog.java:41–69` | Popup native + dismiss cleanup và đọc Format |
| `Assets/App/UI/AppFlow.cs:376–399` | Native event router; firstFrame/channel.ready/channel.playing |
| `Assets/App/UI/AppFlow.cs:470–510` | Interstitial channel switch, OnChannelReady, SetPlaybackGate(false) |
| `Assets/App/Infrastructure/Android/AndroidStbHost.cs` | JNI wrapper và callback Unity thread |

### Tài liệu chính thức

- [Media3 track selection](https://developer.android.com/media/media3/exoplayer/track-selection): đọc Querying available tracks, ví dụ Java listener/group/format.
- [Format](https://developer.android.com/reference/androidx/media3/common/Format): width/height và metadata chưa biết.
- [Player](https://developer.android.com/reference/androidx/media3/common/Player): prepare/playWhenReady, listener; pause không đồng nghĩa chặn trình chiếu frame đầu.
- [AnalyticsListener](https://developer.android.com/reference/androidx/media3/exoplayer/analytics/AnalyticsListener): callback input video format để bổ sung metadata nếu track snapshot chưa có dimensions.
- [MediaCodecVideoRenderer](https://developer.android.com/reference/androidx/media3/exoplayer/video/MediaCodecVideoRenderer): renderer có thể trình chiếu frame đầu trong trạng thái enabled; phải kiểm tra surface/cover riêng.

**Allowed APIs/patterns:** Player.getCurrentTracks(), Player.Listener.onTracksChanged(Tracks), Tracks.Group.getType()/getTrackFormat(int), Format.width/height, C.TRACK_TYPE_VIDEO; Player.setPlayWhenReady(false), prepare(), stop(), clearMediaItems(); AnalyticsListener.onVideoInputFormatChanged(...), ExoPlayer.getVideoFormat() khi có format, callback first-frame hiện có. Compile đối chiếu signature với artifacts 1.11.1 trước dùng.

**Bằng chứng cần thu trước triển khai gate:** fixture HTTP TS một track và HLS media playlist HD/FHD; đo callback nào cho dimensions khi prepare-paused. Nếu dimensions chỉ đến khi renderer init, giữ presentation bị che/mute trong lúc đó. Không lấy onRenderedFirstFrame làm cơ chế kiểm tra đầu tiên vì lúc ấy hình có thể đã xuất hiện.

Preflight cần đọc một phần dữ liệu nguồn để biết resolution; không hứa chặn mọi request/network bytes từ kênh FHD. Mục tiêu là không cho người dùng xem/nghe nguồn bị giới hạn, rồi dừng tải khi có kết luận.

## 4. Kiến trúc đề xuất

- C# `PlaybackAccessPolicy`: nguồn quyền xem dùng chung, mặc định HD_ONLY. Contract tương lai cho subscription truyền tier/limit vào native; V1 không cho người dùng tự bật VIP trong UI/prefs/profile.
- Java `ResolutionAccessPolicy.java`: hàm thuần đánh giá dimensions và limit, trả UNKNOWN / ALLOWED / BLOCKED. Tách khỏi player và quảng cáo để test.
- Java `ResolutionAccessController.java`: state CHECKING / ALLOWED / BLOCKED / FAILED, source generation, policy revision và timeout.
- Java `ResolutionLimitDialog.java`: popup thông báo VIP, không billing.
- Mở rộng StbHost/TvScreen, AndroidStbHost và AppFlow để phối hợp state, event và quảng cáo. Không sửa schema profiles.json hoặc portal facade chỉ để thêm giới hạn.

Bridge **đề xuất cần tạo**, chưa tồn tại: `setPlaybackAccessPolicy(String policyJson)` / C# `SetPlaybackAccessPolicy(string)`; payload version, policyRevision, tier và dimensions limit. AppFlow truyền policy trước bất kỳ play nào; native default HD_ONLY kể cả khi bridge chưa gửi.

Events **đề xuất**: `stream.access.allowed`, `stream.access.blocked`, `stream.access.failed`, payload gồm sourceGeneration, policyRevision, channelId, width/height nếu biết và reason. Không gửi URL ký token. Unity bỏ callback stale, không xem BLOCKED như network/decoder error.

Điều kiện resume phải tập trung:

```text
canPresent = access == ALLOWED
             && !ad/rewardGate
             && !backgrounded
             && !explicitStopped
             && userWantsPlayback
```

Access gate tách khỏi playbackGate hiện tại: bỏ ad gate không bỏ access gate. BLOCKED là terminal cho request nguồn đó; tắt retry/reconnect/stall watchdog nhằm tránh lặp kiểm tra/hiện popup. Source mới tăng generation và reset CHECKING trước khi bật bất kỳ audio/video nào.

## 5. Phase 1 — Policy và kiểm tra metadata

**Thực hiện**

- [ ] Đọc docs Format/Tracks và copy traversal video group từ track selection guide.
- [ ] Tạo policy HD_ONLY và evaluator cạnh dài/cạnh ngắn; metadata ≤0 là UNKNOWN, không coi là SD.
- [ ] Với nguồn một track, đánh giá dimensions ngay cả khi codec không supported. Entitlement và khả năng decode là hai kết luận riêng.
- [ ] Nếu gặp nhiều video tracks bất ngờ: V1 bảo thủ chỉ cho phép khi tất cả candidate video của feed có dimensions biết và đều trong giới hạn. Có candidate vượt HD thì chặn nguồn, không tự chọn/downscale. Đây là scope V1; tối ưu chọn HD variant là công việc khác.
- [ ] Gắn generation/policyRevision vào kết quả; đánh giá lại khi source, tracks hoặc input format thay đổi.
- [ ] Timeout kiểm tra mặc định 10 giây kể từ prepare, cấu hình được; unknown hết hạn báo lỗi kiểm tra với Retry/Chọn kênh khác.

**Kiểm tra:** JVM table tests 640×480, 1280×720, 720×1280 allowed; 1920×1080, 1440×1080, 3840×2160 blocked; unknown/invalid, multiple tracks, stale generation, policy revision đổi.

**Guard:** không dựa tên HD/FHD hay codec support để quyết định resolution; không coi selected adaptive group là một format duy nhất; chưa biết resolution không được mặc định allow.

## 6. Phase 2 — Preflight và chặn presentation

**Thực hiện**

- [ ] Đọc/copy looper/listener và prepare source từ StbHost; thay flow play-ngay bằng CHECKING → prepare-paused → quyết định.
- [ ] Trước thay source: đóng presentation bằng opaque cover/ẩn surface và mute output, clear frame cũ; giữ user volume riêng để khôi phục. Pause đơn thuần có thể render frame đầu nên không đủ.
- [ ] Lấy metadata qua tracks/input format trong lúc prepare; chứng minh fixture TS cho dimensions mà không cần unpause nghe/xem thử. Không có metadata thì timeout, không bỏ gate để thử phát.
- [ ] ALLOWED: chỉ bỏ cover/mute và bật play khi canPresent đạt; cần frame của đúng source, không hiện ảnh cũ trong surface khi mở cover.
- [ ] BLOCKED: trước tiên dừng player/đóng output, huỷ tải/clearMediaItems phù hợp; giữ blocked context riêng để hiện popup. Không xoá channelId trước khi event/popup có đủ context.
- [ ] Khi blocked, xoá resumeOnStart/resumeAfterGate/heldChannelId liên quan và pending retry/timers; không emit channel.playing/isPlaying=true cho request này.
- [ ] Kiểm tra tất cả đường resume: play, setPaused(false), lifecycle resume, decoder retry, retry mạng, reload/replay, seek live, applyPlaybackGate(false), portal autoplay và fullscreen; đều dùng cùng access decision.
- [ ] Khi dimensions đổi từ HD lên FHD giữa phiên: đóng output ngay khi callback cập nhật, chặn terminal và hiện popup. V1 không hứa ngăn tuyệt đối frame trước callback nếu nguồn khai báo sai; ghi lại kết quả fixture/device cho giới hạn này.

**Kiểm tra:** source FHD không có audio leak/flash frame, pause/resume/Reload không bypass, HD phát lại được sau nguồn blocked, generation callback cũ không mở gate, giữ volume/mute đúng sau allow.

**Guard:** không chặn sau khi video đã phát bằng cách chỉ xử lý firstFrame; không dùng maxVideoSize như kiểm soát quyền; không phát âm thanh kênh FHD trong khi video bị che.

## 7. Phase 3 — UI VIP và phối hợp quảng cáo

**Thực hiện**

- [ ] Copy popup lifecycle từ StreamInfoDialog và placeholder/style từ TvScreen; hiển thị đúng nội dung section 2.
- [ ] Đóng popup không resume; danh sách kênh vẫn tương tác được. Request mới dismiss popup cũ và kiểm tra nguồn mới.
- [ ] AppFlow nhận blocked event, xoá pending adAfterPlayChannel tương ứng và không kích hoạt watch/reward timing cho nguồn bị chặn.
- [ ] Đổi thứ tự channel interstitial ở cả portal-ready và reconnect: resolve/prepare → access allowed → điều kiện ad hiện có → resume. Kênh blocked không phải xem quảng cáo trước thông báo VIP.
- [ ] Giữ player paused trong CHECKING bằng access gate riêng, không dùng ad gate để ngăn prepare/source. `channel.ready` hiện được emit sau play/source setup nên không còn đủ bằng chứng được xem; chỉ phát ad/resume sau allowed đúng generation.
- [ ] Chuyển thời điểm emit channel.ready sang sau khi access ALLOWED; giữ contract ad hold đồng bộ với AppFlow, không emit ready cho BLOCKED/UNKNOWN. Event access dùng generation để tránh channelId trùng trong hai request khác nhau.
- [ ] Quảng cáo/consent đang active: giữ blocked context, đợi ad đóng mới hiện popup; không phủ AlertDialog lên ad và không để ad completion mở access gate.
- [ ] Home/Stop/background/release dismiss popup và cleanup; Back đóng popup rồi giữ blocked placeholder. Không xung đột popup reward/info.

**Kiểm tra:** kênh HD giữ chính sách ad hiện có; kênh FHD không show channel ad/reward; reconnect blocked không loop, ad completion không phát FHD; chuyển kênh nhanh không hiện VIP sai kênh.

**Guard:** không emit lỗi playback chung làm portal reconnect tự động; không giả lập entitlement bằng rewarded ad; không thêm paywall/billing giả.

## 8. Phase 4 — Hook subscription tương lai và nghiệm thu

- [ ] C# provider V1 trả HD_ONLY; thiết kế hook cập nhật policy không phụ thuộc SDK billing. Developer test có thể inject policy cao hơn; production chưa có UI mở VIP.
- [ ] Khi subscription triển khai: entitlement đã xác thực cập nhật policy; muốn xem lại blocked source phải tạo request/kiểm tra mới, không bật play trên state cũ. VIP không đảm bảo codec được thiết bị hỗ trợ.
- [ ] Chạy JVM policy/state tests, Unity EditMode cho bridge/event/ad routing khi cần; build native `testReleaseUnitTest` + `copyToUnity`, build APK bằng pipeline AndroidDevBuild hiện tại.
- [ ] Test thiết bị HD/HLS/TS một track, FHD/4K, unsupported HEVC, unknown metadata/timeout, audio-only, đổi dimensions và callback stale; kiểm tra hình/âm thanh thực tế trong preflight.
- [ ] Regression headers/signed URLs, network retry, portal recovery, ad/reward gate, lifecycle, Stop/Home/fullscreen. Không log stream URL hoặc full profile khi debug.
- [ ] Release/R8 và upgrade profiles cũ vẫn hoạt động; không persist fake VIP vào profiles.json.
- [ ] Ghi rõ nguồn/thiết bị đã test; nếu không chứng minh được không flash FHD khi prepare-paused, chưa coi gate hoàn thành.

**Hoàn thành khi:** SD/HD được xem, vượt HD bị dừng và hiện VIP notice, UNKNOWN không vượt gate và không báo VIP sai, không bypass bằng resume/retry/ad, không show ad cho blocked channel, và subscription có điểm tích hợp rõ mà chưa cần triển khai billing.

## 9. Thứ tự triển khai

Discovery metadata/preflight trên nguồn thật → policy/controller → native gate → popup + ad routing → bridge entitlement hook → build/device QA. Ưu tiên chứng minh preflight TS/HLS một track trước UI; đây là rủi ro kỹ thuật chính. Không kết luận mọi channel chỉ một track cho đến khi kiểm tra nguồn; plan có hành vi bảo thủ cho ngoại lệ đa track.
