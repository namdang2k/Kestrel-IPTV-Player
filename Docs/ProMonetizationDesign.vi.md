# Thiết kế Pro + Monetization segment

Mô tả code Kestrel ngày **2026-10-20**, đã đồng bộ với StbEmu (`MonetizationPolicy.Version = 2`). Khi có khác biệt giữa tài liệu này và code, code là nguồn đúng.

Namespace và key PlayerPrefs vẫn dùng tiền tố `StbEmu` (`StbEmu.Monetization.Behavior`, `StbEmu.IAP.*`, `StbEmu.Ads.*`); không đổi vì sẽ làm mất trạng thái người dùng.

## 1. Bản đồ file

| Lớp | File | Vai trò |
|---|---|---|
| Logic thuần (không Unity) | `Assets/App/Services/MonetizationPolicy.cs` | Bounds, công thức effective, giảm reward theo thời gian xem, hệ số tier, `UserBehaviorState` |
| Runtime | `Assets/App/Services/MonetizationController.cs` | Singleton: bundled → raw → effective, lưu behavior, Firebase events |
| Remote Config | `Assets/App/Services/RemoteConfigController.cs` | Fetch `AdMob_Config` → `SetRawConfig`; đặt user property `tier` |
| Ads | `Assets/App/Services/GoogleAdsMobileController.cs` | `GetTimingBlockReason` đọc timing từ controller |
| Luồng app | `Assets/App/UI/AppFlow.cs` | Hold/publish, đếm watch, ad break, timer upsell |
| Reward timer | `Assets/App/Services/StreamingAdPolicy.cs` | `Tick` / `GrantReward` |
| IAP | `Assets/App/Services/InAppPurchaseController.cs` | `IsPro`, mua/restore |
| Bridge Pro | `Assets/App/Infrastructure/Android/AndroidProfileUi.cs` | Nhận `ui.pro.*`, gọi IAP + ghi behavior |
| Native | `Native/StbHost/.../StbHost.java`, `TvScreen.java`, `HomeScreen.java`, `ProDialog.java`, `NativeAdController.java` | Nút Pro, menu ◆, upsell dialog, Pro sheet, native ad |
| Editor | `Assets/App/Services/AdMobTierConfigExporter.cs` | Menu `StbEmu/admod_setting_tier_1/2/3` |
| Test | `Assets/App/Tests/EditMode/MonetizationPolicyTests.cs`, `StreamingAdPolicyTests.cs`, `AdDisplayTimingTests.cs`, `AdMobRemoteConfigTests.cs` | NUnit EditMode |

`Tools/Test-AdServices.ps1` (harness csc thuần) hiện không biên dịch được: `AdDisplayTiming` cần `AdFormat.Native` từ `AdMobSettings` (Unity) và `Tools/Tests/AdServicesPolicyTests.cs` vẫn dùng API policy v1. Lỗi này đã có sẵn ở StbEmu; dùng EditMode tests thay thế.

## 2. Pro

### 2.1 Sản phẩm (Google Play)

| Product | Loại | Ghi chú |
|---|---|---|
| `kestrel_pro_forever` | Non-consumable | "Lifetime" (`forever`) |
| `kestrel_pro` | Subscription | 2 base plan: `monthly`, `yearly` (có thể có trial) |

`IsPro = IsOwned(kestrel_pro_forever) || HasActiveSubscription`. Pro ⇒ tắt mọi ads (`NoAds`), segment = `pro`, không ad break, không upsell.

### 2.2 Quyền lợi (`ProDialog.FEATURES`)

1. Không ads ở mọi nơi, kể cả khi đổi kênh.
2. Stream không bị ngắt bởi "xem ad".
3. Picture in picture.
4. Mở stream bằng VLC / copy stream URL.
5. Không có lời nhắc nâng cấp.

### 2.3 UI

- **Home** (`HomeScreen.setPro`): free = nút accent "Get Pro" + diamond viền; Pro = nút thường "Pro" + cùng diamond màu gold (`PortalStyle.GOLD = #FFC107`).
- **Player** (`TvScreen`): nút ◆ chỉ hiện khi stream đã load và controls đang hiện. Menu ◆: Copy stream URL / Picture in picture / Open in VLC; free có thêm "Upgrade to Pro" (→ `ui.pro.open ""`).
- Free bấm item bị khóa → upsell dialog "`<tên item>` is a Pro feature".
- **Upsell dialog** (`StbHost.upsell(feature)`): một dialog cho 2 nguồn.

  | Nguồn | Tiêu đề | Go Pro | Cancel/Back |
  |---|---|---|---|
  | Timer (Unity gọi `showUpsell()`) | Upgrade to Pro | `ui.pro.open "nudge"` | `ui.pro.nudge-dismiss` |
  | Item khóa (`feature != null`) | `<item>` is a Pro feature | `ui.pro.open "feature"` | không emit |

  Không mở khi dialog hoặc Pro sheet đang mở.
- **Pro sheet** (`ProDialog`): nhận JSON `{pro, forever, subscription, activePlan, monthlyPrice, yearlyPrice, monthlyTrial, yearlyTrial, lifetimePrice}`. Status overlay `{busy, title, message}` cho mua/restore.

### 2.4 Bridge native ↔ Unity

Native → Unity (`emit(type, data)`):

| Event | Data | Xử lý C# |
|---|---|---|
| `ui.pro.open` | `""` / `"nudge"` / `"feature"` | `RecordPaywallView(source)`, retry init IAP, `RefreshPlans`, `ShowProPanel` |
| `ui.pro.buy` | `lifetime` / `monthly` / `yearly` | `RecordCheckoutStarted` trước khi mở Play → mua → `RecordCheckoutResult` |
| `ui.pro.restore` | — | `iap.Restore` |
| `ui.pro.nudge-dismiss` | — | `RecordNudgeDismissed` |

Unity → native: `showProPanel(json)`, `hideProPanel()`, `setProState(json)`, `showProStatus(json)`, `showUpsell()`, `hideUpsell()`.

## 3. Monetization

### 3.1 Sáu timing (giây, theo thứ tự `MonetizationPolicy.Names`)

| # | Field | Asset (base) | Min | Max | × engaged |
|---|---|---|---|---|---|
| 0 | `interstitialIntervalSeconds` | 60 | 30 | 1800 | 1.5 |
| 1 | `appOpenIntervalSeconds` | 30 | 30 | 3600 | 1.5 |
| 2 | `firstSessionInterstitialDelaySeconds` | 300 | 30 | 3600 | 1 |
| 3 | `minSecondsBetweenAds` | 15 | 10 | 600 | 1.5 |
| 4 | `rewardedViewingSeconds` | 300 | 60 | 7200 | 1 |
| 5 | `upsellIntervalSeconds` | 300 | 90 | 7200 | 0.75 |

Min là sàn an toàn chống config sai, không phải base. Engaged được ít ads hơn (×1.5 các khoảng cách) và upsell sớm hơn (×0.75). Cột Asset lấy từ `AdMobSettings.asset` hiện tại; khi chạy app dùng asset hoặc Remote Config làm raw.

Ngoài sáu timing, `AdMobRemoteConfig` còn `tier` (chuỗi, mặc định `Default`), `useTestAds`, `sessionShowNative` (mặc định 2) và ID theo nền tảng.

### 3.2 Pipeline config

```
Bundled (asset, snapshot 1 lần) → Raw (Remote Config AdMob_Config, hoặc bundled) → Effective
Effective[f] = clamp(raw[f] × (engaged ? EngagedMultiplier[f] : 1), Min[f], Max[f])
Effective[RewardedViewing] = max(60, clamp(raw) − RewardedReduction(totalWatch))
```

- Luôn tính từ raw, không bao giờ từ effective cũ (tránh nhân dồn).
- Raw không hợp lệ (NaN/âm/vô hạn) → bundled; bundled cũng hỏng → Min.
- `rewardedViewingSeconds = 0` là sentinel **tắt ad break**; field khác bằng 0 bị nâng lên Min.
- `RewardedReduction(totalWatch)` = `floor(totalWatch / 600) × 10` giây: cứ 600 giây xem tích lũy (không bao giờ trôi khỏi bucket) thì khoảng reward ngắn đi 10 giây, sàn 60 giây. `engaged` không nhân reward (hệ số 1) vì đã có cơ chế này.
- **Bẫy:** `MonetizationController.Instance` phải được chạm **trước** khi Remote Config ghi đè asset (làm ở `RemoteConfigController.Awake`). Trong Editor, Remote Config không ghi vào asset.
- Mọi chỗ đọc timing phải qua `MonetizationController.Get(field)`, không đọc thẳng asset.

### 3.3 Segment

| Segment | Điều kiện |
|---|---|
| `pro` | `IsPro` |
| `engaged` | ≥ 1800 s xem **và** ≥ 2 ngày có xem, trong 7 ngày UTC gần nhất |
| `casual` | còn lại |

Hành vi lưu trong `UserBehaviorState` (PlayerPrefs `StbEmu.Monetization.Behavior`, JSON):

- 7 bucket theo ngày UTC (`day % 7`): `bucketDay[]`, `bucketWatch[]`; `totalWatch` (double, tổng thời gian xem từ trước tới nay, không trôi). State cũ chưa có `totalWatch` được khởi tạo từ tổng các bucket.
- Mốc thời gian Unix giây: `lastNudgeAt`, `lastNudgeDismissAt`, `lastCheckoutCancelAt`; `watchSinceNudge`.
- `Normalize(now)`: đồng hồ lùi → kéo mốc tương lai về now, xóa bucket ngày tương lai; JSON hỏng/khác version → state mới.
- Watch chỉ đếm khi **thực sự đang phát** (foreground, playing, không ad/prompt), không phụ thuộc ad sẵn sàng. Lưu mỗi 30 s và mỗi lần ghi sự kiện.

Segment **chỉ** phụ thuộc Pro + thời gian xem.

### 3.4 Hold / publish

- `SetHold(true)` khi state là Connecting / Portal / RewardPrompt.
- Trong lúc hold, config hay segment mới không áp dụng; rời stream mới `Publish`, để ngưỡng thấp hơn không bắn ad giữa stream.
- `Publish` tính lại segment + effective, chỉ log khi có thay đổi. Mua thành công → `RequestPublish`.

### 3.5 Ads gate (`GetTimingBlockReason`)

- Banner và Native → không bị chặn bởi timing.
- Full-screen → xét timing bằng `policy.Get(...)` (interval, first session delay, min gap).
- Bị chặn → event `ad_show_failed` kèm reason (xem [FirebaseAndAdMob](FirebaseAndAdMob.md)).

Không còn "intent window": checkout hoặc mở paywall không chặn ads.

### 3.6 Ad break (bắt buộc, không có "Not now")

- `viewing.Tick(dt, streaming && !blocked, Get(RewardedViewing))`; `blocked = ads null || AdsDisabled (Pro)`. Đủ thời gian xem thì `RewardRequired`.
- Một break đến hạn chờ hết khoảng min gap sau full-screen ad cuối (ví dụ interstitial dẫn vào stream), rồi `RequestAdBreak`. Nếu `IsAdBreakReady` (Rewarded hoặc RewardedInterstitial sẵn sàng) → hiện popup đếm ngược 3 giây rồi phát ad; nếu chưa → hỏi lại sau 10 giây, **không** tự gọi load.
- `ShowAdBreak`: Rewarded trước, RewardedInterstitial dự phòng. Ad xem đủ, đóng sớm hoặc lỗi giữa chừng đều tính là đã xong break: `EndRewardPrompt` → `GrantReward` → bắt đầu chu kỳ đầy mới. Không còn khoảng "skipped" ngắn hơn.
- Khi trở lại từ background giữa lúc đếm ngược, popup đếm lại từ đầu. Back bị bỏ qua khi popup đang hiện.
- Nếu Pro mua giữa chừng: đóng popup đang hiện và reset `RewardRequired`.

### 3.7 Upsell theo timer

Không còn `TryNudge` ở Home. `AppFlow.Update` cộng `upsellSeconds` (thời gian app thật) khi:

- không phải Pro, app không pause, không chuyển ad, không ở RewardPrompt;
- không có full-screen ad hoặc consent form đang chạy.

Đủ `Get(Upsell)` thì `host.ShowUpsell()` rồi reset bộ đếm. Pro → `host.HideUpsell()` và reset bộ đếm. Upsell từ item khóa tách biệt, không qua timer.

`MonetizationController.TryNudge`, `UserBehaviorState.CanNudge` và `RecordNudge` còn trong code nhưng không ai gọi (di sản từ nudge ở Home). `RecordNudgeDismissed` vẫn được gọi khi người dùng Cancel upsell.

### 3.8 Firebase events

| Event | Params |
|---|---|
| `monetization_policy_applied` | `policy_version`, `ab_version`, `segment`, `effective` (6 số, phẩy), `reasons` (`field:invalid_raw` / `zero_raised_to_min` / `clamped`) |
| `segment_changed` | `from`, `to` |
| `paywall_view` | `source`: `manual` / `nudge` / `feature` |
| `upsell_dismissed` | — |
| `checkout_started` | `plan` |
| `checkout_result` | `plan`, `result` (`purchased`/`pending`/`cancelled`/`failed`) |
| `ad_show_failed` | reason chặn (xem 3.5) |

User property: `tier`. String params bị cắt 100 ký tự.

### 3.9 Tier preset (Editor)

- Menu `StbEmu/admod_setting_tier_1/2/3`: asset × `TierMultiplier`, ghi JSON (UTF-8 không BOM) có `tier = tier_N` để dán vào Firebase condition theo quốc gia.
- Tắt khi Play mode; không sửa asset. Log `base × multiplier = output` và cảnh báo khi runtime sẽ clamp.

| Tier | Interstitial | AppOpen | FirstDelay | MinGap | RewardView | Upsell |
|---|---|---|---|---|---|---|
| 1 | ×1.5 | ×1.5 | ×1.5 | ×1.5 | ×1.5 | ×1 |
| 2 | ×1.3 | ×1.3 | ×1.3 | ×1.3 | ×1.3 | ×1 |
| 3 | ×1 | ×1 | ×1 | ×1 | ×1 | ×1 |

Base hỏng (âm/NaN/upsell < 1) → không xuất.

## 4. Kiểm tra khi đổi monetization

- EditMode: `MonetizationPolicyTests`, `StreamingAdPolicyTests`, `AdDisplayTimingTests`, `AdMobRemoteConfigTests`.
- Compile Android define (`UNITY_ANDROID && !UNITY_EDITOR`); JVM test + build AAR bằng JDK 17.
- Trên máy: ad break (xem đủ / đóng sớm / ad lỗi), đổi kênh, về Home, mua rồi quay lại app (không App Open), upsell timer (hạ `upsellIntervalSeconds` để thử), item khóa → upsell, native ad ở Home và EPG.

## 5. Chưa làm

- Event `playback_session_summary`; event khi hiện upsell của item khóa (hiện chỉ đo khi bấm Go Pro).
- Gate riêng cho banner khi Pro sheet đang mở.
- Dọn code nudge không dùng (3.7).
- `Tools/Tests/AdServicesPolicyTests.cs` và `Tools/Test-AdServices.ps1` cần cập nhật theo policy v2 (xem mục 1).
