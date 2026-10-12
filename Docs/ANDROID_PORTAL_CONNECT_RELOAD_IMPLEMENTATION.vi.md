> **Lưu ý (2026-10-12):** Stalker portal/WebView đã được thay bằng Xtream Codes API — xem `Docs/superpowers/specs/2026-10-12-xtream-live-design.vi.md`. Tài liệu này chỉ còn giá trị lịch sử.

# Áp dụng thiết kế vào StbEmu Unity/WebView

Ngày: 29/09/2026. Tham chiếu: `ANDROID_PORTAL_CONNECT_RELOAD_DESIGN.vi.md`.

## Kiến trúc thực tế

Game dùng Unity C#, Android Java và WebView chạy JavaScript của portal Stalker. Portal sở hữu handshake, token, cookie, profile và danh sách; không có API client Kotlin độc lập. Vì vậy bản triển khai này đặt chính sách tại `AppFlow`, `AndroidPortalView`, `StbHost` và `stb_facade.js`, không đưa một SessionManager thứ hai vào cạnh session do portal quản lý.

## Hành vi đã triển khai

- Connect kiểm tra URL/MAC trước khi mở WebView. `bridge.hello` chỉ xác nhận bridge; connect hoàn tất khi profile có status 0 rõ ràng và snapshot Live hợp lệ đã đến native. UI hiển thị xác thực, kiểm tra tài khoản, tải danh sách.
- Nút ⟳ tải lại danh sách Live bằng GET `get_all_channels` đã quan sát từ chính portal, dùng header của profile được chấp nhận. Các lần bấm đồng thời dùng cùng một request. Không handshake lại chỉ để tải danh sách. Nếu chưa có request GET tương thích hoặc session không còn dùng được, chuyển sang reconnect.
- Nút Reconnect mở lại portal, để portal tự handshake/profile và tải catalog. Các yêu cầu reconnect đang chạy được gộp; operation ID và session ID chặn kết quả/cancellation cũ làm thay đổi tác vụ mới.
- Profile thiếu status, token rỗng, HTML/JSON hỏng được phân biệt với lỗi xác thực. Profile có ID nhưng status từ chối hoặc dấu hiệu khóa rõ ràng không được chấp nhận.
- HTTP 401 trên request có Authorization và chuỗi Stalker `Authorization failed.` kích hoạt báo mất phiên một lần/version. Handshake/profile bị từ chối là lỗi cuối, không refresh đệ quy. 403, 429, 5xx, lỗi mạng có mã riêng. Không đoán provider code từ từ khóa tự do.
- Response của session version cũ không vô hiệu phiên mới. Chỉ quan sát API cùng origin và endpoint đã nhận diện. Header của request tới CDN/origin khác không trở thành header portal.
- Không tự replay `create_link` như một GET đọc an toàn. Fast play cần profile hợp lệ, HTTP thành công và command hợp lệ; không log body/token khi lỗi.
- Snapshot được kiểm tra trước khi cập nhật UI. Danh sách rỗng chỉ được công bố sau response catalog hợp lệ. Reload lỗi giữ snapshot native cũ; lỗi EPG/VOD không tự hủy Live.
- Khóa catalog gồm account/config/identity; các command cũ được xóa khi thay account/config hoặc thay snapshot. Favorites giữ khóa lưu trữ cũ để không mất lựa chọn khi nâng cấp.
- Tự phục hồi xác thực giới hạn một lần trong cửa sổ 5 phút của account/config; lần từ chối tiếp theo yêu cầu thao tác người dùng. Không tự retry lỗi quyền truy cập, profile, dữ liệu sai hoặc rate limit. Reload danh sách có timeout 12 giây; connect/reconnect giữ deadline 30/35 giây hiện có.

## Giới hạn cần biết

Đây là bản áp dụng cho adapter WebView Stalker hiện tại, không phải toàn bộ kiến trúc API client độc lập trong tài liệu thiết kế. Chưa có thao tác `refreshSession` chỉ thay token mà không tải lại page: token còn được JavaScript của portal sử dụng nội bộ, nên tự thay token sẽ có nguy cơ làm hai bên lệch session. Reconnect thực hiện cả xác thực và tải catalog.

Catalog reload hiện cập nhật Live; genre/favorites từ portal vẫn dùng metadata hiện có. Reconnect cập nhật lại toàn bộ dữ liệu do portal khởi tạo. Chưa thêm adapter username/password/playlist, VOD/Series độc lập, cache bền vững qua process death, resolver dò endpoint, Retry-After scheduling hay shared refresh task cho HTTP client độc lập. GET catalog không tự transport retry; lỗi giữ cache và cho thử lại thủ công. Các provider chỉ dùng POST sẽ dùng reconnect thay vì giả lập request không biết hợp đồng.

Không gửi request tới account/portal thật trong quá trình kiểm thử. Cần thử trên thiết bị với portal được phép sử dụng để xác nhận contract profile và hành vi UI; không coi toàn bộ T01–T22 đã nghiệm thu.

## Kiểm chứng

- `node Native/StbHost/test/fastplay_check.js Native/StbHost/src/main/assets/stb_facade.js`: fast play, lỗi typed, burst 401, stale response, origin isolation, Empty, gộp catalog reload, giữ cache khi 500, reload không handshake.
- `playback_check.js` và `facade_check.js`: hồi quy bridge/playback/identity/catalog.
- Gradle `test copyToUnity --offline`: kiểm thử Java, build AAR và cập nhật `Assets/Plugins/Android/StbHost.aar`.
- Biên dịch C# bằng Roslyn với response file Android của Unity; 16 test case retry policy và guard thiếu identity chạy riêng bằng Mono/NUnit.
- Unity EditMode không chạy được vì Editor báo `No valid Unity Editor license found`; chưa build APK hoặc kiểm thử UI trên thiết bị.
