# Plan: Home native Android — tab Profile / Setting, popup Add/Edit

## Hiện trạng 2026-10-10

Đối chiếu code hiện tại; dùng cùng [README](../../../README.md) trước khi sửa luồng này.

Đã triển khai, không còn ở trạng thái “chưa sửa runtime”.

- Android HomeScreen có tab Profile/Setting, ProfileEditorDialog cho Add/Edit và dialog xác nhận Delete; Editor giữ CanvasProfileUi.
- Theme mặc định là Dark nếu chưa lưu lựa chọn; Setting vẫn cho chọn System/Light/Dark và giữ lựa chọn đã lưu qua các lần mở app.
- AppFlow điều phối qua IProfileUi. ProfileManager/ProfileForm/JsonProfileRepository chịu trách nhiệm validation, cập nhật dữ liệu và persistence ở C#; Java chỉ render và giữ draft.
- Danh sách rỗng có Add profile, không tự mở form. Connect ẩn home; Home từ portal về Profiles.
- App đã có ProDialog, purchase status, Restore Purchase và sự kiện ui.pro.* qua AndroidProfileUi. Giới hạn “Setting chỉ tên/phiên bản, chưa có billing” của phạm vi ban đầu không còn mô tả toàn app.
- Pro state/giá cập nhật từ Initialized, PlansChanged và OwnershipChanged. Restore chờ kết quả purchases fetch và khôi phục cả lifetime lẫn subscription.

Khi sửa profile, giữ các dữ liệu Compatibility/Playback/identity không liên quan; không thay profile bằng draft thiếu field. Đọc [App startup](../../AppStartup.md) và [Pro plans](../../ProSubscriptionPlans.md). Kết quả kiểm chứng cũ ở cuối là lịch sử; cần smoke phần UI mới thay đổi.

## Bản kế hoạch gốc và lịch sử triển khai

> Phần dưới giữ để tra cứu. Khi có khác biệt, dùng hiện trạng ở đầu tài liệu và README; không thực thi lại checklist hoặc chỉ dẫn công cụ/agent cũ mặc định.

Ngày: 2026-10-07. Trạng thái: plan; chưa sửa runtime.

## Goal

Trên Android, màn hình quản lý profile chuyển từ Unity Canvas sang Android Views: home hai tab **Profile** / **Setting**,
popup Add/Edit và dialog xác nhận xoá. Unity Editor giữ Canvas hiện có. Loading, Connecting/Error (StatusScreen) và
playback nằm ngoài phạm vi.

- Tab ở trên, ngay dưới tiêu đề. Tab mặc định: Profile. Chuyển tab giữ vị trí cuộn (chỉ toggle visibility).
- Setting giai đoạn đầu: tên app + phiên bản (C# đưa sang, không đọc version AAR).
- Danh sách rỗng: empty state + nút Add profile; vẫn vào được Setting. Bỏ hành vi tự mở form khi rỗng.
- Delete: dialog xác nhận có tên profile, Cancel mặc định. Thay cho hai lần bấm hiện tại.
- Connect: ẩn home + popup trước khi sang Connecting; về home hiện lại tab Profile.

```text
┌─────────────────────────────────────────────┐
│ StbEmu                                      │
│ [ Profile ]              [ Setting ]        │
├─────────────────────────────────────────────┤
│ Profile:                         [+ Add]    │
│ Tên profile                                 │
│ Host portal · MAC        [Edit] [Delete]    │
│ ...                                         │
└─────────────────────────────────────────────┘

Add/Edit → AlertDialog cuộn được → Save / Cancel
Delete   → AlertDialog xác nhận → Delete / Cancel
Setting  → tên app, phiên bản
```

## Architecture rules

- Java render và giữ draft hiển thị. C# sở hữu dữ liệu, validation, persistence, điều hướng. Không repository Java,
  không đổi schema `profiles.json`.
- `AppFlow` nói với UI profile qua một interface `IProfileUi` thay cho hai field `list`/`edit`. Editor: `CanvasProfileUi`
  bọc `ProfileListScreen` + `ProfileEditScreen` hiện có (không sửa hai class này). Android: `AndroidProfileUi` qua JNI.
  Chọn impl bằng `#if UNITY_ANDROID && !UNITY_EDITOR` trong `Start()` (`AppFlow.cs:58`).
- Tab là state native thuần. C# không có state Settings, không có event tab. Điều kiện ads retry (`state == Profiles`)
  giữ nguyên vì cả hai tab đều là `State.Profiles`.
- Không editorId / home generation / operationId. Guard stale = `state` của AppFlow (như hiện nay) + `editing != null`
  trong `AndroidProfileUi`. `ProfileManager.busy` đã chặn mutation chồng; native disable Save sau khi bấm cho tới khi
  nhận errors hoặc home.
- Save payload = đúng 10 field mà `ProfileEditScreen.Save()` đang set (name, portalUrl, mac, serialNumber, model,
  sendDeviceId, deviceId, deviceId2, signature, forceLoginPortal). C# patch lên deep copy của profile gốc
  (copy pattern `ProfileEditScreen.Show`), nên Compatibility/Playback/DeviceIdMode và field tương lai giữ nguyên.
  Add = `new Profile()` (Model MAG250, ForceLoginPortal true, SendDeviceId true như form hiện tại); MAC rỗng để
  `ProfileManager.AddAsync` sinh.
- Generate MAC và Device ID từ MAC làm tại Java (2 hàm static ~10 dòng, cùng vector test với `ProfileTests.cs:49–52`).
  Không round-trip qua C#. Validate/duplicate MAC vẫn chỉ ở C#.
- Back: `HomeScreen` chỉ consume Back khi đang ở tab Setting (chuyển về Profile). Không consume → rơi xuống Unity →
  `AppFlow.Update` (`Profiles` → `Application.Quit()`) như hiện nay. AlertDialog tự xử lý Back (IME đóng trước, rồi
  dismiss → `ui.profile.cancel`). Không thêm event back. Mỗi lần Back chỉ một nơi xử lý.
- Home root là child của `nativeLayer` (pattern `rewardPrompt`, `StbHost.java:402–410`), nên `release()` và
  `applyPlaybackGate` tự gỡ/ẩn. AlertDialog có window riêng: `hideProfileHome()` và `release()` phải dismiss nó.
  Không cần xử lý Activity recreate: manifest Unity có `configChanges` cho orientation; chỉ cần layout theo
  `onSizeChanged` như `TvScreen`.
- Mọi mutation view qua `ui.post(...)`; event về Unity qua `Listener.onEvent` hiện có (đã repost lên Unity thread
  trong `AndroidStbHost`). `AndroidProfileUi` tự subscribe `host.NativeEvent` và lọc `ui.profile.*`; không sửa router
  `OnNativeEvent` (`AppFlow.cs:376`) vì type lạ đã rơi qua.
- Style: `PortalStyle.surface/interactive/button`, `dp()`; RecyclerView theo `ChannelPanel`; dialog theo
  `StreamInfoDialog.java:41–63`; ẩn IME theo `TvScreen.java:511`. Không Material/Compose/WebView.

### JNI (Java ↔ `AndroidStbHost`)

| Java / C# wrapper | Payload | Ý nghĩa |
|---|---|---|
| `showProfileHome(String json)` / `ShowProfileHome` | `{app:{name,version}, profiles:[{id,name,host,mac}], error?}` | Render list + Setting, dismiss editor nếu đang mở; `error` → Toast |
| `hideProfileHome()` / `HideProfileHome` | — | Ẩn root, dismiss dialog, ẩn IME |
| `showProfileEditor(String json)` / `ShowProfileEditor` | `{title, name, portalUrl, mac, serialNumber, model, sendDeviceId, deviceId, deviceId2, signature, forceLoginPortal}` | Mở popup Add/Edit |
| `setProfileEditorErrors(String json)` / `SetProfileEditorErrors` | `["..."]` | Hiện lỗi trong popup, enable lại Save, cuộn tới lỗi |

### Events (Java → C#, qua `emit`)

| Event | Data |
|---|---|
| `ui.profile.add` | `""` |
| `ui.profile.edit`, `ui.profile.connect`, `ui.profile.delete` | profileId (delete chỉ sau khi xác nhận) |
| `ui.profile.save` | JSON 10 field (string/bool), đã trim |
| `ui.profile.cancel` | `""` (Cancel, Back; `setCanceledOnTouchOutside(false)`) |

JSON hai phía bằng `org.json` / Newtonsoft, không ghép chuỗi. C# tra id trong `profiles.Profiles` hiện tại; id lạ bỏ qua.

## Files

CREATE
- `Assets/App/UI/IProfileUi.cs` — `Show(IReadOnlyList<Profile>)`, `ShowEdit(Profile)`, `ShowErrors(IEnumerable<string>)`,
  `Hide()`; events `Add`, `Edit(Profile)`, `Delete(Profile)`, `Selected(Profile)`, `Saved(Profile)`, `Cancelled`.
- `Assets/App/UI/CanvasProfileUi.cs` — bọc `ProfileListScreen` + `ProfileEditScreen`, nối event 1-1.
- `Assets/App/UI/ProfileForm.cs` — `static Profile Copy(Profile)` (JSON round-trip) và
  `static void Apply(Profile target, JObject form)` cho 10 field. Dùng bởi `AndroidProfileUi`; test được trong Editor.
- `Assets/App/Tests/EditMode/ProfileFormTests.cs` — Apply giữ Compatibility/Playback/DeviceIdMode; Unicode/dấu nháy
  round-trip; field thiếu trong JSON không ghi đè; bool parse đúng.
- `Assets/App/Infrastructure/Android/AndroidProfileUi.cs` — `#if UNITY_ANDROID && !UNITY_EDITOR`; giữ `current`
  list và `editing`; parse `ui.profile.*`; gọi 4 wrapper.
- `Native/StbHost/src/main/java/com/stbemu/host/HomeScreen.java` — root opaque MATCH_PARENT, tab bar (LinearLayout 2
  Button, tách thành inner class để đổi vị trí sau), ProfilePanel (RecyclerView + Add + empty state), Setting panel
  (2 TextView), delete-confirm AlertDialog, `onKey` Back.
- `Native/StbHost/src/main/java/com/stbemu/host/ProfileEditorDialog.java` — AlertDialog + ScrollView form + footer
  Save/Cancel custom (không auto-dismiss); `static String generateMac()`, `static String deviceIdFromMac(String)`.
- `Native/StbHost/src/test/java/com/stbemu/host/ProfileEditorDialogTest.java` — `deviceIdFromMac("00:1A:79:12:34:56")`
  và `("001a79123456")` = `667094E7E8FF0347F6EC27A8E8115BE76E2AA9CD5C8F13C6FA00BCEDFAC02B41`; `generateMac()` khớp
  `^00:1A:79(:[0-9A-F]{2}){3}$`.

MODIFY
- `Assets/App/UI/AppFlow.cs` — field `IProfileUi ui` thay `list`/`edit`; `Start` chọn impl; `ShowProfiles` bỏ auto
  `ShowEdit(null)` khi rỗng; `ShowEdit`/`HideAll`/`SaveAsync` dùng `ui`; `DeleteAsync` thêm try/catch → log +
  `ShowProfiles()` (row còn nguyên nhờ rollback; `ponytail:` chưa có toast lỗi xoá, thêm khi cần).
- `Assets/App/Infrastructure/Android/AndroidStbHost.cs` — 4 wrapper.
- `Native/StbHost/src/main/java/com/stbemu/host/StbHost.java` — field `HomeScreen home`; tạo trong `attach()`, add vào
  `nativeLayer` (GONE); 4 public method `ui.post`; `release()` dismiss dialog của home.
- `Docs/AppStartup.md` — Android dùng home native, Editor dùng Canvas; list rỗng không tự mở form.
- `Assets/Plugins/Android/StbHost.aar` — rebuild bằng `copyToUnity`.

## Steps

1. `ProfileForm` + `ProfileFormTests` → verify: EditMode tests xanh (Editor đóng, chạy batch hoặc Test Runner).
2. `IProfileUi`, `CanvasProfileUi`; `AppFlow` chuyển sang `ui`, bỏ auto-mở form → verify: Play Main trong Editor,
   add/edit/delete/cancel như cũ, list rỗng hiện nút Add.
3. `HomeScreen`, `ProfileEditorDialog`, JVM test; wiring trong `StbHost.java` → verify:
   `Native/StbHost> gradlew testReleaseUnitTest copyToUnity`, AAR mới trong `Assets/Plugins/Android`.
4. `AndroidStbHost` wrapper + `AndroidProfileUi`; `AppFlow` chọn impl Android → verify: compile với define Android.
5. `Docs/AppStartup.md`; build dev APK (`StbEmu/Build And Run Android (Dev)`), smoke theo Verify.

## Verify

- `git status --short` chỉ có file trong `## Files`; grep `ProfileListScreen|ProfileEditScreen` trong `AppFlow.cs`
  chỉ còn trong nhánh Editor qua `CanvasProfileUi`; mỗi JNI method có đủ cặp Java/C#; mỗi event có đúng một nơi phát
  và một nơi nhận.
- Editor: Play Loading → Main; Play Main trực tiếp; add/edit/cancel/delete/cuộn.
- Android: cold start list rỗng và nhiều row; tab giữ scroll; add/edit với URL sai, MAC trùng, Unicode; Generate/
  From MAC; Save disable trong lúc lưu; Cancel/Back/tap ngoài không mất draft/không lưu; bàn phím không che footer;
  xoay màn hình khi dialog mở; delete confirm; connect → home ẩn, Connecting hiện, cancel/error/retry quay về đúng UI;
  Home từ portal hiện lại tab Profile; app open ad khi resume không để home lấy focus; không có hai root home.
- Dữ liệu cũ: `profiles.json` có Compatibility/Playback/DeviceIdMode không đổi sau edit field khác; restart đọc được.
- Release/R8: `consumer-rules.pro` đã keep `com.stbemu.host.**`, chỉ cần smoke bản release.

## Do NOT

- Không deserialize payload native thành Profile rồi replace; không copy validator sang Java; không ghi file từ Java.
- Không sửa luồng portal/player/reward/ads; không bỏ Canvas khi StatusScreen còn dùng; không dùng state Edit tính
  watch time.
- Không auto-dismiss dialog khi Save; không sinh lại identity khi mở Edit; không log đầy đủ identity.
- Không thêm dependency, network request, settings nghiệp vụ, Material/Compose.

## Revisions

- 2026-10-07: viết lần đầu (tab trên, Setting = tên app/phiên bản, được user xác nhận).
- 2026-10-07: sửa lại sau khi đối chiếu code. Gộp 4 file Java → 2, 4 file C# → 3 (+`ProfileForm` để test được);
  5 JNI → 4; 8 event → 5. Bỏ editorId/generation/operationId (dùng `state` + `ProfileManager.busy`). Bỏ tab state và
  `ui.home.tab`/`ui.home.back` bên C# (Back rơi xuống `AppFlow.Update` như hiện nay). MAC/Device ID tính tại Java với
  vector test chung thay vì round-trip. Bỏ giả định Activity recreate. `Start()` ở dòng 58, không phải 62.

## Outcome notes

2026-10-07, triển khai đủ 5 bước theo `## Files`.

- JVM: `gradlew test copyToUnity --offline` (JAVA_HOME = OpenJDK của Unity 6000.6.3f1) pass, `ProfileEditorDialogTest`
  3/3; AAR mới tại `Assets/Plugins/Android/StbHost.aar`.
- EditMode batchmode: 111/111 pass, `ProfileFormTests` 4/4, không lỗi compile.
- Android dev build batchmode (`AndroidDevBuild.Build`): dừng ở "Checking prerequisites" vì batchmode không có mật khẩu
  keystore (không phải lỗi code). Thay bằng compile `StbEmu.App` với define player Android (rsp của Bee + Roslyn của
  Unity, `UNITY_ANDROID`, không `UNITY_EDITOR`): 0 lỗi, assembly chứa `AndroidProfileUi`. APK dev cần build từ Editor.
- Chưa smoke trên thiết bị thật (home native, popup, delete confirm, Back, IME, xoay, connect/home, ads). Người dùng chạy
  theo mục Verify.
- Sửa ngoài plan khi code: listener dismiss của AlertDialog chạy async nên chỉ xoá tham chiếu khi `dialog == d`
  (tránh mất tham chiếu popup mới mở ngay sau khi đóng popup cũ).
