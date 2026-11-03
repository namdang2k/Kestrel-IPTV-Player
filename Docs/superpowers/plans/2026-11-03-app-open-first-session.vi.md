# App Open ngay từ session đầu tiên

## Mục tiêu
Cho phép hiện App Open ở lần mở app đầu tiên (cold start, session 1), bật/tắt được qua `AdMobSettings` và Remote Config.
Mặc định bật.

## Phạm vi
- Thêm bool `appOpenFirstSession` (không phải timing, không vào `MonetizationPolicy.Names`).
- Chỉ điều khiển cold start của session 1. Resume trong session 1 vốn đã hiện App Open, giữ nguyên.
- Ngoài phạm vi: đổi cooldown, đổi cửa sổ 10 s, `firstSessionInterstitialDelaySeconds`.

## File sửa
- `Assets/App/Services/AdMobSettings.cs`: field + property `AppOpenFirstSession`.
- `Assets/App/Services/RemoteConfigController.cs`: field cùng tên trong `AdMobRemoteConfig`.
- `Assets/Resources/Services/AdMobSettings.asset`: `appOpenFirstSession: 1`.
- `Assets/App/Services/GoogleAdsMobileController.cs`: Awake (dòng 113), stage 1 (dòng 192), log (dòng 173).
- `Assets/App/Tests/EditMode/AdMobRemoteConfigTests.cs`: test round-trip.
- Docs: `Docs/FirebaseAndAdMob.md` (dòng 51), `Docs/ProMonetizationDesign.vi.md` (dòng 87), README mục cuối nếu liệt kê field.

## Bước
1. Awake luôn tạo cơ hội startup (`new AppOpenEntryPolicy(startupOpportunityCreated, ...)`), vì `settings` và Remote Config chưa sẵn sàng lúc đó.
2. Stage 1 (sau 1 s, Remote Config đã có cơ hội áp dụng): nếu `IsFirstSession && !settings.AppOpenFirstSession` thì `appOpenEntry.Consume()`; bỏ điều kiện `!IsFirstSession` ở if.
3. Thêm field, asset, remote config, test, docs.

## Quyết định của advisor
- Không đặt cổng trong `ShowAppOpen`/`HandleAppOpenEntry` (sẽ chặn nhầm resume ở session 1).
- Remote JSON được parse bằng `FromJsonOverwrite` lên object seed từ asset, nên JSON cũ thiếu field vẫn giữ giá trị asset (true).
- Load App Open chỉ bắt đầu từ stage 1/4 hoặc resume, nên không có đường hiện trước stage 1.

## Kiểm tra
- EditMode `AdMobRemoteConfigTests` (field sống qua `From` → `ApplyTo`).
- Trên thiết bị (chưa kiểm được ở đây): xoá dữ liệu app, mở lần đầu, xem log `[Ads] App entry`.

## Cập nhật 2026-11-03
Sau review: thêm `AppOpenEntryPolicy.Renew(now)`, gọi ở stage 1 cho session đầu để cửa sổ 10 s không hết hạn vì UMP/init; `Consume()` khi tắt được chuyển lên trước lệnh return sớm. Xoá file plan này sau khi kiểm tra trên thiết bị xong.

## Rủi ro chưa kiểm
- Cửa sổ 10 s tính từ Awake; session 1 còn có UMP form, init, load ad nguội nên có thể hết hạn ("App open kept for the next entry").
- Session 1 thường chạy bằng giá trị asset vì Remote Config không lưu và lần cài đầu chưa fetch; Editor không áp Remote Config.
- Cần xuất lại JSON và publish `AdMob_Config` nếu muốn tắt từ xa (README mục cuối).
