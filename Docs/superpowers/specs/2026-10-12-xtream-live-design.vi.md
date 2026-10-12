# Thiết kế: chuyển Stalker portal (MAC) sang Xtream Codes (Live TV)

Ngày: 2026-10-12 · Trạng thái: chờ duyệt

## Mục tiêu

App mới (tên, package, Firebase, IAP đã cấu hình) kết nối tới nhà cung cấp IPTV qua **Xtream Codes API**
(`player_api.php`) bằng **Server URL + username + password**, thay hoàn toàn cho cơ chế giả lập MAG/Stalker
theo MAC. Giữ nguyên trải nghiệm Live TV hiện tại: danh sách kênh theo thể loại, yêu thích, tìm kiếm, EPG,
panel thông tin tài khoản, quảng cáo/Pro.

## Ngoài phạm vi

- VOD, Series, catch-up/timeshift (badge archive vẫn hiện, chưa phát).
- Nhập link M3U, playlist M3U thuần.
- Cache danh sách kênh ra đĩa (mỗi lần kết nối tải lại).
- Chuyển đổi dữ liệu profile cũ (app mới, không có người dùng cũ).
- Mã hoá password.

## Hiện trạng (tóm tắt)

App không có Stalker HTTP client: JS của portal chạy trong WebView ẩn, `stb_facade.js` giả lập `gSTB`, scrape
danh sách kênh và gửi JSON `channels.data` sang `StbHost.java`; `TvScreen.java` (native) vẽ UI. Phát kênh:
facade → `player.play` → C# `PortalController` → `AndroidPlayer` → `StbHost.playFromPortal`. ExoPlayer (Media3)
phát bằng `StbHost.play(url, headersJson)`.

## Quyết định

| # | Quyết định |
|---|---|
| D1 | Thay hẳn Stalker, không giữ song song. |
| D2 | Chỉ Live TV ở bản này. |
| D3 | Đăng nhập: Server URL + username + password. |
| D4 | Xtream client viết bằng **Java trong `StbHost`** (hướng A), không qua C#. |
| D5 | Giữ hợp đồng sự kiện native ↔ C# và **giữ tên nội bộ `Portal*`**; chỉ đổi chữ hiển thị. |
| D6 | UA API mặc định = UA MAG (`MagUserAgent`, đổi sang `MAG250`); UA stream mặc định = `Lavf53.32.100`. Cả hai ghi đè được. |
| D7 | Password lưu dạng thường trong `profiles.json` (bộ nhớ riêng của app). Không bao giờ log. |
| D8 | Profile schema v2; file schema khác bị bỏ qua (coi như rỗng). |

## Kiến trúc

### Java (`Native/StbHost`)

**`XtreamClient.java` (mới)** — HTTP thuần qua OkHttp, không đụng View, chạy trên executor nền. Transport
được inject để test JVM bằng fixture.

- `authenticate()` → `GET {server}/player_api.php?username=&password=` → `user_info` + `server_info`.
  Hợp lệ khi `user_info.auth == 1` và `status == "Active"`.
- `liveCategories()` → `&action=get_live_categories`
- `liveStreams()` → `&action=get_live_streams`
- `shortEpg(streamId)` → `&action=get_short_epg&stream_id=` (title/description base64)
- `streamUrl(streamId)` → `{server}/live/{user}/{pass}/{streamId}.{ext}`; `ext = m3u8` nếu
  `user_info.allowed_output_formats` chứa `m3u8`, ngược lại `ts`. Username/password được URL-encode.
- Header `User-Agent` = UA API của session. Timeout 15 s mỗi request.

**`XtreamCatalog.java` (mới)** — map JSON Xtream sang payload `channels.data` hiện có:

```
channels[{ id: stream_id, number: num|null, name, genreId: category_id, logo: stream_icon, archive: tv_archive==1 }]
genres[{ id: category_id, title: category_name }]
favIds: []
logoBase: ""
```

Bỏ kênh không có `stream_id`/`name`, bỏ trùng `stream_id` (giữ cái đầu). Kết quả luôn qua `validateCatalog`.

**`StbHost.java`**

- `openPortal(url, snapshotJson, userAgent)`: không mở WebView. Đọc `{sessionId, serverUrl, username,
  password, apiUserAgent, playerUserAgent}` từ snapshot, chạy nền: `authenticate` (phase `AUTHENTICATING`) →
  `liveCategories` + `liveStreams` (phase `LOADING`) → về UI thread → đường cũ: `validateCatalog` →
  `channelPanel/tvScreen.setData` → `portalChannelState.ready` → `emit("portal.ready")`. Kết quả của session
  cũ (sessionId khác) bị bỏ.
- Khoá yêu thích: `UUID(url + "\n" + username)`.
- `sendChannelRequest(id)`: ghép `streamUrl(id)` và gọi `play(url, {"User-Agent": playerUserAgent})` trực tiếp.
- `requestDetails`: `epg` → `shortEpg` → `{programs[{title, description, time}]}`; `info` → từ `user_info`
  đã lưu: hạn dùng (`exp_date`), trạng thái, `max_connections`, `active_cons`. Panel info hiện username thay MAC.
- Nút ⟳ (`reconnectPortal`): khi READY tải lại categories + streams; khác READY → `ui.refresh` như cũ.
- Khôi phục stream: giữ `ReloadPolicy`. Bước "resolve link" = gọi lại `authenticate()`:
  Active và chưa vượt kết nối → phát lại URL; `active_cons >= max_connections` → `MAX_CONNECTIONS`;
  hết hạn/khoá → mã tương ứng; lỗi mạng → đường reconnect hiện có.
- Chọn kênh khi mất kết nối: giữ `ui.channel-selected.reconnect` → reconnect → phát kênh chờ. Nếu đã có
  `user_info` hợp lệ, phát ngay bằng URL ghép, không chờ danh sách kênh.
- **Gỡ bỏ:** WebView và mọi thứ gắn với nó (`installPortalWebViewClient`, web message listener, document-start
  script, cookie, `facadeJs`), `stb_facade.js`, fast-play và `channelCmds`, env prefs (`stbemu_portal_env`),
  `onPortalAuthFailed`/`AUTH_FAILED_REPEATED`, `touchAsRemote`, `sendKeyOnUi`, viewport messages.
  `PortalChannelState` giữ lại nếu vẫn phục vụ READY/UNAVAILABLE/pending channel; bỏ phần chỉ dùng cho fast-play.
- Log: URL stream được che password (`/live/{user}/***/{id}.ext`).

**`ProfileEditorDialog.java`**: ô Tên, Server URL, Username, Password (nút hiện/ẩn); mục "Nâng cao":
User-Agent API, User-Agent player. Bỏ MAC/Generate/From MAC/serial/model/device ID/signature/force login.

**`HomeScreen.java`**: dòng profile hiện `host · username`.

### C# (`Assets/App`)

- **`Profile.cs`**:
  ```
  Profile { ProfileId, Name, ServerUrl, Username, Password,
            Compatibility { UserAgentOverride, PlayerUserAgentOverride },
            Playback { MinBufferMs, Tunneling } }
  ```
  Bỏ `DeviceIdentity`, `DeviceIdMode`, `ForceLoginPortal`, `ForceLoginConverter`, `AdapterId`,
  `PortalLanguage`, `Timezone`. `CurrentSchemaVersion = 2`.
- **Chuẩn hoá `ServerUrl`**: trim; thêm `http://` nếu thiếu scheme; bỏ `/player_api.php…`, `/get.php…`,
  `/panel_api.php…` và `/` cuối. Giữ path con (vd. `http://host:8080/xc`).
- **`ProfileValidator`**: Tên, URL http(s), Username, Password bắt buộc; không trùng `(ServerUrl, Username)`
  (so sánh URL đã chuẩn hoá, username phân biệt hoa thường).
- **`ProfileManager`**: bỏ sinh MAC.
- **`JsonProfileRepository`**: schema ≠ 2 → danh sách rỗng.
- **Xoá** `MacAddress.cs`.
- **`PortalSession`**: snapshot `{sessionId, operationId, serverUrl, username, password, apiUserAgent,
  playerUserAgent}`; UA rỗng → mặc định (D6). `NextAttempt()` giữ nguyên.
- **`PortalController.cs`**: chỉ giữ hằng UA (`MagUserAgent` → `MAG250`, `DefaultPlayerUserAgent`), hoặc
  chuyển hằng sang `PortalSession` rồi xoá file. Bỏ routing `player.*`. **Xoá** `PlayCommand.cs`.
- **`PortalMessage.cs`**: bỏ `PortalMessage`/`PortalEvent` nếu không còn ai dùng sau khi bỏ facade
  (`AndroidPortalView` không còn nhận `portal.message`).
- **`AndroidPortalView`**: giữ retry/deadline; bỏ nhánh `portal.message`.
- **`AppFlow`**: bỏ tạo `PortalController`; `TrackConnect` chỉ log host (không MAC, không username/password);
  câu lỗi "Editor has no WebView" → "chỉ chạy trên thiết bị Android".
- **`ProfileForm` / `ProfileEditScreen` / `AndroidProfileUi`**: field keys mới khớp `ProfileEditorDialog`.
- **`PortalFailurePolicy` + `ErrorText`**: bảng mã dưới đây.

## Mã lỗi

| Tình huống | Mã | Retry khi kết nối | Thông báo |
|---|---|---|---|
| DNS, mất mạng, timeout | `NETWORK` / `TIMEOUT` | có | Không kết nối được server |
| HTTP 5xx | `HTTP_5xx` | có | Server đang lỗi, thử lại sau |
| HTTP 401/403 ở `player_api` | `FORBIDDEN` | không | Server từ chối truy cập |
| `auth == 0` | `AUTHENTICATION_REJECTED` | không | Sai username hoặc password |
| `status == Expired` | `ACCOUNT_EXPIRED` | không | Tài khoản đã hết hạn ({ngày}) |
| `status` Banned/Disabled/khác | `ACCOUNT_DISABLED` | không | Tài khoản bị khoá |
| Phản hồi không phải JSON Xtream | `INVALID_RESPONSE` | không | Địa chỉ không phải server Xtream |
| Lỗi `get_live_streams`/categories | `catalog-error` | có | Không tải được danh sách kênh |
| Stream: vượt kết nối | `MAX_CONNECTIONS` | — | Vượt số kết nối cho phép |

## Kiểm thử

- **JVM**: `XtreamClientTest` (fixture `user_info` Active/Expired/Banned/`auth=0`, non-JSON, HTTP 5xx; categories;
  streams; short EPG base64; URL m3u8/ts và URL-encode), `XtreamCatalogTest` (định dạng `channels.data`, qua
  `validateCatalog`, id trùng, `num` null, `category_id` không có trong genres). Cập nhật/xoá test JVM của lớp
  bị đổi/bỏ (`PortalChannelStateTest`, `ProfileEditorDialogTest`).
- **NUnit EditMode**: `ProfileTests`, `ProfileFormTests` (chuẩn hoá URL, trùng `(url,user)`),
  `PortalFailurePolicyTests`, `ErrorTextTests`, `PortalMessageTests` (snapshot mới). Xoá `PortalControllerTests`,
  `PlayCommandTests`.
- **Node**: xoá `Native/StbHost/test/*` (facade).
- **Thủ công trên thiết bị** (tài khoản Xtream do người dùng cung cấp, không ghi vào repo): kết nối, chuyển kênh,
  EPG, panel info, sai mật khẩu, tắt mạng giữa chừng, ⟳, quảng cáo xen kẽ khi chọn kênh.

## Thứ tự triển khai

1. Java: `XtreamClient` + `XtreamCatalog` + test JVM.
2. Java: nối vào `StbHost` thay WebView; `requestDetails`, khôi phục stream, `ProfileEditorDialog`, `HomeScreen`.
3. C#: `Profile`, validator, form, `PortalSession`, bỏ `PortalController`/`PlayCommand`, `ErrorText`,
   `PortalFailurePolicy`, `AppFlow`.
4. Dọn dẹp: xoá facade và test Node; ghi chú "đã thay bằng Xtream" ở đầu các docs Stalker (không xoá docs).
5. `gradlew copyToUnity`, build APK, test thủ công trên thiết bị.
