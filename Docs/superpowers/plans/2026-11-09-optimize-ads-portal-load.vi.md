# OptimizeAds: port từ StbEmu, show interstitial sau khi load portal

Trạng thái: đã code, chưa build/test trên thiết bị. Nguồn: StbEmu `35b9b15`, `eff3cec` (advisor đã xác nhận hướng port tay).

## Mục tiêu
Remote Config key `OptimizeAds` (JSON, mẫu `{"showInterOnPortalLoad":true}`, mặc định tắt) cho phép gọi ad sau mỗi lần load portal trong `AppFlow.ConnectAsync`, thành công hoặc lỗi. Ưu tiên Rewarded Interstitial, fallback Interstitial. Thêm event `show_inter` / `show_reward` / `show_reward_inter` (kèm value) cùng lúc `ad_paid`.

## Phạm vi / ngoài phạm vi
- Có: `OptimizeAds.cs` (+ menu export), `RemoteConfigController` (key, field, parse try/catch riêng), `AppFlow` (`ShowInterAfterPortalLoadAsync`, tham số `preferRewardedInter`), `GoogleAdsMobileController` (event `show_*`), README.
- Có thêm: delay stage 4 của `GoogleAdsMobileController` 10s → 5s (StbEmu `eb5df01`); App Open lúc khởi động vốn tải ở stage 1 nên không bị ảnh hưởng.
- Không: reconnect/watchdog; đổi tên `appOpenFirstSession` (Kestrel cố ý khác StbEmu); `AdMobSettings.asset` và ad unit ID.
- Banner và native Java: đã có sẵn (`d9c8249` trùng `96417a1`), không đổi Java, không cần build AAR.

## Kiểm chứng
- Cần chạy EditMode tests (Editor đóng) và build Android; trên thiết bị: bật `OptimizeAds` trên Firebase, connect thành công và lỗi đều hiện ad; tắt thì không.
