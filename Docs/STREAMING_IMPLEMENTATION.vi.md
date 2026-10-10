# Streaming hiện tại — đối chiếu thiết kế Android IPTV

Bản sửa ngày 03/10/2026 áp dụng phần playback của
[thiết kế streaming](UNITY_ANDROID_IPTV_STREAM_DESIGN.vi.md) vào `Native/StbHost` đang có.

## Đã triển khai

### Tiếp tục stream sau ads/background (2026-10-10)

- Playback gate giữ media item/source, URL, headers và buffer trong bộ nhớ; đóng ads tiếp tục vị trí đang giữ thay vì luôn seek về live edge. Không có cache video trên đĩa; live window phía server vẫn có thể hết hạn.
- Lỗi player trong lúc ads/background được hoãn đến khi tiếp tục playback, tránh kết thúc recovery lúc đang chủ động pause.
- `PlaybackInterruption` loại thời gian ads/background khỏi deadline recovery 30 giây, kể cả callback gate/lifecycle chồng nhau. Khi tiếp tục, khởi động lại watchdog và xử lý lỗi hiện tại theo cùng đường với lỗi lúc đang phát.
- Media IDLE được prepare lại; live ENDED được retry; lỗi live window về live edge. Link hết hạn được resolve lại; nếu portal đã unavailable thì tự reconnect để lấy link thay vì dừng ngay với STREAM_RECOVERY_EXHAUSTED.
- Giữ giới hạn retry, lựa chọn Auto reload/Reload one/No reload và xử lý lỗi codec/DRM không thể khôi phục. Không tạo vòng reload vô hạn.

Kiểm chứng: 60 JVM tests pass, gồm ba regression cases về deadline khi ads kéo dài, ads/background chồng nhau và nhiều lần ngắt. Native build + copyToUnity thành công. Người dùng đã test playback sau khi xem ads và xác nhận hoạt động ổn.

### Playback nền tảng

- Media3 1.11.1: HLS, DASH, HTTP progressive; dependencies đồng bộ giữa AAR và Unity Gradle.
- Buffer mục tiêu 5–15 giây, ngưỡng bộ nhớ 32 MiB; adaptive rebuffer không đợi vượt ngưỡng byte. Đây là cấu hình thử nghiệm dùng chung, chưa tách Live/VOD.
- Live offset theo manifest/engine, không ép 12 giây cho mọi nguồn. Duration chưa biết không tự động được coi là Live; catalog kênh hiện tại vẫn được coi là Live.
- Recovery mạng tối đa hai lần, backoff 2/4 giây, một lần yêu cầu link mới trong session hiện tại. Media3 loader và OkHttp không tự retry thêm.
- Deadline 30 giây cho startup/buffering/recovery đang hoạt động; tối đa ba incident trong năm phút. Sau 10 giây phát ổn định, budget incident được reset nhưng lịch sử cửa sổ vẫn giữ. Đổi kênh chủ động bắt đầu budget mới.
- CDN 401/403 chỉ yêu cầu link mới, không tự handshake/reload portal. Decoder, parser và DRM không dẫn tới reload portal; chưa có compatibility engine.
- Header từng item được sao chép độc lập. Network interceptor áp policy theo scheme/host/port cho cả redirect, manifest, segment và key. Chỉ player User-Agent được dùng trên origin khác. Không follow redirect đổi HTTP/HTTPS.
- Audio focus và audio-becoming-noisy do Media3 xử lý. Có event `firstFrame` riêng với `state`/`isPlaying`.
- Generation chặn lệnh play đã xếp hàng trước đổi kênh/stop/close. Bỏ EventLogger để tránh log exception chứa signed URL.

## Kiểm chứng

- `gradlew.bat testDebugUnitTest copyToUnity`: thành công; AAR release được cập nhật tại `Assets/Plugins/Android/StbHost.aar`.
- Test HTTP dùng MockWebServer xác nhận redirect khác origin không nhận Authorization/Cookie/header riêng; test policy xác nhận header không bị thay đổi từ bên ngoài.
- `playback_check.js`, `fastplay_check.js`, `facade_check.js`: thành công.
- Chưa chạy Unity IL2CPP APK/AAB hoặc ma trận thiết bị/codec trong tài liệu thiết kế.

## Các phần thiết kế chưa chuyển đổi

Hiện vẫn là host SurfaceView nhúng trong Unity Activity và portal WebView/C# bridge cũ.
Chưa có PortalCore native độc lập, PlaybackEngine interface/LibVLC, PlaybackActivity riêng,
API content-ID/typed descriptor cho VOD/catch-up, snapshot/event sequence, track selection API
hay MIME hint cho URL không đuôi. Cookie từ response chưa được lưu vào session context;
policy hiện chỉ dùng header đầu vào và một origin, chưa có allowlist CDN theo account.
Các phần này cần migration riêng; không coi AAR hiện tại là toàn bộ SDK trong thiết kế.

API tham chiếu: [Media3 load error policy](https://developer.android.com/reference/androidx/media3/exoplayer/upstream/LoadErrorHandlingPolicy),
[Media3 OkHttp datasource](https://developer.android.com/reference/androidx/media3/datasource/okhttp/OkHttpDataSource).
