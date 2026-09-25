# Native channel list (mobile) — design

Ngày: 2026-09-25 · Trạng thái: chờ duyệt · Nối tiếp: `2026-09-25-app-flow-ui-design.md`

## 1. Mục tiêu

Điều khiển portal Stalker trên điện thoại khó vì UI portal đi từng ô bằng phím. Thêm **danh sách kênh native (Java, RecyclerView)** đè lên portal: cuộn quán tính mượt, tìm kiếm bằng bàn phím điện thoại (có dấu), tab **nhóm kênh** và **yêu thích**; chạm kênh → phát.

Đã chốt với người dùng:
- Giữ hướng portal WebView: **không gọi API Stalker từ app**. Dữ liệu lấy từ những gì portal đã tải trong bộ nhớ trang; phát kênh qua hàm của chính portal (portal tự `create_link`, token).
- Có: danh sách + tìm kiếm + nhóm kênh + yêu thích.
- EPG, Video Club, cài đặt… vẫn dùng UI portal (cử chỉ + remote ảo giữ nguyên).

Thành công khi, trên điện thoại thật với một portal Stalker đang đăng nhập:
1. Bấm 📺 → danh sách hiện < 300 ms với toàn bộ kênh (đo với ≥ 16.000 kênh), cuộn mượt.
2. Gõ "espn" → lọc tức thì (< 100 ms mỗi phím); tìm không phân biệt hoa/thường và dấu ("thoi su" khớp "Thời sự").
3. Chọn nhóm → chỉ kênh của nhóm; tab ★ → chỉ kênh yêu thích của tài khoản (lấy từ portal).
4. Chạm kênh → danh sách ẩn, portal chuyển sang kênh đó và video phát (qua `player.play` → ExoPlayer như hiện tại).
5. Portal không có dữ liệu đọc được → nút 📺 ẩn, không lỗi.

Ngoài phạm vi: sửa yêu thích từ danh sách native, EPG trong danh sách, logo kênh (bản đầu chỉ chữ), sắp xếp tuỳ chỉnh, lưu cache ra đĩa.

## 2. Luồng

```
Portal khởi động xong (bridge đã có) ──▶ facade đọc kênh/nhóm/yêu thích từ bộ nhớ portal
        │                                    │ gửi "channels.data" (1 message, JSON gọn)
        ▼                                    ▼
Người dùng bấm 📺 ─────────────────▶ Native ChannelPanel (RecyclerView) hiện, ô tìm kiếm trên cùng, tab: Tất cả | ★ | nhóm…
        │ chạm kênh id=X                       │
        ▼                                      ▼
Native → facade "channels.play" {id} ──▶ facade gọi hàm chuyển kênh của portal ──▶ portal create_link ──▶ gSTB.Play ──▶ ExoPlayer
```

- Dữ liệu gửi một lần khi portal sẵn sàng và gửi lại khi portal làm mới danh sách (theo dõi bằng polling nhẹ 5 s so sánh số lượng + id đầu/cuối; không hook sâu).
- Panel native **không đi qua C#**: Java xử lý message `channels.*` ngay trong listener `stbNative` và **không** chuyển chúng sang C# (C# giới hạn 16 KB/message; 16.000 kênh ≈ 1 MB). Không trễ, C# không đổi.

## 3. Thành phần

### 3.1 Facade (`stb_facade.js`)
- `ChannelSource` (một object, một chỗ duy nhất biết tên biến nội bộ của portal):
  - `read() → {channels:[{id, number, name, genreId}], genres:[{id, title}], favIds:[id]} | null`.
  - `play(id) → bool`.
  - Đã xác minh trên tatatv (Stalker 5.6.10, 16.519 kênh, 253 nhóm): kênh `stb.player.channels` (`id, number, name, tv_genre_id`), nhóm `module.tv.genres` (`id, title`; bỏ nhóm giả `id:'*'` "All"), yêu thích `stb.player.fav_channels_ids` (id dạng chuỗi), phát `module.tv._play_now(item)`. Không có nguồn nào khớp → `read()` trả `null`.
- Gửi `{type:"channels.data", payload}` khi `read()` khác lần trước; nhận `channels.play` từ native.
- Kích thước: chỉ 4 trường/kênh → ~60 byte/kênh → 16.000 kênh ≈ 1 MB, gửi một message (WebMessage không giới hạn cỡ này).

### 3.2 Native (`StbHost.java` + `ChannelPanel.java` mới)
- `ChannelPanel`: `LinearLayout` dọc gồm `EditText` tìm kiếm, hàng tab cuộn ngang (`Tất cả`, `★`, các nhóm), `RecyclerView` (`LinearLayoutManager`, item 56dp: số + tên).
- Lọc: chuẩn hoá tên một lần khi nhận dữ liệu (`Normalizer` NFD, bỏ dấu, `ư/ơ/đ` → `u/o/d`, lowercase); mỗi phím lọc tuyến tính trên mảng đã chuẩn hoá (16.000 phần tử, vài ms).
- Chạm kênh → gửi `channels.play {id}` cho facade, ẩn panel.
- Nút 📺 trên overlay: ẩn tới khi có `channels.data`; bấm = hiện/ẩn panel. Back khi panel mở = đóng panel.
- Thêm dependency `androidx.recyclerview:recyclerview` (cả `build.gradle` lẫn `mainTemplate.gradle`, ghi vào `Docs/dependency-lock.md`).

### 3.3 C#
- Không thay đổi (Java giữ lại `channels.*`).

## 4. Lỗi
| Tình huống | Hành vi |
|---|---|
| `read()` ném lỗi / portal khác cấu trúc | `null` → không gửi → 📺 ẩn; log `console.warn` một lần |
| `play(id)` false (kênh không còn) | facade gửi `channels.error {code:"CHANNEL_NOT_FOUND"}` (không tới C#); native hiện Toast |
| Danh sách rỗng sau lọc | dòng "Không có kênh phù hợp" |
| Tên kênh rất dài | facade cắt còn 80 ký tự |

## 5. Test
- Node (`Native/StbHost/test/facade_check.js`): `ChannelSource.read()` với một object portal giả theo đúng cấu trúc xác minh ở Task 1 → đúng 3 mảng; cấu trúc thiếu → `null`; `play(id)` gọi đúng hàm portal.
- Java: lớp thuần `ChannelFilter` (chuẩn hoá + lọc theo text/tab) kèm JUnit trong `Native/StbHost/src/test` (`./gradlew test`): dấu tiếng Việt, hoa/thường, tab ★, tab nhóm, chuỗi rỗng.
- Thiết bị (adb + CDP như đã làm): mở panel, gõ, chọn nhóm, chạm kênh → `player.play` xuất hiện trong log, ExoPlayer phát; đo thời gian mở panel với danh sách lớn.

## 6. Rủi ro / chưa biết
- **Nơi portal giữ danh sách kênh, nhóm, yêu thích và hàm chuyển kênh chưa được xác minh** (lần đọc thử `stb.player.channels` trả rỗng; portal hiện đang trả 403 nên chưa đọc được `tv.js`). Task 1 của plan = đọc `tv.js`/`player.js` trên portal thật qua CDP, chốt tên, ghi vào đầu `ChannelSource`. Nếu danh sách chỉ nạp khi mở module TV, facade gọi hàm nạp của portal một lần (không gọi API riêng).
- Portal khác phiên bản đổi tên biến → chỉ sửa `ChannelSource`.
- Chuyển kênh bằng hàm portal có thể đổi màn portal (mở TV toàn màn) — chấp nhận, vì đó là hành vi xem kênh bình thường.
