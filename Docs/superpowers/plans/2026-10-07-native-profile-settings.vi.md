# Plan: Profile và popup Add/Edit bằng Android native, hai tab Profile / Setting

Ngày: 2026-10-07. Trạng thái: plan triển khai; chưa sửa runtime.

## 1. Mục tiêu và quyết định UI

Chuyển màn hình quản lý profile trên Android từ Unity Canvas sang Android Views, bao gồm popup thêm/sửa và xác nhận xoá. Màn hình chính có hai tab **Profile** và **Setting**.

- Mặc định đặt tab **ở trên**, ngay dưới tiêu đề. Tách thanh tab thành một component để có thể chuyển xuống dưới mà không thay đổi bridge hoặc nghiệp vụ.
- Ưu tiên điện thoại/tablet và cảm ứng theo scope hiện tại. Dùng focus và controls chuẩn Android; chưa mở rộng thành dự án Android TV riêng.
- Tab mặc định khi mở app: Profile. Chuyển tab giữ vị trí cuộn danh sách trong phiên.
- Setting giai đoạn đầu hiển thị tên app và phiên bản, đã được người dùng xác nhận. Các tuỳ chọn riêng của profile vẫn nằm trong popup edit.
- Khi chưa có profile: hiển thị empty state và nút **Add profile**, vẫn truy cập được Setting. Bỏ hành vi tự mở form liên tục khi danh sách rỗng.
- Khi connect: ẩn home native và popup trước khi chuyển sang Connecting/Portal. Khi về home: hiện lại tab Profile.
- Unity Editor tiếp tục dùng UI Canvas để phát triển và kiểm tra nghiệp vụ. Loading scene, Connecting/Error và UI playback hiện có nằm ngoài phần chuyển đổi này.

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

Add/Edit → popup native cuộn được → Save / Cancel
Delete   → popup xác nhận → Delete / Cancel
Setting  → thông tin app và phiên bản (scope ban đầu)
```

## 2. Phase 0 — Discovery và nguồn cần đọc

Các nguồn dưới đây được đối chiếu với code hiện tại; spec cũ chỉ cung cấp bối cảnh, không thay thế hành vi runtime.

| Nguồn | Pattern/API cần dùng |
|---|---|
| `Assets/App/UI/AppFlow.cs:62` | `Start()`: hiện đang khởi tạo Canvas trước native host; phải tách khởi tạo presenter theo platform |
| `Assets/App/UI/AppFlow.cs:195` | `ShowProfiles`, `ShowEdit`, `HideAll`, `SaveAsync`, `DeleteAsync`: điểm tích hợp UI mới |
| `Assets/App/UI/AppFlow.cs:376` | `OnNativeEvent`: router các event `ui.*` |
| `Assets/App/UI/AppFlow.cs:632` | `CloseAndShow`: teardown portal/player/quảng cáo trước khi về home |
| `Assets/App/UI/ProfileEditScreen.cs:61` | Deep-copy profile, bind form; `Save()` chỉ cập nhật các trường được edit |
| `Assets/App/UI/ProfileListScreen.cs:36` | Selected/Edit/Delete/Add; hiện xoá bằng hai lần bấm, thay bằng dialog xác nhận |
| `Assets/App/Domain/Profiles/ProfileManager.cs:43` | `AddAsync(Profile, CancellationToken)`, `UpdateAsync`, `RemoveAsync`; sinh/normalize MAC, validate và rollback khi lưu lỗi |
| `Assets/App/Domain/Profiles/ProfileValidator.cs:9` | `Validate(Profile, IEnumerable<Profile>)`: tên, URL HTTP(S), MAC hợp lệ và không trùng |
| `Assets/App/Domain/Profiles/Profile.cs:14` | Model đầy đủ; còn Compatibility/Playback và DeviceIdMode ngoài các trường form |
| `Assets/App/Infrastructure/Storage/JsonProfileRepository.cs:25` | camelCase + string enums; lưu `profiles.json`, `.tmp`, `.bak` |
| `Assets/App/Infrastructure/Android/AndroidStbHost.cs:21` | JNI qua `host.Call(...)`, callback `Listener.onEvent(string type, string data)` repost về Unity thread |
| `Native/StbHost/src/main/java/com/stbemu/host/StbHost.java:272` | `attach()`: native layer gắn vào `android.R.id.content`, pattern view như `TvScreen` |
| `Native/StbHost/src/main/java/com/stbemu/host/TvScreen.java` và `PortalStyle.java` | Native Views, style, density và lifecycle theo convention dự án |
| `Native/StbHost/src/main/java/com/stbemu/host/StreamInfoDialog.java:41` | ScrollView + `AlertDialog.Builder(activity).setView(...).create()`, dismiss cleanup |
| `Native/StbHost/build.gradle` | Java 17, minSdk 26, compileSdk 36; đã có RecyclerView; `copyToUnity` tạo AAR |
| `Native/StbHost/consumer-rules.pro` | Giữ `com.stbemu.host.**` để JNI không bị R8 loại bỏ |
| `Docs/AppStartup.md` | Loading → Main, nguồn prefab, hành vi Editor cần duy trì |

**Allowed APIs hiện có:** các API trong bảng trên, `MacAddress.Generate()`, `MacAddress.Normalize(string)`, `MacAddress.ToDeviceId(string)` và listener của StbHost. Các API home/editor ở phase 1 là API **đề xuất cần tạo**, chưa tồn tại.

**Docs Android chính thức:** đọc [custom dialogs](https://developer.android.com/develop/ui/views/components/dialogs?hl=en) cho `AlertDialog.Builder.setView`, và [keyboard visibility](https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility) cho focus/IME/window behavior. Pattern popup copy từ `StreamInfoDialog.java:41–63`; pattern focus/ẩn keyboard copy từ `TvScreen.java:371–389`.

**Kết quả discovery cần chốt trước code:** đọc pattern attach/dispose, kiểm tra lifecycle khi Activity/scene thay đổi, xác minh API Android cho dialog/IME/insets từ docs chính thức. Không giả định có Material Components, Compose hay Activity riêng: build hiện tại dùng Java Views.

## 3. Kiến trúc và contract đề xuất

Java render UI và giữ draft hiển thị; C# sở hữu dữ liệu, validation, persistence và điều hướng portal. Dùng native layer/Listener của `StbHost` hiện tại.

### Thành phần dự kiến

- Java: `HomeScreen.java` (root + tabs), `ProfilePanel.java` (RecyclerView), `ProfileEditorDialog.java` (form), `SettingsPanel.java` (thông tin app).
- C#: `IProfileHomeView.cs` (contract presenter), `CanvasProfileHomeView.cs` (adapter UI Editor), `AndroidProfileHomeView.cs` (adapter JNI), `ProfileUiProtocol.cs` (DTO/parser/patch draft).
- Mở rộng `AndroidStbHost.cs`, `StbHost.java` và `AppFlow.cs`; cập nhật `Docs/AppStartup.md` sau triển khai.
- Không tạo repository Java thứ hai. Không đổi schema profiles.json chỉ vì thay UI.

### JNI methods cần tạo

| Java method / C# wrapper | Ý nghĩa |
|---|---|
| `showProfileHome(String snapshotJson)` / `ShowProfileHome(string)` | Render danh sách đã load, tab và thông tin app |
| `hideProfileHome()` / `HideProfileHome()` | Ẩn home và đóng popup khi đổi flow |
| `showProfileEditor(String editorJson)` / `ShowProfileEditor(string)` | Mở draft add/edit có editorId |
| `setProfileEditorResult(String resultJson)` / `SetProfileEditorResult(string)` | Trả busy/errors hoặc đóng khi lưu thành công |
| `setProfileHomeResult(String resultJson)` / `SetProfileHomeResult(string)` | Kết quả xoá/lỗi và danh sách cập nhật |

Mọi mutation View chạy trên Android UI thread qua pattern `ui.post(...)`. Callback quay về Unity thread bằng listener hiện có.

### Native events đề xuất

| Event | Payload |
|---|---|
| `ui.profile.add` | home generation |
| `ui.profile.edit`, `ui.profile.connect`, `ui.profile.delete` | home generation + profileId; delete chỉ gửi sau xác nhận |
| `ui.profile.save` | editorId + operationId + mode + profileId + các trường form |
| `ui.profile.cancel` | editorId |
| `ui.profile.generate-mac`, `ui.profile.device-id-from-mac` | editorId + MAC draft cần tính; trả kết quả qua editor result |
| `ui.home.tab` | `profile` hoặc `setting` + home generation |
| `ui.home.back` | home generation; chỉ gửi khi IME/popup không xử lý Back |

Payload có `version: 1`; serialize bằng JSON library, không ghép chuỗi thủ công. Native nhận snapshot list rút gọn (id/name/host/mac); editor chỉ gửi whitelist field đã sửa. C# lấy bản sao profile gốc rồi patch các field đó để giữ Compatibility, Playback, DeviceIdMode và field tương lai.

C# không tin mode/id do UI quyết định: add chỉ hợp lệ với draft add; edit/delete/connect phải lookup id hiện có. Chặn payload hỏng, enum/tab lạ, callback của home/editor cũ. Serialize các mutation lưu/xoá; khoá Save/Delete khi đang xử lý và dùng operationId để chống bấm lặp. Lỗi trả về đúng draft, giữ nguyên nội dung nhập.

## 4. Phase 1 — Presenter và protocol

**Việc thực hiện**

- [ ] Đọc các nguồn `AppFlow`, `AndroidStbHost`, `ProfileEditScreen`, `ProfileManager` trong phase 0.
- [ ] Tạo contract presenter với events Add/Edit/Delete/Connect/Save/Cancel, show/hide, errors và busy. Adapter Canvas nối lại event có sẵn.
- [ ] Tạo DTO JSON, snapshot danh sách, editor draft + whitelist patch; tạo editorId/home generation và kiểm tra callback hợp lệ.
- [ ] Xác định default add giống form hiện tại: model MAG250, SendDeviceId bật, ForceLoginPortal bật; MAC rỗng để manager sinh lúc add nếu người dùng không bấm Generate.
- [ ] Tạo JNI methods/event router theo bảng contract; định nghĩa response cho generate MAC / Device ID và error.

**Kiểm tra:** EditMode tests cho round-trip ký tự Unicode/dấu nháy, giữ field chưa edit, draft cancel không đổi model, payload sai/stale và submit lặp. Dùng lại `ProfileTests.cs` cho normalization, duplicate MAC và rollback.

**Guard:** không deserialize payload native thành toàn bộ profile rồi replace trực tiếp; không sao chép logic validator/MAC sang Java; không giả định wrapper JNI đã tồn tại.

## 5. Phase 2 — Home native và hai tab

**Việc thực hiện**

- [ ] Đọc và copy pattern native layer + callback từ `StbHost.attach()`, view/style từ `TvScreen`, `PortalStyle`, RecyclerView từ `ChannelPanel`.
- [ ] Tạo HomeScreen có nền opaque, thanh tab trên, ProfilePanel và SettingsPanel; chỉ một tab active.
- [ ] Profile row: tên, host portal, MAC, Connect/Edit/Delete; Add rõ ràng, danh sách cuộn và empty state.
- [ ] Delete dùng dialog native có tên profile, Cancel mặc định; chỉ gửi request xoá khi xác nhận. Lỗi storage hiện thông báo, giữ row.
- [ ] Setting hiển thị tên app và phiên bản do C# cung cấp, thay vì đọc version của library AAR.
- [ ] Gắn root home vào nativeLayer, bổ sung show/hide/dispose; xử lý system bars, rotation và focus.

**Kiểm tra:** thiết bị portrait/landscape, danh sách rỗng/nhiều row, tab giữ scroll, touch target tối thiểu 48dp; home không che portal sau connect, native root không bị nhân đôi.

**Guard:** không thêm Material/Compose chỉ để làm tab; không thêm network request hay settings nghiệp vụ chưa được yêu cầu; không render home bằng WebView.

## 6. Phase 3 — Popup Add/Edit native

**Việc thực hiện**

- [ ] Đọc `ProfileEditScreen.cs:61` và `Save()/DeviceIdFromMac()` để copy chính xác mapping field và default.
- [ ] Đọc docs dialogs/keyboard ở phase 0, copy pattern `StreamInfoDialog.java:41–63`; tạo popup Android với tiêu đề Add profile / Edit profile, nội dung ScrollView và footer Save / Cancel luôn truy cập được. Điện thoại nhỏ có thể dùng dialog gần toàn màn hình; landscape giới hạn chiều rộng hợp lý.
- [ ] Đủ field: Name, Portal URL, MAC + Generate, Serial, Model, Send Device ID, Device ID + From MAC, Device ID 2, Signature, Force Login.
- [ ] Input URL phù hợp bàn phím URL; MAC/device identity không autocorrect. Các nút tính MAC/Device ID nhận kết quả C# và chỉ áp dụng nếu draft chưa thay đổi kể từ request.
- [ ] Custom listener Save để dialog không tự dismiss trước khi C# lưu xong. Trong khi save, disable Save và chặn Cancel/Back gây mất kết quả; hoàn tất thì đóng hoặc mở lại controls cùng lỗi.
- [ ] Validation/storage error hiển thị trong popup; giữ dữ liệu và cuộn đến lỗi. Cancel không lưu. Tap ngoài dialog không làm mất draft.
- [ ] IME/insets: field focus và footer không bị bàn phím che; đóng dialog phải ẩn IME và trả focus về row/Add.
- [ ] Back: khi bàn phím mở, đóng bàn phím trước; tiếp theo đóng popup; ở home chuyển Setting về Profile, từ Profile thực hiện chính sách thoát của AppFlow. Chỉ một nơi xử lý từng lần Back.

**Kiểm tra:** add/edit/save/cancel, URL sai, MAC trùng/sai, Generate/From MAC, lỗi ghi file, Unicode, bàn phím và rotation; xác minh ForceLogin và SendDeviceId giữ đúng giá trị sau restart.

**Guard:** không auto-dismiss khi Save; không sinh lại identity khi mở edit; không ghi file trực tiếp từ Java; không log đầy đủ profile/device identity.

## 7. Phase 4 — Tích hợp AppFlow và lifecycle

**Việc thực hiện**

- [ ] Đọc `Start`, `HideAll`, `ShowProfiles`, `SaveAsync`, `DeleteAsync`, `OnNativeEvent`, `CloseAndShow`, `OnDestroy` trong AppFlow.
- [ ] Android chọn native presenter, Editor chọn Canvas adapter. Android vẫn tạo StatusScreen nếu còn dùng Canvas cho Connecting/Error, nhưng không bind/render ProfileListScreen/ProfileEditScreen.
- [ ] Route event profile/home trước guard Portal-only đang có trong `OnNativeEvent`; kiểm tra state/generation riêng cho từng event.
- [ ] Sau LoadAsync, render home kể cả danh sách rỗng. Init profile lỗi phải được báo và không âm thầm giả vờ dữ liệu đã load thành công.
- [ ] Add/Edit mở popup trên danh sách; Save success render lại list, Cancel về list; Delete async có try/catch và busy/error tương tự Save.
- [ ] Connect lookup profile mới nhất theo id, hide home + popup trước flow hiện có. Home/Retry/Cancel/error quay về đúng UI; tránh reference tới list/edit Canvas đã bỏ trên Android.
- [ ] Khi trạng thái Settings: ads retry initialization có hành vi giống home Profiles; không để đổi tab làm sai điều kiện quảng cáo.
- [ ] Phối hợp quảng cáo/consent, pause/resume và Back: không để home/popup lấy focus hoặc phủ fullscreen ad. `StbHost.java:1575–1590` chỉ ẩn nativeLayer; AlertDialog có window riêng nên phải ẩn/dismiss riêng, giữ draft/editorId để phục hồi sau ad nếu vẫn ở Edit. Native editor state giữ draft trong lifecycle mà Unity hỗ trợ; nếu Activity tái tạo, bind lại view từ draft thay vì tạo hai root.
- [ ] OnDestroy unsubscribe, dismiss dialog/IME và remove native home root theo pattern host dispose; async completion sau destroy không cập nhật UI.
- [ ] Cập nhật `Docs/AppStartup.md`: Android native home, Editor Canvas fallback, scope Status/Loading.

**Kiểm tra:** Editor mở Main/Loading; Android cold start, background/resume, rotate khi edit, connect rồi Home, connect cancel/error/retry, ad transition; không NullReference và không có hai nơi xử lý Back.

**Guard:** không sửa quy trình portal/player/reward để chỉ đổi UI; không bỏ toàn bộ Canvas khi StatusScreen vẫn cần; không dùng state Edit để tính watch time.

## 8. Phase 5 — Build và nghiệm thu

- [ ] Chạy EditMode tests liên quan profile/protocol và JVM tests hiện có: Gradle `testReleaseUnitTest` từ `Native/StbHost` bằng Java/Gradle toolchain tương ứng Unity đang dùng.
- [ ] Build `copyToUnity`, kiểm tra AAR mới tại `Assets/Plugins/Android/StbHost.aar`, rồi build Android bằng pipeline `Assets/Editor/AndroidDevBuild.cs`.
- [ ] Nếu thêm dependency: pin đồng thời native Gradle, Unity mainTemplate và `Docs/dependency-lock.md`. Thiết kế mặc định tận dụng dependencies có sẵn.
- [ ] Smoke trên thiết bị thật theo phase 2–4; kiểm tra release/R8 vì các entrypoint dùng JNI.
- [ ] Upgrade với profiles.json cũ: tên/id/MAC, Compatibility/Playback/DeviceIdMode không đổi sau edit field khác; restart vẫn đọc được dữ liệu.
- [ ] Kiểm tra exact-string: không còn Android branch gọi list/edit Canvas, không có persistence Java mới, không duplicate registration NativeEvent, không API bridge thiếu Java/C# counterpart.
- [ ] Ghi kết quả test/build/device vào plan; phần không có thiết bị hoặc portal hoạt động phải ghi rõ chưa xác minh.

**Hoàn thành khi:** Android hiện home native có đúng hai tab; Add/Edit/Delete dùng popup native; validation và storage lỗi giữ nguyên dữ liệu; connect/home và quảng cáo hoạt động; dữ liệu cũ còn nguyên; Editor fallback chạy được.

## 9. Thứ tự triển khai

Phase 0 → protocol/presenter → home/tabs → popup → tích hợp flow/lifecycle → build/nghiệm thu. Mỗi phase chỉ chuyển tiếp khi checklist tương ứng đạt. Plan dùng tab trên và Setting thông tin app/phiên bản đã được xác nhận.
