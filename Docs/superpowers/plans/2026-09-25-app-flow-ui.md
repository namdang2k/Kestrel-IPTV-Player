# App flow & UI (mobile) Implementation Plan

## Hiện trạng 2026-10-10

Đối chiếu code hiện tại; dùng cùng [README](../../../README.md) trước khi sửa luồng này.

Đã triển khai và đã thay đổi so với thiết kế ban đầu.

- Loading tải Main; AppFlow nằm trong scene Main và tạo AppCanvas từ prefab, không tự bootstrap UI toàn cục.
- Android dùng HomeScreen/ProfileEditorDialog qua AndroidProfileUi; Editor dùng CanvasProfileUi. StatusScreen điều phối trạng thái Connecting/Error, Android có native loading status.
- Profile rỗng hiển thị Add; Delete dùng dialog xác nhận, không còn thao tác bấm hai lần.
- ConnectAsync và ReconnectPortalAsync có timeout 180 giây ở C#; watchdog native có thể báo lỗi sớm hơn. Không lấy timeout 30 giây của bản gốc làm giá trị hiện hành.
- Connect mới dừng player; reconnect portal giữ stream. Danh sách kênh native, ads/reward/upsell và Pro/Restore đã được tích hợp.
- Service ads và IAP khởi tạo độc lập. Mở app/resume chỉ query quyền SUBS; Restore vẫn lấy cả subscription và Pro Forever.
- JNI portal/player đi qua AndroidStbHost; subscription base-plan có bridge riêng SubscriptionPlans.java. Không áp quy tắc “một JNI entry point” cho toàn bộ app.

Điểm sửa chính: AppFlow.cs, AndroidProfileUi.cs, CanvasProfileUi.cs, StatusScreen.cs và Native/StbHost. Đọc thêm [App startup](../../AppStartup.md) và [plan native profile](2026-10-07-native-profile-settings.vi.md).

## Bản kế hoạch gốc và lịch sử triển khai

> Phần dưới giữ để tra cứu. Khi có khác biệt, dùng hiện trạng ở đầu tài liệu và README; không thực thi lại checklist hoặc chỉ dẫn công cụ/agent cũ mặc định.

> Executed natively in-session (user: "triển khai đi"). Steps use checkbox syntax for tracking.

**Goal:** Mobile shell app: profile list/edit, connect with loading/error, portal with native overlay + virtual remote, Play → ExoPlayer.

**Architecture:** `AppFlow` (self-bootstrapping MonoBehaviour) owns ProfileManager + AndroidStbHost/PortalView/Player + PortalController and swaps code-built uGUI screens. Portal-time controls are native Android views above the WebView.

**Tech Stack:** Unity 6000.6.3f1, uGUI (legacy `Text`/`InputField`, OS dynamic font for Vietnamese), UniTask, Newtonsoft, Java StbHost AAR (Media3 1.11.1, webkit 1.17.1).

**Spec:** `Docs/superpowers/specs/2026-09-25-app-flow-ui-design.md`

## Global Constraints
- Android phone/tablet, touch only; no TV/D-pad, no EnhancedScroller, no Stalker API calls from C#.
- Connect timeout 30 s, waits for `bridge.hello`.
- One JNI entry point: `AndroidStbHost`.
- Deviation from spec §3.2: legacy uGUI `Text`/`InputField` instead of TMP (TMP default font lacks Vietnamese glyphs; legacy font falls back to OS fonts). Connecting + Error share one `StatusScreen`. Delete-confirm = tap 🗑 twice.

## Review Focus
- Play command with no URL / unknown prefix → no playback, `player.error` to portal (PlayCommand + PortalController tests).
- Portal message arriving after Home/Refresh (old session) → ignored (existing sessionId check; PortalController unsubscribes on Dispose — test).
- Save with invalid URL/duplicate MAC → form stays, errors listed (ProfileValidator, existing tests).
- Unknown `portal.error` text → generic message with raw code (ErrorText test).
- Back while Connecting → cancels open, returns to list (manual device checklist).

---

### Task 1: PlayCommand
- Create `Assets/App/Domain/Portal/PlayCommand.cs`, test `Assets/App/Tests/EditMode/PlayCommandTests.cs`.
- Produces: `static bool PlayCommand.TryParseUrl(string command, out string url)`.
- [ ] tests (prefixes ffmpeg/ffrt/ffrt2/ffrt3/auto, bare URL, extra spaces, no URL, scheme javascript:) → implement → compile.

### Task 2: Player + host wrappers
- Create `Assets/App/Domain/Portal/IPlayer.cs` (`IPlayer`, `PlayerState`), `Assets/App/Infrastructure/Android/AndroidPlayer.cs`.
- Modify `AndroidStbHost.cs`: `Play(url, headersJson)`, `SetPaused(bool)`, `SetVolume(float)`, `Stop()`, `SendKey(int)`, `SetOverlayVisible(bool)`.

### Task 3: PortalController
- Create `Assets/App/Domain/Portal/PortalController.cs`, test `PortalControllerTests.cs` with FakePortalView/FakePlayer.
- Consumes IPortalView, IPlayer, PlayCommand. Produces `PortalController(IPortalView, IPlayer, string userAgent)`, `Dispose()`.

### Task 4: Native overlay, remote, keys
- Modify `StbHost.java`: `sendKey`, WebView back → DEL, overlay (⟳ ⌂ 🎮) + remote row as native views, events `ui.refresh`, `ui.home`; `setOverlayVisible`. Rebuild AAR.

### Task 5: UI screens (code-built)
- Create `Assets/App/UI/UiKit.cs`, `ProfileListScreen.cs`, `ProfileEditScreen.cs`, `StatusScreen.cs`, `ErrorText.cs` (+ `ErrorTextTests.cs`).

### Task 6: AppFlow + cleanup
- Create `Assets/App/UI/AppFlow.cs` (`[RuntimeInitializeOnLoadMethod]` bootstrap).
- Delete `Assets/App/Dev/PortalSmokeTest.cs`, `Assets/Prefabs/PortalTest.prefab`. User removes PortalTest/Canvas instances from SampleScene.
- Verify: csc compile (editor + android defines), facade node check, EditMode tests when Editor is closed, device checklist (spec §1).
