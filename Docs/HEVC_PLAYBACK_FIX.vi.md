# Sửa đường fallback HEVC

## Phát hiện trong mã

Player dùng `new ExoPlayer.Builder(activity)` với cấu hình renderer mặc định, chưa bật decoder fallback. Nhánh lỗi decoder lúc chạy có thể đi vào retry stream rồi reload portal, trong khi vẫn chọn lại decoder vừa lỗi. Chưa tái hiện trên thiết bị: thời điểm triển khai không có thiết bị ADB hoặc stream mẫu.

## Thay đổi

- `DefaultRenderersFactory.setEnableDecoderFallback(true)` cho phép Media3 thử decoder tiếp theo khi khởi tạo decoder thất bại.
- `HevcDecoderSelector` giữ thứ tự decoder hệ thống, gồm decoder software nếu Android cung cấp. Khi HEVC gặp `ERROR_CODE_DECODING_FAILED` có tên codec từ `MediaCodecDecoderException`, loại codec đó trong lần phát hiện tại và chỉ chuẩn bị lại player nếu còn decoder hỗ trợ đúng format.
- Tối đa hai lần chuyển decoder cho mỗi lần phát; không chuyển sang codec không đáp ứng DRM. Request/headers/media source được giữ; VOD giữ vị trí, live trở về live edge. Không ép phát khi đang pause, quảng cáo hoặc chạy nền.
- Lỗi codec hết phương án fallback dừng retry tự động và không kích hoạt reload portal. Chọn phát mới mở lại ngân sách decoder.
- Stream info có tên decoder, số lần fallback runtime và mã lỗi playback; tên decoder cũng ghi vào logcat `StbHost`.

## Giới hạn

Chưa thêm thư viện giải mã video HEVC bằng phần mềm vào APK. Vì vậy thay đổi này giúp khi decoder ưu tiên bị lỗi và thiết bị có decoder khác tương thích; không bảo đảm giải mã được HEVC Main10/4K trên mọi máy. Bộ FFmpeg extension chính thức của Media3 phục vụ audio, không phải một bộ giải mã HEVC video có thể bật bằng dependency đơn giản.

Nếu vẫn lỗi, cần model thiết bị/Android, giá trị Decoder và Playback error trong Stream info, cùng thông tin stream (codec/profile/độ phân giải). Với thiết bị thiếu decoder phù hợp, phương án tiếp theo là tích hợp backend có software HEVC như libVLC hoặc cung cấp/transcode stream H.264. Chưa đổi backend khi chưa có bằng chứng lỗi thuộc thiếu decoder.

## Kiểm thử

- Gradle `test copyToUnity --offline`: biên dịch Java, chạy unit tests và cập nhật `Assets/Plugins/Android/StbHost.aar`.
- `DecoderRetryBudgetTest`: loại codec đã lỗi, giới hạn hai lần chuyển, không retry codec không rõ tên, reset khi phát mới.
- Cần kiểm thử thiết bị: HEVC chạy bình thường; init lỗi chuyển codec; lỗi runtime chuyển codec; không có codec dự phòng; pause/background/ad gate khi chuyển; H.264 hồi quy. Unit tests không xác nhận khả năng decode bitstream thực.

Nguồn: [Media3 supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats), [DefaultRenderersFactory](https://developer.android.com/reference/androidx/media3/exoplayer/DefaultRenderersFactory).
