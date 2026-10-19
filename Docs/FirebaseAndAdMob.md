# Firebase, AdMob và UMP

Đối chiếu code ngày **2026-10-20**. Monetization (timing, segment, Pro) nằm ở [ProMonetizationDesign](ProMonetizationDesign.vi.md); tài liệu này chỉ mô tả lớp ads/Firebase bên dưới.

`FirebaseController` và `GoogleAdsMobileController` (namespace `StbEmu.Services`) là hai singleton `Instance`, `DontDestroyOnLoad`, nằm trên GameObject riêng trong Main. Khởi tạo chạy từ `Start`, không chặn Loading.

## Định dạng quảng cáo

`AdFormat`: `Banner`, `Interstitial`, `Rewarded`, `AppOpen`, `RewardedInterstitial`, `Native`.

| Format | Load / show | Hết hạn cache |
| --- | --- | --- |
| Interstitial, Rewarded, RewardedInterstitial | `LoadX` / `ShowX`; tải lại sau khi đóng | 55 phút |
| App Open | `LoadAppOpen` / `ShowAppOpen`; chỉ tại điểm vào app (xem bên dưới) | 4 giờ |
| Banner | `ShowBanner` / `HideBanner` / `DestroyBanner`; AppFlow gọi theo layout native | — |
| Native | Vẽ bằng Java (`NativeAdController`); C# chỉ gửi cấu hình và nhận event | — |

- `ShowAdBreak` phát Rewarded trước, RewardedInterstitial là phương án dự phòng; `IsAdBreakReady` là một trong hai sẵn sàng.
- Native chỉ bật khi `SessionCount >= SessionShowNative` (mặc định 2) và có ad unit ID. `AppFlow.SyncNativeAdConfig` gửi `{enabled, adUnitIds}` xuống `StbHost.setNativeAdConfig` mỗi khi ads init, consent, Remote Config hoặc quyền Pro đổi.
- Quyền Pro (`InAppPurchaseController.IsPro`) chặn mọi request/load/show (`NoAds`).
- Load thất bại không lặp vô hạn; lỗi no-fill có backoff.

## ID quảng cáo

Chỉnh `Assets/Resources/Services/AdMobSettings.asset`:

- `Use Test Ads`: bật thì dùng ID test; Editor luôn dùng ID test.
- `Android`: App ID (`~`), Banner, Interstitial, Rewarded, App Open, Rewarded Interstitial, Native (mỗi format là một danh sách ID).
- Format không có ID thì bị tắt, không báo lỗi. Native và Rewarded Interstitial hiện chưa có ID của Kestrel; phải tạo ad unit trong AdMob rồi điền vào asset hoặc Remote Config.
- `AdMobSettingsBuildSync` đồng bộ App ID sang settings của Google SDK trước build Android; menu **StbEmu > Services > Sync AdMob App IDs**. Thiếu App ID thì build báo lỗi. Đổi App ID cần build lại vì SDK đọc từ Android manifest.

## UMP consent

Mỗi lần khởi động controller gọi `ConsentInformation.Update`, rồi `LoadAndShowConsentFormIfRequired`. UMP tự xác định vị trí và trạng thái consent, app không suy đoán quốc gia từ ngôn ngữ máy. Chỉ khởi tạo ads khi `CanRequestAds()` trả về true; nếu không, gọi `Initialize()` để thử lại.

Trong AdMob > Privacy & messaging, tạo và publish European regulations message cho đúng App ID; code không làm được việc này. Test bằng thiết bị thật hoặc UMP debug geography; chế độ test ads không ép vị trí UMP.

`PrivacyOptionsRequired`, event `ConsentUpdated` và `ShowPrivacyOptions()` dùng cho nút Privacy options trong Setting. Mở lại form sẽ hủy ad đã cache và bỏ các request cũ.

## Điều kiện show

Mọi full-screen ad qua `GetTimingBlockReason(format)`:

- Banner và Native không bị timing chặn và không tính vào cooldown.
- Full-screen đọc timing từ `MonetizationController.Get(...)`, không đọc trực tiếp asset.
- Lý do `AdDisplayTiming`: `ad_gap` (min gap sau full-screen ad), `first_session_delay`, `interstitial_cooldown`, `app_open_cooldown`.
- Lý do khác khi từ chối show: `app_open_not_at_entry`, `another_ad_showing`, `ads_not_available`, `ad_not_ready`, hoặc lỗi SDK.
- Khoảng cách tính từ lúc ad thực sự mở (callback SDK), không phải từ lúc load hay lần show bị từ chối. Đặt 0 sẽ được nâng lên mức tối thiểu của `MonetizationPolicy`.

**App Open** chỉ được phép ở đầu session thứ 2 trở đi, hoặc khi quay lại từ background sau `OnApplicationPause`, và không bị cooldown. Cơ hội hết hạn sau 10 giây để không chen ngang khi tải lâu; không tạo cơ hội mới khi quay lại từ ad full-screen hay UMP. `AppOpenEntryPolicy` giữ trạng thái này; `SetAppOpenSuppressed(true)` chặn trong lúc connect, ad đang che hoặc reward prompt.

Session đếm bằng PlayerPrefs `StbEmu.Ads.HasStartedSession` và `StbEmu.Ads.SessionCount`. Scene reload không tạo session mới; trong Editor mỗi lần Play là một session.

## Callback và tracking

`AdCallbacks`: `OnShown`, `OnClosed`, `OnFailed(string)`, `OnImpression`, `OnClicked`, `OnPaid(AdValue)`, chạy trên Unity main thread. Mỗi request full-screen kết thúc đúng một lần qua `OnClosed` hoặc `OnFailed` (`AdShowSession` bảo vệ). Reward chỉ được cấp bởi callback Rewarded của SDK, không cấp ở `OnClosed`.

Event gửi qua `FirebaseController`: `ad_load_failed`, `ad_loaded`, `ad_show_failed`, `ad_reward_earned`, `ad_paid` (hoặc `ad_impression` khi bật `Log Manual Ad Impressions`). Tham số chung: `ad_platform`, `ad_format`, `ad_unit_name`, `placement`, `is_test_ad`; lỗi có `error_message`, doanh thu có `value`/`currency`/`precision`. Giá trị paid đổi từ micros sang đơn vị tiền. Native đẩy event về qua `ads.native` → `TrackNativeEvent`.

Firebase chưa ready thì giữ tối đa 64 event trong RAM, bỏ event cũ nhất khi đầy; không lưu qua lần khởi động. `ad_impression` chuẩn do Firebase/AdMob tự ghi khi đã liên kết tài khoản, nên `Log Manual Ad Impressions` mặc định tắt để tránh ghi trùng.

Event kết nối server: `server_connect` (`url`, `user`, `status`, `error`), gửi từ `AppFlow.TrackConnect`; không bao giờ gửi password.

User property: `tier` (đặt khi fetch Remote Config xong), dùng để phân nhóm theo quốc gia.

## Remote Config

Key `AdMob_Config` (JSON, `AdMobRemoteConfig` trong `RemoteConfigController.cs`) và `AB_Version`. Xem phần Cấu hình trong [README](../README.md).

## Kiểm tra

- EditMode: `AdDisplayTimingTests`, `AdMobRemoteConfigTests`, `MonetizationPolicyTests`, `StreamingAdPolicyTests`.
- Trên thiết bị: UMP debug device (form EEA, chấp nhận/từ chối, mở lại privacy options, offline), load/show từng format bằng ads test, Rewarded chỉ cấp thưởng khi có callback, Firebase DebugView cho event.
- Chưa kiểm thử SDK mạng, form UMP hay quảng cáo thật trên thiết bị trong các thay đổi gần đây.

Nguồn API: <https://firebase.google.com/docs/unity/setup>, <https://developers.google.com/admob/unity/privacy>, <https://firebase.google.com/docs/analytics/measure-ad-revenue>.
