# App flow & UI (mobile) — design

Ngày: 2026-09-25 · Trạng thái: chờ duyệt

## 1. Mục tiêu

App Android **điện thoại/tablet (cảm ứng)**, nhanh gọn nhẹ: quản lý profile, kết nối portal Stalker, xem kênh.

Đã chốt với người dùng:
- **Giữ hướng portal WebView** (blueprint §3). Danh sách kênh, EPG, **search, favorite** là của UI portal; Unity không tải channel list, không gọi API Stalker.
- Unity chỉ làm **shell**: danh sách profile, thêm/sửa/xoá, connect, refresh, loading/lỗi, remote ảo, bàn giao playback cho ExoPlayer.
- Không TV/D-pad, không EnhancedScroller (danh sách profile ngắn → `ScrollRect`).

Thành công khi, trên một điện thoại thật:
1. Mở app lần đầu → tạo profile (MAC/Device ID sinh sẵn) → lưu → connect → thấy portal.
2. Điều khiển portal bằng remote ảo và nút Back hệ thống.
3. Portal gọi Play → video ExoPlayer hiện dưới WebView; Stop/Pause/Volume hoạt động.
4. Refresh tải lại portal; "Về danh sách" quay lại màn profile; mở app lần sau profile còn nguyên.
5. Lỗi (HTTP bị chặn, không vào được server, portal không trả `bridge.hello` trong 30 s) hiện thông báo dễ hiểu + Thử lại.

Ngoài phạm vi: cài đặt nâng cao (proxy, udpxy, firmware…), theme, nhớ kênh cuối, search/favorite phía Unity, TV/D-pad, iOS.

## 2. Màn hình và luồng

Một Canvas (uGUI + TextMeshPro), mỗi màn là một panel; chỉ một panel active.

```
[Profiles] ──chạm dòng──▶ [Connecting] ──bridge.hello──▶ [Portal]
  │ + Thêm                  │ lỗi / 30 s / Huỷ              │ ⟳ Refresh  → [Connecting] (phiên mới)
  │ ✎ Sửa, 🗑 Xoá (xác nhận)  ▼                               │ ⌂ Về danh sách → đóng portal → [Profiles]
  ▼                        [Error] (Thử lại / Quay lại)       │ 🎮 remote ảo hiện/ẩn
[EditProfile] ── Lưu ──▶ [Profiles]                            ▼
                                                   player.* ↔ ExoPlayer (dưới WebView)
```

| Màn | Nội dung | Hành vi |
|---|---|---|
| Profiles | `ScrollRect` các dòng: tên, host portal, MAC; nút ✎, 🗑; nút + | Danh sách rỗng → mở thẳng EditProfile. Xoá cần xác nhận. |
| EditProfile | Tên, Portal URL, MAC [Sinh], Serial, Model, Send Device ID, Device ID [Từ MAC], Device ID 2, Signature; Lưu / Huỷ | MAC trống khi lưu → `ProfileManager` tự sinh. Lỗi của `ProfileValidator` hiện dưới form. Device ID 2 / Signature trống = facade tự tính (đã có). |
| Connecting | Spinner, tên profile, Huỷ | Huỷ = đóng portal, về Profiles. Timeout 30 s → Error. |
| Error | Thông điệp theo mã lỗi, Thử lại, Quay lại | Bảng thông điệp ở §4. |
| Portal | WebView toàn màn; overlay nhỏ góc trên: ⟳, ⌂, 🎮 | Overlay là View native nằm trên WebView (xem §3.3). |
| Remote ảo | Hàng phím đáy màn: ▲▼◀▶, OK, Back, Menu, CH+, CH− | Mặc định ẩn; trạng thái hiện/ẩn nhớ trong phiên. |

Nút Back hệ thống: ở Portal → gửi phím Back cho portal; ở EditProfile/Error → về Profiles; ở Profiles → thoát app.

## 3. Kiến trúc

### 3.1 Giữ nguyên
`Profile`, `ProfileManager`, `JsonProfileRepository`, `MacAddress`, `ProfileValidator`, `PortalMessage`/`PortalEvent`/`PortalSession`, `IPortalView`/`AndroidPortalView`, `AndroidStbHost`, `stb_facade.js`, `AndroidDevBuild`, `Tools/logcat.ps1`.

### 3.2 Thêm mới (C#)

| Thành phần | Trách nhiệm | Phụ thuộc |
|---|---|---|
| `AppFlow` (MonoBehaviour, composition root) | Tạo `ProfileManager`, `AndroidStbHost`, `AndroidPortalView`, `AndroidPlayer`, `PortalController`; giữ state `Profiles/EditProfile/Connecting/Portal/Error`; bật/tắt panel; xử lý native event `ui.*` | mọi thứ dưới |
| `ProfileListScreen` + `ProfileRow` prefab | Render `IReadOnlyList<Profile>`; event `Selected(id)`, `Edit(id)`, `Delete(id)`, `Add()` | không (chỉ data) |
| `ProfileEditScreen` | Bind form ⇄ bản sao `Profile`; nút Sinh MAC, Device ID từ MAC; hiện list lỗi; event `Saved(Profile)`, `Cancelled` | `MacAddress` |
| `ConnectingScreen`, `ErrorScreen` | Hiển thị; event `Cancel`, `Retry`, `Back` | không |
| `PortalController` (C# thuần) | Map `PortalMessage` → `IPlayer`; map sự kiện player → `PortalEvent` gửi portal; tách URL từ lệnh Play | `IPortalView`, `IPlayer` |
| `PlayCommand.TryParseUrl(string, out string)` | Bỏ tiền tố (`ffmpeg`, `ffrt`, `ffrt2`, `ffrt3`, `auto`) → URL http/https/rtmp/rtsp/udp; không có URL → false | không |
| `IPlayer` / `AndroidPlayer` | `Play(url, headers)`, `SetPaused`, `SetVolume(0..1)`, `Stop`; event `StateChanged(PlayerState)`, `Error(string)` | `AndroidStbHost` |
| `AndroidStbHost` (mở rộng) | Thêm wrapper `Play`, `SetPaused`, `SetVolume`, `Stop`, `SendKey`, `SetOverlayVisible` | Java |

`AndroidStbHost` vẫn là nơi duy nhất gọi JNI. `IPlayer` tồn tại để test `PortalController` bằng fake (giống `IPortalView`).

### 3.3 Native (Java, `StbHost`)
- `sendKey(int androidKeyCode)`: `webView.dispatchKeyEvent` DOWN+UP. Map remote → Android key: ▲▼◀▶ `DPAD_*`, OK `ENTER`, Back `DEL` (DOM keyCode 8 như MAG), Menu/CH+/CH− **cần xác minh trên portal thật** (ứng viên: `MENU`, `PAGE_UP`, `PAGE_DOWN`).
- Back hệ thống khi WebView có focus: `setOnKeyListener` bắt `KEYCODE_BACK` → gửi `DEL` cho portal, không để Activity xử lý.
- **Overlay ⟳ ⌂ 🎮 và remote ảo là View native** (LinearLayout + Button) thêm vào `android.R.id.content` trên WebView, vì uGUI nằm dưới WebView nên không bấm được khi portal đang hiện. Nút overlay emit `ui.refresh`, `ui.home`; phím remote gọi thẳng `sendKey`. `setOverlayVisible(bool)` do `AppFlow` điều khiển.
- Player events đã có (`state`, `isPlaying`, `error`) → `AndroidPlayer`.

### 3.4 Luồng dữ liệu chính
1. Chạm profile → `AppFlow` tạo `PortalSession(profile)` → `portalView.OpenAsync(session, timeout 30 s)` → state Connecting.
2. `bridge.hello` → state Portal, `SetOverlayVisible(true)`.
3. Portal `gSTB.Play(cmd)` → facade → `player.play` → `PortalController` → `PlayCommand.TryParseUrl` → `IPlayer.Play(url, headers: UA của profile)`.
4. ExoPlayer `state/isPlaying/error` → `AndroidPlayer` → `PortalController` → `PortalEvent("player.state"|"player.error")` → facade dispatch `stb:event`. (Map sang callback riêng của portal: để sau, cần capture — blueprint §7.)
5. ⟳ → như bước 1 với phiên mới. ⌂ → `CloseAsync`, `IPlayer.Stop`, overlay ẩn, state Profiles.

## 4. Lỗi

| Nguồn | Mã | Thông điệp |
|---|---|---|
| `portal.error` `LOAD_FAILED:* ERR_CLEARTEXT_NOT_PERMITTED` | cleartext | "Portal dùng HTTP nhưng bị chặn. Build lại app." |
| `LOAD_FAILED:*` ERR_NAME_NOT_RESOLVED / CONNECTION_* / TIMED_OUT | network | "Không kết nối được tới portal. Kiểm tra mạng và URL." |
| `WEBVIEW_UNSUPPORTED` | webview | "WebView trên máy quá cũ. Cập nhật Android System WebView." |
| Timeout 30 s chờ `bridge.hello` | timeout | "Portal không phản hồi." |
| Khác | unknown | "Lỗi: {mã gốc}" |
| `player.error` | — | Không đổi màn; toast 3 s + gửi `player.error` cho portal. |
| Lưu profile lỗi | — | Giữ form, hiện lỗi dưới form (rollback đã có trong `ProfileManager`). |

## 5. Test

EditMode (NUnit, dùng fake, không cần Unity scene):
- `PlayCommand`: có/không tiền tố, nhiều khoảng trắng, không URL, scheme lạ.
- `PortalController`: play hợp lệ → `IPlayer.Play`; play không URL → không play + `player.error`; stop/pause/continue/volume; player state → `PublishAsync`; không còn gọi player sau `Dispose`.
- `AppFlow` logic chuyển state tách thành hàm thuần nếu có nhánh không tầm thường (quyết định khi viết plan).

Thiết bị (checklist thủ công, `Tools/logcat.ps1`): 5 tiêu chí ở §1.

## 6. Dọn dẹp
- Xoá `Assets/App/Dev/PortalSmokeTest.cs` và prefab `PortalTest` (AppFlow thay thế).
- `Canvas.prefab` hiện có: dùng lại làm gốc Canvas.

## 7. Rủi ro / chưa biết
- Mã phím Menu/CH± của portal: xác minh trên thiết bị, giữ mapping ở một chỗ.
- Portal redirect sang origin khác → facade không được inject (đã biết từ trước).
- Callback player riêng của portal chưa map (chỉ có `stb:event`) → có thể portal không cập nhật UI khi video bắt đầu/kết thúc; xử lý khi có capture.
- Tatatv trả 403 cho request từ PC; test end-to-end phụ thuộc portal/tài khoản dùng được trên thiết bị.
