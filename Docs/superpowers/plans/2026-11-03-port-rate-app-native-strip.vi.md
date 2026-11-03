# Port Rate app và native ad strip từ StbEmu

Nguồn: `H:\Unity\StbEmu` commit 47751c3 (Rate app, plan `2026-11-02-rate-app.vi.md`) và df46ca5 (một dải native duy nhất trên TvScreen).

## Đã làm
- Áp patch bằng `git apply --reject`, ghép tay các hunk bị reject: `AndroidStbHost.cs` (`ShowRatePrompt`), `AppFlow.cs` (tick Rate trong Update), `mainTemplate.gradle` (`play:review:2.0.2`), `StbHost.java` (import ReviewManager), `TvScreen.java` (bỏ 2 lần gọi `syncEpgHeaderAd`).
- Mới: `RatePrompt.cs`, `RatePromptTests.cs`, `Docs/RateAppDesign.vi.md`. README thêm dòng Rate app và mô tả dải native.
- `gradlew test copyToUnity --offline` thành công, `StbHost.aar` đã build lại.

## Chưa kiểm chứng
- Unity chưa compile/chạy test (Editor đang mở). Cần: focus Editor, xem Console, chạy EditMode `RatePromptTests`, `AppOpenEntryPolicyTests`, `AdMobRemoteConfigTests`.
- `.meta` của `RatePrompt.cs`, `RatePromptTests.cs`, `AppOpenEntryPolicyTests.cs` do Unity tạo khi refresh, phải commit cùng script.
- Thiết bị: panel Rate ở Home/TV (dọc, ngang); sheet review của Google và không có App Open khi quay lại; vị trí dải native, không chiếm chỗ khi load fail; session 1 xoá dữ liệu app xem `[Ads] App entry`.
- `RateAppDesign.vi.md` copy nguyên từ StbEmu, cần đọc lại các từ chỉ hợp với StbEmu.

Xoá file này khi đã kiểm tra xong.
