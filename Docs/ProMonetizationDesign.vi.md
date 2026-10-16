# Thiết kế Pro + Monetization segment (để port sang Xstream)

Tài liệu mô tả đúng trạng thái code StbEmu tại commit `32effa2` (+ icon Pro gold). Plan gốc: `Docs/superpowers/plans/2026-10-16-monetization-unified.vi.md`.
Khi port, đổi namespace/prefix `StbEmu`/`com.stbemu.host` và key PlayerPrefs, giữ nguyên quy tắc.

## 1. Bản đồ file

| Lớp | File | Vai trò |
|---|---|---|
| Logic thuần (không Unity) | `Assets/App/Services/MonetizationPolicy.cs` | Bounds, công thức effective, hệ số tier, `UserBehaviorState` (segment, intent window, nudge) |
| Runtime | `Assets/App/Services/MonetizationController.cs` | Singleton: bundled → raw → effective, lưu behavior, Firebase events |
| Remote Config | `Assets/App/Services/RemoteConfigController.cs` | Fetch `AdMob_Config` → `SetRawConfig` |
| Ads | `Assets/App/Services/GoogleAdsMobileController.cs` | `GetTimingBlockReason` đọc timing + gate từ controller |
| Luồng app | `Assets/App/UI/AppFlow.cs` | Hold/publish, đếm watch, reward prompt, nudge ở Home |
| Reward timer | `Assets/App/Services/StreamingAdPolicy.cs` | `Tick` / `GrantReward` / `SkipReward` |
| IAP | `Assets/App/Services/InAppPurchaseController.cs` | `IsPro`, mua/restore |
| Bridge Pro | `Assets/App/Infrastructure/Android/AndroidProfileUi.cs` | Nhận `ui.pro.*`, gọi IAP + ghi behavior |
| Native | `Native/StbHost/.../StbHost.java`, `TvScreen.java`, `HomeScreen.java`, `ProDialog.java` | Nút Pro, menu ◆, upsell dialog, Pro sheet |
| Editor | `Assets/App/Services/AdMobTierConfigExporter.cs` | Menu `StbEmu/admod_setting_tier_1/2/3` |
| Test | `Tools/Tests/AdServicesPolicyTests.cs`, `Tools/Test-AdServices.ps1` | Harness csc thuần |

## 2. Pro

### 2.1 Sản phẩm (Google Play)

| Product | Loại | Ghi chú |
|---|---|---|
| `remove_ads` | Non-consumable | "Lifetime" (`forever`) |
| `premium_monthly` | Subscription | 2 base plan: `monthly`, `yearly` (có thể có trial) |

`IsPro = IsOwned(remove_ads) || HasActiveSubscription`. Pro ⇒ tắt mọi ads (`NoAds`), segment = `pro`, không reward prompt, không nudge.

### 2.2 Quyền lợi (`ProDialog.FEATURES`)

1. Không ads ở mọi nơi, kể cả khi đổi kênh.
2. Stream không bị ngắt bởi "xem ad".
3. Picture in picture.
4. Mở stream bằng VLC / copy stream URL.
5. Không có lời nhắc nâng cấp.

### 2.3 UI

- **Home** (`HomeScreen.setPro`): free = nút accent "Get Pro" + diamond viền; Pro = nút thường "Pro" + **cùng diamond viền nhưng màu gold** (`PortalStyle.GOLD = #FFC107`).
- **Player** (`TvScreen`): nút ◆ chỉ hiện khi stream đã load và controls đang hiện. Free = diamond viền trắng, Pro = cùng icon, màu gold.
  - Menu ◆: Copy stream URL / Picture in picture / Open in VLC; free có thêm nút "Upgrade to Pro" (→ `ui.pro.open ""`, tính là tự mở).
  - Free bấm một item bị khóa → **upsell dialog** tiêu đề "`<tên item>` is a Pro feature" (thay cho toast cũ).
- **Upsell dialog** (`StbHost.upsell(feature)`): một dialog dùng cho 2 nguồn.

  | Nguồn | Tiêu đề | Go Pro | Cancel/Back |
  |---|---|---|---|
  | Nudge (Unity gọi `showUpsell()`) | Upgrade to Pro | `ui.pro.open "nudge"` | `ui.pro.nudge-dismiss` |
  | Item khóa (`feature != null`) | `<item>` is a Pro feature | `ui.pro.open "feature"` | không emit |

  Không mở khi dialog hoặc Pro sheet đang mở.
- **Pro sheet** (`ProDialog`): nhận JSON `{pro, forever, subscription, activePlan, monthlyPrice, yearlyPrice, monthlyTrial, yearlyTrial, lifetimePrice}`. Status overlay `{busy, title, message}` cho mua/restore.

### 2.4 Bridge native ↔ Unity

Native → Unity (`emit(type, data)`):

| Event | Data | Xử lý C# |
|---|---|---|
| `ui.pro.open` | `""` / `"nudge"` / `"feature"` | `RecordPaywallView(source)` (rỗng → `manual`), retry init IAP, `RefreshPlans`, `ShowProPanel` |
| `ui.pro.buy` | `lifetime` / `monthly` / `yearly` | `RecordCheckoutStarted` **trước** khi mở Play → mua → `RecordCheckoutResult` |
| `ui.pro.restore` | — | `iap.Restore` |
| `ui.pro.nudge-dismiss` | — | `RecordNudgeDismissed` |
| `ui.reward-exit` | — | `DeclineReward` (nút "Not now") |

Unity → native: `showProPanel(json)`, `hideProPanel()`, `setProState(json)` (cập nhật icon Home + player + sheet), `showProStatus(json)`, `showUpsell()`.

## 3. Monetization

### 3.1 Bảy timing (giây, theo thứ tự `MonetizationPolicy.Names`)

| # | Field | Asset (base) | Min | Max | × engaged |
|---|---|---|---|---|---|
| 0 | `interstitialIntervalSeconds` | 60 | 30 | 1800 | 1.5 |
| 1 | `appOpenIntervalSeconds` | 50 | 30 | 3600 | 1.5 |
| 2 | `firstSessionInterstitialDelaySeconds` | 600 | 300 | 3600 | 1 |
| 3 | `minSecondsBetweenAds` | 20 | 10 | 600 | 1.5 |
| 4 | `rewardedViewingSeconds` | 400 | 240 | 7200 | 1.5 |
| 5 | `rewardedSkippedViewingSeconds` | 240 | 120 | 3600 | 1.5 |
| 6 | `upsellIntervalSeconds` | 180 | 90 | 7200 | 0.75 |

Min là sàn an toàn (~50% base), không phải base. Engaged được ít ads hơn và nudge sớm hơn.

### 3.2 Pipeline config

```
Bundled (asset, snapshot 1 lần) → Raw (Remote Config AdMob_Config, hoặc bundled) → Effective
Effective[f] = clamp(raw[f] × (engaged ? EngagedMultiplier[f] : 1), Min[f], Max[f])
```

- Luôn tính từ raw, không bao giờ từ effective cũ (tránh nhân dồn).
- Raw không hợp lệ (NaN/âm/vô hạn) → bundled; bundled cũng hỏng → Min.
- `rewardedViewing = 0` là sentinel **tắt reward prompt**; các field khác = 0 bị nâng lên Min.
- Skipped bị cap ≤ viewing.
- **Bẫy khi port:** `MonetizationController.Instance` phải được chạm **trước** khi Remote Config ghi đè asset (StbEmu làm ở `RemoteConfigController.Awake`). Trong Editor, RC không ghi vào asset.
- Asset vẫn giữ IDs/test flag từ RC; timing trong asset không còn được ai đọc — mọi chỗ đọc phải qua `MonetizationController.Get(field)`.

### 3.3 Segment

| Segment | Điều kiện |
|---|---|
| `pro` | `IsPro` |
| `engaged` | ≥ 1800 s xem **và** ≥ 2 ngày có xem, trong 7 ngày UTC gần nhất |
| `casual` | còn lại |

Hành vi lưu trong `UserBehaviorState` (PlayerPrefs `StbEmu.Monetization.Behavior`, JSON, `version = 1`):

- 7 bucket theo ngày UTC (`day % 7`): `bucketDay[]`, `bucketWatch[]`.
- Mốc thời gian Unix giây: `checkoutStartedAt`, `lastManualPaywallAt`, `previousManualPaywallAt`, `lastNudgeAt`, `lastNudgeDismissAt`, `lastCheckoutCancelAt`.
- `watchSinceNudge`.
- `Normalize(now)`: đồng hồ lùi → kéo mốc tương lai về now, xóa bucket ngày tương lai; JSON hỏng/khác version → state mới.
- Watch chỉ đếm khi **thực sự đang phát** (foreground, playing, không ad/prompt), không phụ thuộc ad sẵn sàng. Lưu mỗi 30 s và mỗi lần ghi sự kiện.

Segment **chỉ** phụ thuộc Pro + thời gian xem. Mở paywall, nudge, checkout không đổi segment; chúng chỉ tác động intent window và lịch nudge.

### 3.4 Intent window (gate tạm thời)

Mở khi:
- checkout bắt đầu trong 24 h, **hoặc**
- ≥ 2 lần mở Pro "manual" trong 7 ngày và lần gần nhất trong 24 h.

`manual` = mọi nguồn trừ `nudge` (gồm nút Get Pro, menu ◆, và Go Pro từ upsell item khóa).

Khi mở, `GateReason = "intent_window"` chặn: interstitial, App Open, reward prompt, nudge. Banner không bị chặn. Lý do: không làm phiền người đang có ý mua, và chặn App Open khi quay lại từ màn thanh toán Play.

### 3.5 Hold / publish

- `SetHold(true)` khi state là Connecting / Portal / RewardPrompt.
- Trong lúc hold, config hay segment mới không áp dụng; rời stream (`SetHold(false)`) mới `Publish`. Điều này tránh ngưỡng thấp hơn bắn ad/prompt giữa stream.
- `Publish` tính lại segment + effective, chỉ log khi có thay đổi.
- Mua thành công → `RequestPublish` (áp ngay nếu không hold).

### 3.6 Ads gate (`GetTimingBlockReason`)

- Banner → không chặn bởi timing/gate.
- Full-screen → trả về `GateReason`; nếu null thì xét timing bằng `policy.Get(...)` (interval, first session delay, min gap).
- Bị chặn → event `ad_show_skipped` kèm reason.

### 3.7 Reward prompt (tùy chọn, không chặn stream)

- `viewing.Tick(dt, streaming && !blocked && rewardedReady, Get(RewardedViewing))`.
- `blocked = ads null || AdsDisabled (Pro) || GateReason != null`. Khi blocked: đóng prompt đang hiện (tính như đủ chu kỳ) và reset `RewardRequired`.
- Kết thúc prompt (`EndRewardPrompt(fullCycle)`): `GrantReward` nếu xem đủ ad, ngược lại `SkipReward(viewing, skipped)`; ẩn prompt, về Portal, mở gate playback, bỏ suppress App Open.

| Kết quả | Hành vi |
|---|---|
| Xem đủ ad | Reset chu kỳ, xem tiếp |
| Đóng ad sớm / "Not now" / Back / ad lỗi | Xem tiếp, nhắc lại sau `rewardedSkipped` |

Không có interstitial "phạt" khi từ chối.

### 3.8 Nudge ở Home

`TryNudge()` chỉ được gọi khi về Home từ Portal **và không có interstitial vừa hiện**. Điều kiện:

- non-Pro, `engaged`, ngoài intent window;
- tối đa 1 lần / ngày UTC;
- 48 h sau Cancel, 72 h sau hủy checkout;
- `watchSinceNudge ≥ Get(Upsell)`.

Khi hiện: ghi `lastNudgeAt`, reset `watchSinceNudge`, log `upsell_shown`. Upsell từ item khóa **không** qua `TryNudge`: không tốn lượt, không reset bộ đếm, Cancel không pause nudge.

### 3.9 Firebase events

| Event | Params |
|---|---|
| `monetization_policy_applied` | `policy_version`, `ab_version`, `segment`, `effective` (7 số, phẩy), `reasons` (`field:invalid_raw` / `zero_raised_to_min` / `clamped`) |
| `segment_changed` | `from`, `to` |
| `paywall_view` | `source`: `manual` / `nudge` / `feature` |
| `upsell_shown` / `upsell_dismissed` | — (chỉ nudge) |
| `checkout_started` | `plan` |
| `checkout_result` | `plan`, `result` (`purchased`/`pending`/`cancelled`/`failed`) |
| `ad_show_skipped` | reason gồm `intent_window` |

String params bị cắt 100 ký tự.

### 3.10 Tier preset (Editor)

- Menu `StbEmu/admod_setting_tier_1/2/3`: asset × `TierMultiplier`, ghi JSON (UTF-8 không BOM) để dán vào Firebase condition theo quốc gia.
- Bị tắt khi Play mode; không sửa asset. Log `base × multiplier = output` và cảnh báo khi runtime sẽ clamp.

| Tier | Interstitial | AppOpen | FirstDelay | MinGap | RewardView | RewardSkip | Upsell |
|---|---|---|---|---|---|---|---|
| 1 | ×10 | ×12 | ×2 | ×6 | ×3 | ×3 | ×5 |
| 2 | ×4 | ×6 | ×1.5 | ×3 | ×2 | ×2 | ×3 |
| 3 | ×1 | ×1 | ×1 | ×1 | ×1 | ×1 | ×1 |

Base hỏng (âm/NaN/upsell < 1/skipped > viewing) → không xuất.

## 4. Checklist port sang Xstream

1. Copy nguyên `MonetizationPolicy.cs` (thuần, đổi namespace) và test section `Monetization()` + harness.
2. Copy `MonetizationController.cs`. Đổi `StateKey`, kiểm tra `FirebaseController.LogEvent`, `InAppPurchaseController.IsPro`, `AdMobSettings.ResourcePath` có tương đương.
3. RemoteConfig: thêm `Timings()`/`SetTimings()` vào DTO; chạm `MonetizationController.Instance` trước khi ghi đè asset; gọi `SetRawConfig` sau fetch thành công.
4. Thay **mọi** chỗ đọc timing từ asset bằng `MonetizationController.Instance.Get(...)` (grep tên 7 field).
5. Ads controller: `GateReason ??` timing check cho full-screen; banner bỏ qua.
6. App flow: `SetHold` theo state stream; `AddWatch` khi đang phát thật; reward prompt optional + `EndRewardPrompt`; bỏ upsell giữa stream và interstitial "reconnect sau Exit"; nudge ở Home.
7. Pro bridge: `RecordPaywallView` với 3 source; `RecordCheckoutStarted` trước khi mở Play; `RecordCheckoutResult` trong callback; `nudge-dismiss`.
8. Native: nút reward "Not now"; `upsell(feature)` thay toast item khóa; icon diamond viền đổi sang gold khi Pro (một drawable, chỉ đổi tint).
9. Editor exporter tier.
10. Kiểm tra:
    - harness pass;
    - compile Android define (`UNITY_ANDROID && !UNITY_EDITOR`);
    - JVM test + build AAR bằng JDK 17 của Unity;
    - trên máy: reward prompt (xem / Not now / lỗi), đổi kênh, về Home, mua rồi quay lại app (không App Open), nudge (hạ `rewardedViewingSeconds`/`upsellIntervalSeconds` để thử), item khóa → upsell.

## 5. Chưa làm (mở cho Xstream quyết định)

- Event `playback_session_summary`; event khi hiện upsell của item khóa (hiện chỉ đo khi bấm Go Pro).
- Gate riêng cho banner khi Pro sheet đang mở.
- Nudge đầu tiên đếm watch từ lúc cài, nên user vừa thành engaged sẽ được nudge ở lần về Home kế tiếp.
- Menu cũ "Export AdMob Remote Config JSON" trùng tier 3.
