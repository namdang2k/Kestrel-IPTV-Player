# Đối chiếu SFVIP và Android — 2026-10-05

Nguồn: `.analysis/decompiled/sfvip player.decompiled.cs` do người dùng cung cấp.
Đây là bản decompile có tên symbol và chuỗi bị obfuscate; chưa có DLL `libmpv`
managed hoặc executable gốc trong thư mục `.analysis/decompiled` lúc kiểm tra.

## Các điểm xác nhận từ mã

| Vị trí trong bản decompile | Hành vi |
|---|---|
| 16091–16093 | Account có `UserAgent` và `PlayerUserAgent` riêng biệt. |
| 38259–38261 | `_stbUserAgent` và `_playerUserAgent` có hai giá trị mặc định khác ID chuỗi. |
| 38990–38999 | Override của từng trường chỉ áp dụng khi account cung cấp giá trị không rỗng. |
| 40290 | `FixUrl` bỏ prefix trước dấu cách đầu tiên rồi trim; không thay token query trong đoạn này. |
| 40307–40311 | Gán player User-Agent, network timeout và HTTP proxy nếu được cấu hình. |
| 40480–40532 | Live MAC portal có thể lấy temporary link; thất bại thì refresh/retry; một số đường đi dùng URL trực tiếp. |
| 40694–40700 | Gán `_player.UserAgent = _playerUserAgent`, proxy nếu có, rồi `_player.Load(text, title, force: true)` và `Resume()`. |

Trong đoạn gọi player đã kiểm tra không thấy gán Cookie/Authorization/Referer.
Điều này không chứng minh wrapper `libmpv.Player.MpvPlayer` không bổ sung chúng:
implementation wrapper chưa có trong file decompile được cung cấp.

## Khác biệt với Android hiện tại

`PortalController` lấy `Compatibility.UserAgentOverride` hoặc MAG200 và gửi làm
User-Agent cho stream. Chưa có trường PlayerUserAgent độc lập như SFVIP.
MPV Android tải qua `MpvStreamGateway`/OkHttp; phía SFVIP gọi wrapper MPV Load.
Cần xem wrapper trước khi kết luận chi tiết HTTP stack của SFVIP.

Người dùng đã xác nhận cùng URL Android phát được trên SFVIP nhưng không trên VLC.
Vì vậy User-Agent/request behavior là hướng đối chiếu có cơ sở, chưa phải nguyên nhân
đã được chứng minh. Không có cơ sở để tự thêm Cookie/Authorization hoặc thay play_token.

## Dữ liệu còn thiếu để tái hiện chính xác

- Giá trị PlayerUserAgent thực của account SFVIP đang phát được (nếu có override).
- Chuỗi mặc định `_playerUserAgent`: được giải mã bằng hàm obfuscate với ID `967494370`;
  cần resource/executable gốc để giải mã, không suy đoán rằng nó là `mpv`, VLC hay MAG.
- DLL managed chứa `libmpv.Player.MpvPlayer` để kiểm tra setter UserAgent và Load.
- Cấu hình HTTP proxy nếu account SFVIP có dùng.

Chưa thay đổi cấu hình phát Android dựa trên chuỗi mặc định chưa xác định.

## ?? x?c minh v?i b? ch?y g?c

Ngu?n b? sung: `C:/Users/namdc/Desktop/1.2.5.7/SFVIP-Player-x64`.
?? decompile `libmpv.dll` v? ??c chu?i t? resource c?a EXE qua m?t b?n sao ph?n t?ch
trong `.utmp`; kh?ng s?a b? ch?y g?c v? kh?ng ch?y entry point/UI c?a SFVIP.

- Chu?i ID `967494370` (PlayerUserAgent m?c ??nh): **`Lavf53.32.100`**.
- Chu?i ID `967497597` (portal STB UA): MAG200 QtEmbedded.
- `libmpv.Player.MpvPlayer.UserAgent` ghi th?ng property MPV `user-agent` khi kh?ng r?ng.
- `MpvPlayer.Load` ??t pause r?i g?i `_mpv.Command("loadfile", url, replace/append)`.
  Kh?ng c? b??c th?m Cookie/Authorization ho?c thay token trong method n?y.
- `mpv.conf` ???c cung c?p kh?ng c? override User-Agent/HTTP headers.
- Gi? tr? override ri?ng trong account SFVIP ch?a ???c ??c; k?t qu? tr?n l? m?c ??nh c?a ch??ng tr?nh.

## Thay ??i Android ?? ?p d?ng

`PortalController.DefaultPlayerUserAgent = "Lavf53.32.100"`.
`CompatibilitySettings.PlayerUserAgentOverride` l? tr??ng m?i; r?ng d?ng m?c ??nh n?y.
`AppFlow` truy?n tr??ng player m?i v?o controller, kh?ng truy?n `UserAgentOverride` c?a portal.
Media3 v? MPV gateway nh?n c?ng header stream ?? ch?n. URL/play_token gi? nguy?n.
Kh?ng thay HTTP stack ho?c t? th?m credentials trong l?n s?a n?y ?? ki?m tra ri?ng kh?c bi?t UA.

?? compile actual source C# v? ch?y 9 test PortalController b?ng Mono/NUnit, g?m default
null/r?ng/whitespace, override v? URL c? query. Ch?a th? HTTP/ph?t tr?n thi?t b? Android.
??y l? thay ??i managed code: c?n build l?i APK b?ng Unity; ch? ch?p l?i StbHost.aar l? ch?a ??.
