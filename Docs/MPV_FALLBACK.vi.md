# Media3 → MPV trên Android

Mỗi media item bắt đầu bằng Media3. Sau khi hết phương án decoder HEVC hiện có,
lỗi parse/định dạng hoặc decoder (Media3 3001–3004, 4001–4005) sẽ thử MPV một lần.
Lỗi `IO_BAD_HTTP_STATUS` (2004, kể cả 401/403) cũng thử lại URL đã resolve và cùng
headers bằng MPV một lần trước khi báo lỗi. Không yêu cầu portal cấp URL mới trước lần thử này.
MPV lỗi thì báo `MPV_PLAYBACK_FAILED`; không quay lại Media3 trong cùng item.
Chọn kênh/phát item mới bắt đầu lại bằng Media3. Lỗi mạng khác, live-window
và DRM giữ cơ chế xử lý Media3 hiện có. MPV không thể bảo đảm sửa được lỗi HTTP
nếu server tiếp tục từ chối cùng URL/headers.

## Giao diện và vòng đời

- `HybridPlayer` giữ API điều khiển nội bộ dùng bởi `StbHost` và `StreamInfoDialog`.
- Dùng chung `SurfaceView`: dừng Media3, bỏ media item và surface trước khi MPV nhận surface.
  Đối tượng ExoPlayer được giữ ở trạng thái dừng để dùng cho kênh tiếp theo, không chạy hai engine.
- Giữ âm lượng/trạng thái pause. VOD thử mở gần vị trí cũ; live nối lại từ nguồn.
- Pause, Stop, quảng cáo, background/foreground và audio focus điều khiển engine đang hoạt động.
- Mỗi phiên MPV có định danh riêng: callback JNI cũ bị bỏ qua sau stop/đổi kênh/release.
- MPV dùng MediaCodec copy khi khả dụng và FFmpeg software khi không giải mã phần cứng được.
- Info hiển thị engine/decoder và buffer MPV, không hiển thị số liệu Media3 cũ như số liệu MPV.
- Chuyển engine cần mở lại nguồn nên có thể gián đoạn ngắn.

## HTTP và phạm vi định dạng

`MpvStreamGateway` là HTTP gateway chỉ bind `127.0.0.1`, cổng ngẫu nhiên, route có token riêng.
MPV không nhận URL upstream hoặc credentials trực tiếp. Gateway dùng lại `StreamRequestPolicy`:
User-Agent được giữ; Cookie/Authorization/Referer/header portal chỉ đi tới đúng origin gốc.
Redirect, segment, playlist con và key cũng tuân theo chính sách này.

Hỗ trợ progressive HTTP(S), gồm `.ts`, MP4 và các container MPV nhận diện được; hỗ trợ HLS
bằng cách rewrite URI của variant/segment/key/init-map. Có chuyển tiếp HTTP Range cho VOD.
Không ép mọi URL `.ts` dùng MPV hoặc coi `.ts` là HLS. Playlist HLS được nhận diện qua nội dung.
Route cache giới hạn 4096 mục; buffer native giới hạn 32 MiB; không cache ra đĩa.

Giới hạn hiện tại: gateway không rewrite DASH XML/BaseURL/SegmentTemplate; phản hồi MPD
được nhận diện sẽ bị từ chối ở nhánh MPV. DASH vẫn dùng Media3. Không bổ sung DRM cho MPV.
Không cam kết giải mã realtime được mọi codec/độ phân giải trên thiết bị yếu.

## Dependency và build

- Maven: `dev.jdtech.mpv:libmpv:0.5.1`, khai báo đồng bộ ở `Native/StbHost/build.gradle`
  và `Assets/Plugins/Android/mainTemplate.gradle`.
- Pin 0.5.1 vì API Java bytecode 52 tương thích JDK 17 đi kèm Unity. Bản 1.0.0 dùng bytecode 65 (Java 21).
- `MpvSession` quản lý singleton JNI của bản 0.5.1, gỡ observer khi đóng phiên.
- Consumer ProGuard giữ tên `dev.jdtech.mpv.**` vì JNI gọi theo tên.
- Unity main template thêm pickFirst cho đúng `libc++_shared.so` ở module application.
  Đã so sánh các C++ imports của bộ MPV ARM64 với runtime NDK 27.2 của Unity: không thiếu symbol.
  Cần kiểm tra lại khi nâng MPV hoặc NDK.
- Các ELF ARM64 của dependency có PT_LOAD alignment 16384.
- `StbHost.aar` không chứa dependency Maven; không chỉ chép AAR sang dự án khác mà bỏ khai báo Gradle.

Build từ `Native/StbHost` với JAVA_HOME/ANDROID_HOME trỏ JDK/SDK của Unity:

```powershell
./gradlew.bat testDebugUnitTest copyToUnity --console=plain
```

Dependency upstream: https://github.com/jarnedemeulemeester/libmpv-android/tree/v0.5.1
Wrapper có giấy phép MIT; các native component có giấy phép riêng. FFmpeg trong AAR 0.5.1
có build flags `--enable-gpl --enable-version3` (đã đọc từ binary). Không xem toàn bộ AAR là MIT;
khi phân phối app cần xử lý nghĩa vụ giấy phép/source tương ứng của native dependencies.

## Xác minh

- JUnit: chính sách fallback, progressive TS + Range, HLS redirect + URI tương đối,
  variant/init map, cô lập credentials giữa origin/CDN, token gateway.
- Tổng 56 test đạt, 0 lỗi. `lintDebug` ở lần kiểm tra tích hợp trước chưa đạt: 121 lỗi `UnsafeOptInUsageError`
  tại các lớp Media3 hiện có và 38 cảnh báo; các lớp mới của tích hợp MPV không còn lint finding.
- Build release AAR và copy sang `Assets/Plugins/Android/StbHost.aar`.
- Trên Gradle export IL2CPP hiện có: `:launcher:mergeReleaseNativeLibs` và
  `:launcher:mergeExtDexRelease` qua. Đây là kiểm tra đóng gói trên export hiện có,
  không thay thế một lần build đầy đủ managed code/scenes từ Unity.
- Chưa kiểm tra phát trên thiết bị: ADB không có thiết bị kết nối lúc triển khai.

Các ca cần chạy trên Android thật:

1. Kênh Media3 phát được: không tạo MPV; điều khiển giữ hành vi cũ.
2. TS có codec Media3 không hỗ trợ nhưng FFmpeg hỗ trợ: chuyển MPV, cùng vùng video,
   không phát hai âm thanh; Info hiển thị MPV.
3. Pause/resume, volume, fullscreen, Stop, Home và đổi nhanh nhiều kênh khi MPV đang mở.
4. Quảng cáo và background: không nghe âm thanh nền; quay lại tiếp tục đúng trạng thái.
5. VOD lỗi decoder sau khi đã phát: vị trí được khôi phục gần thời điểm chuyển engine.
6. Stream lỗi ở cả hai engine: thông báo một lần, không chuyển engine lặp vô hạn.
7. HTTP 401/403/404/5xx: thử MPV một lần với URL/headers đã resolve; nếu vẫn lỗi thì dừng.
8. Mất mạng/timeout và DRM: giữ cơ chế khôi phục hiện có, không kích hoạt fallback HTTP.

## Log HTTP (tag StbPortal)

C? Media3 v? MPV ghi `http.request` / `http.response` cho t?ng network hop.
Log g?m engine, request ID, origin (scheme/host/port), t?n headers th?c g?i,
`removedHeaders`, HTTP status v? origin redirect. Media3 c? channel/generation;
MPV c? session ID. Kh?ng ghi URL path/query, gi? tr? headers ho?c n?i dung exception.

Gateway gi? l?i HTTP upstream ?? `playback.error` c? `httpStatus` th?t v? Stream info
hi?n th? nguy?n nh?n. L?i k?t n?i ghi t?n l?p exception; l?i transfer ghi ri?ng v? c?
th? l? ph?a MPV ??ng socket. L?i ch?a bi?t kh?ng b? g?n m?c ??nh l? decoding failed.

Theo y?u c?u ch?n ?o?n VLC, `StbHost.play()` hi?n ghi th?m `Stream resolved URL=<url>` d??i tag `StbPortal`: URL ??y ??, nguy?n gi? tr? ??a v?o player, c? th? ch?a credentials/query token. Quy t?c ?n URL c?a log HTTP ? tr?n kh?ng ?p d?ng cho ri?ng d?ng ch?n ?o?n n?y.
