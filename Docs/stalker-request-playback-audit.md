# Stalker: request, streaming và điều khiển video

Đối chiếu mã trong `work` với dự án hiện tại ngày 2026-09-25. Đây là kiểm tra mã nguồn và test tự động; chưa phải kiểm thử với portal/server thật.

## Luồng hiện tại

1. Unity tạo `PortalSession` và snapshot thiết bị, gọi `StbHost.openPortal`.
2. Java đăng ký bridge theo origin của portal và chèn `stb_facade.js` từ đầu trang. Chờ xóa cookie xong mới tải URL; callback của lần mở đã hủy không được tải lại trang.
3. JavaScript của portal thực hiện các request xác thực, danh sách và tạo link. App không gọi lại API Stalker để tự lấy link. Nội dung script phía server không nằm trong `work`, nên thứ tự request thực tế cần xác minh qua Network trên thiết bị.
4. Danh sách native lấy dữ liệu từ bộ nhớ portal. Chọn kênh gọi `module.tv._play_now(channel)`; portal xử lý link rồi gọi `gSTB.Play(command)`.
5. `player.play` đi qua bridge → `AndroidPortalView` kiểm tra session → `PortalController` → `PlayCommand` → Java `StbHost.play` → ExoPlayer.
6. Stream HTTP(S) do `DefaultHttpDataSource` tải riêng với User-Agent MAG mặc định hoặc override của profile. WebView dùng User-Agent mặc định nếu profile không override. Hai đường request không dùng chung một HTTP client.

## Bằng chứng từ bản gốc

- `work/base/res/raw/xmlhttprequest.js`: thay XMLHttpRequest bằng proxy `helper.createRequest`. Dự án hiện tại dùng XMLHttpRequest của WebView; chưa triển khai proxy tương đương. Không thể suy ra hành vi header/CORS/cookie giống hoàn toàn bản gốc.
- `work/decompiled/sources/defpackage/s13.java`: `Play`, `Pause`, `Continue`, `Stop`, `SetViewport`, `SetViewportEx`.
- `j03.java`: `Play(null)` dừng phát; lệnh phát có thể chứa tham số track/position/subURL tách khỏi URL.
- `r03.java`, `q03.java`: thứ tự viewport là **width, height, x, y**.
- `pu.java:updateVideoSize`: scale viewport theo mặt phẳng **1920×1080**, không phải kích thước CSS của portal. Bản gốc bỏ qua kích thước bằng 0.
- `s13.java:SetViewportEx`: dùng bốn tham số đầu; `SetAspect` và `SetVideoState` không thực hiện thay đổi trong bản decompile này.
- `ExoPlayer2.java`: bản gốc có CookieManager của player riêng. Chỉ từ đoạn này chưa thể kết luận nó chia sẻ cookie với WebView.

## Đã sửa/bổ sung

| Thành phần | Kết quả |
|---|---|
| Xóa cookie | Chờ callback trước khi load portal; generation ngăn callback cũ mở lại portal đã đóng. |
| Session native | Viewport và dữ liệu kênh kiểm tra version/session trước khi sử dụng. |
| Viewport portal | `SetViewport`/`SetViewportEx` chuyển qua bridge tới SurfaceView, scale lại khi đổi kích thước màn hình. |
| Phóng to | Hiển thị video toàn vùng 16:9; tạm ẩn hình ảnh WebView nhưng giữ trang và phiên hoạt động. Không khởi động lại stream. |
| Thu nhỏ / Back | Khôi phục viewport gần nhất của portal và hiển thị lại trang. Nếu portal gửi viewport mới, ưu tiên viewport đó. |
| Tạm dừng / Tiếp tục | Đổi `playWhenReady` trên player hiện tại; nhãn dựa trên trạng thái player, không tạo request lấy link mới. |
| Dừng | `stop` + `clearMediaItems`, ẩn surface và trả lại hình ảnh portal. Muốn phát lại cần chọn kênh. |
| `Play(null)` | Gửi `player.stop` giống nhánh tương ứng của bản gốc. |
| Scheme chưa hỗ trợ | Báo `UNSUPPORTED_STREAM_SCHEME` cho RTSP/RTMP/UDP, vì cấu hình player hiện tại chỉ cung cấp HTTP data source và module HLS. |

Nút native điều khiển player trực tiếp. Không giả lập biến pause/fullscreen nội bộ của mọi phiên bản portal. Phóng to giữ tỷ lệ 16:9, không phải zoom/crop nội dung video.

## Giới hạn còn cần xử lý theo portal thực tế

- Player hiện nhận User-Agent từ controller; chưa có cơ chế chia sẻ cookie/Authorization/Referer từ WebView theo từng host. Server yêu cầu các header này có thể từ chối stream dù portal mở được. Không tự chuyển toàn bộ cookie sang CDN.
- `PlayCommand` hiện lấy phần sau prefix làm URL. Tham số cuối như `position:`, `atrack:`, `vtrack:`, `strack:`, `subURL:` chưa được tách/thi hành. Chưa xác nhận seek, track hoặc phụ đề tương đương bản gốc.
- Trạng thái player trả về qua sự kiện riêng `stb:event`; chưa có adapter đầy đủ cho callback MAG của từng portal. Điều này cần kiểm thử với trạng thái loading/end/error của portal thật.
- Pause trên live chỉ tạm dừng player; không cung cấp dịch vụ timeshift hoặc bảo đảm server còn giữ segment khi tiếp tục sau thời gian dài.
- Chưa kiểm thử redirect, URL hết hạn, lỗi 401/403, phát dài, đồng bộ thanh trạng thái của portal hoặc thao tác trên thiết bị.

## Kiểm tra đã chạy

- Gradle `test assembleRelease`: thành công; 17 JUnit tests, không failure/error.
- `test/playback_check.js`: chạy bằng JavaScript V8 harness, kiểm tra chuỗi lệnh play/pause/continue/stop, `Play(null)`, thứ tự viewport, viewport lỗi và loại sự kiện session cũ. Thành công.
- Cần kiểm tra thiết bị: vào danh sách → phát kênh → phóng to → thu nhỏ → pause → continue → stop → chọn lại kênh; xoay màn hình trong preview/fullscreen; refresh/đóng khi đang kết nối.
- Khi lấy Network/log để kiểm tra server, đối chiếu URL đích, status, redirect và header cần thiết; che token, MAC và cookie trong báo cáo.
