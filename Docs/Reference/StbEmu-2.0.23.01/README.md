# StbEmu v2.0.23.01 (com.mvas.stb.emu.free) — tài liệu tham chiếu

Trích từ `StbEmu_v2.0.23.01.xapk` (đã xoá) để tham khảo tương thích. Chỉ dùng để hiểu hành vi, **không copy code**.
Tên class/method bị obfuscate (`s13`, `dm5`, `tob`…) — chỉ đúng cho bản này.

| File | Nội dung |
|---|---|
| `profiles_schema.sql` | Bảng `profiles` (Room DB) — toàn bộ field của một profile |
| `gSTB_api_surface.txt` | Các method `gSTB` mà StbEmu expose cho portal JS (class `s13`) |
| `strings_en_profile.txt` | Nhãn UI tiếng Anh của các field profile |
| `assets/` | `default_config.json`, timezone/language list, CSS patch cho Stalker, `apistub.js` |
| `disasm/` | Đoạn `dexdump -d` của GetUID, GetDeviceSerialNumber, hàm băm, bộ sinh Device ID |

## Cách StbEmu kết nối portal

- Portal chạy trong **WebView**; `gSTB` được expose bằng `addJavascriptInterface` → **gọi đồng bộ**, tính giá trị ngay lúc gọi.
- StbEmu **không tự gọi API Stalker**: handshake/get_profile do JS của portal tự làm, lấy `sn`, `device_id`, `device_id2`, `signature` qua `gSTB.*`.
- UA mặc định: `Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3`.

## Field định danh thiết bị

| Field (DB) | Ý nghĩa |
|---|---|
| `mac_address` | MAC, mặc định `00:1A:79:??:??:??` |
| `serial_number` | `gSTB.GetDeviceSerialNumber()` trả nguyên giá trị này |
| `device_id` | Device ID 1 (64 hex, in hoa) |
| `use_mac_based_device_id`, `device_id_seed`, `mac_seed_net_interface` | Cách sinh `device_id` |
| `send_device_id` | Tắt → mọi `GetUID*` trả `""` |
| `device_custom_dev_id2` | Bật → dùng `device_id2`/`device_signature` nhập tay thay vì tính |
| `device_id2`, `device_signature` | Device ID 2 và Signature (cache lần tính gần nhất, hoặc giá trị nhập tay) |

### Sinh `device_id` (`tob.B`, gọi khi bấm "Reset Device ID")
`device_id = UPPER(hex(SHA256(input)))`, với `input`:
- MAC-based bật → chuỗi MAC của profile
- ngược lại, seed không rỗng → seed
- ngược lại → `Settings.Secure.ANDROID_ID`

### `GetUID` (class `s13`)
Ký hiệu: `B64(x) = Base64.DEFAULT` (**có `\n` ở cuối**), `HMAC(key, data) = HmacSHA256`, UTF-8, `hex` = chữ thường.

| Gọi | Trả về (khi `send_device_id` = true; nếu false → `""`) |
|---|---|
| `GetUID()` | `device_id` |
| `GetUID(a)` | custom bật & signature ≠ "" → `device_signature`; `a` rỗng → `B64(HMAC(device_id, device_id))`; ngược lại `B64(HMAC(a, device_id))`. Lưu vào `portal_signature` |
| `GetUID(a, b)` | `b` rỗng → như `GetUID(a)`. custom bật & id2 ≠ "" → `device_id2`. `a == b` → `UPPER(hex(SHA256(B64(HMAC(a, b)))))`. `a == "device_id"` → `device_id`. Khác → `""`. Kết quả lưu vào `portal_device_id2` |
| `GetUID(s, int, int)` | luôn `""` |

`HMAC(k, d)` ở trên: `k` là key, `d` là data (`dm5.x(data, key)`).
Portal Stalker thường gọi `GetUID('device_id', 'device_id')` → device_id2 và `GetUID(token)` → signature (chưa xác nhận bằng capture thật).

## Chưa rõ
- Serial mặc định được sinh thế nào (không thấy trong code đọc được; có thể người dùng nhập tay).
