# Google Play Store Listing (ASO) — Kestrel – IPTV Player

- Package: `com.stbemu.kestrel` · Version: 1.0.1
- Primary language: **English (en-US)**. Vietnamese (vi-VN) translation at the end.
- Play limits: App name ≤ 30 chars · Short description ≤ 80 chars · Full description ≤ 4000 chars.
- Replaces `Docs/ASO_GooglePlay.txt` (old "STB Portal Player" listing for Stalker/MAC portals, no longer accurate).

## Before publishing

1. **Scope matches the build.** This version supports **Xtream Codes API login (server URL + username + password), Live TV only**.
   Do not claim VOD, Series, catch-up playback, M3U playlists, MAC/Stalker portals, Chromecast or Android TV remote
   support — they are not implemented. Catch-up channels only show a badge.
2. **Trademarks.** No "IPTV Smarters", "TiviMate", "MAG", "Infomir", "StbEmu" or other app/brand names in the title, icon,
   screenshots or text. "Xtream Codes API" is used descriptively (compatibility) in the description only, not in the app name.
3. **IPTV policy.** Play reviews IPTV players strictly. Keep the disclaimer. Screenshots/video must not show copyrighted
   channels, logos of real broadcasters or a real provider's server. Use a demo server with neutral test streams or blurred content.
4. **Play Console declarations.**
   - Contains ads: **Yes** (AdMob: banner, interstitial, rewarded, app open).
   - In-app purchases: **Yes** — Monthly / Yearly subscription (`premium_monthly`), Pro Forever (`remove_ads`).
   - Data safety: device/advertising ID (AdMob), crash logs and app interactions (Firebase Analytics / Crashlytics),
     purchase history (Google Play Billing). Server URL, username and password stay on the device only and are never sent to us.
5. **Free trial wording.** Mention "3-day free trial" only while the 3-day offers are active on both base plans in Play Console.
   If they are removed, delete that line from the description.
6. Category: **Video Players & Editors**. Tags: IPTV, video player, live TV.

---

## English (en-US)

### App name (21 / 30)

```
Kestrel – IPTV Player
```

Alternative (if the name can differ from the in-app name, 29 / 30): `Kestrel IPTV Player – Live TV`

### Short description (≤ 80)

```
Fast IPTV player for live TV: log in to your provider with Xtream Codes API
```

Alternatives:

- `Watch your IPTV provider's live channels with EPG, favorites and quick search`
- `Simple, fast live TV player for your IPTV subscription. Sign in and watch.`

### Full description

```
Kestrel is a fast, clean IPTV player for live TV on Android. Sign in with the server URL, username and password from your IPTV provider (Xtream Codes API) and your channel list opens in seconds, sorted into your provider's categories.

★ SIGN IN AND WATCH
• Log in with server URL, username and password, no MAC address needed
• Multiple profiles: switch between providers or subscriptions in one tap
• Your account at a glance: expiry date, status and allowed connections
• Clear messages when a login fails: wrong password, expired account, server down or too many connections

★ A TV GUIDE MADE FOR YOUR PHONE
• Channels grouped by category, with logos and channel numbers
• Favorites: star the channels you watch most
• Instant search across channels and categories, even with tens of thousands of channels
• EPG: see what's on now and next for the selected channel
• Portrait and landscape, with a full-screen video mode
• Dark and light themes

★ SMOOTH, RELIABLE PLAYBACK
• Modern Media3 / ExoPlayer engine for HLS and MPEG-TS live streams, including HEVC (H.265)
• Automatic retry when a stream stalls or drops, rejoining the live edge
• Reconnects to your server in the background and keeps the current channel playing
• Adapts buffering to your network and warns you when the connection is too slow for a channel

★ STREAM INFO FOR POWER USERS
• Resolution, frame rate, video codec and bitrate
• Buffered time, live latency and measured bandwidth
• Network quality and the last server problem, with tips to fix it

★ KESTREL PRO
Go Pro to remove all ads: no ads when changing channels, no "watch an ad" breaks and no upgrade reminders.
• Monthly or Yearly subscription with a 3-day free trial for eligible users
• Pro Forever: pay once, keep it forever
• Restore Purchase on any device signed in to the same Google account
Subscriptions renew automatically. Cancel anytime in the Google Play Store.

HOW TO START
1. Get the server URL, username and password from your IPTV provider.
2. Open Kestrel, tap Add profile and enter them.
3. Tap the profile to connect and pick a channel.

TIPS
• If the server cannot be reached, check the URL (including the port, e.g. http://example.com:8080) or try Cloudflare DNS (1.1.1.1).
• Each subscription allows a limited number of connections. Close other devices if you see "too many connections".

The app is free and supported by ads.

DISCLAIMER
Kestrel is a media player only. It does not include, provide, sell or host any TV channels, playlists, servers or subscriptions. You must use your own account from a legitimate IPTV provider, and you are responsible for having the rights to the content you watch. Kestrel is not affiliated with any IPTV provider, and we do not endorse streaming copyrighted content without permission.
```

### Screenshot captions (2–5 words)

1. `Sign in. Start watching.`
2. `Channels by category`
3. `Favorites & instant search`
4. `What's on now — EPG`
5. `Smooth live playback`
6. `Stream info at a glance`
7. `Go Pro: no ads`

### Feature graphic (1024 × 500)

Headline: `Your IPTV. Live, fast, simple.` · Sub: `Kestrel – IPTV Player`

### Keywords (woven into the text above; Play has no keyword field — do not add a keyword list)

iptv player, iptv, live tv, xtream codes, xtream codes api, iptv xtream player, epg, tv guide, tv channels,
hls player, m3u8 player, hevc player, iptv subscription, video player

### What's new (≤ 500)

```
• Sign in with Xtream Codes API: server URL, username and password
• Account info: expiry date, status and connections
• Clearer error messages for login and connection problems
• Playback stability improvements
```

---

## Tiếng Việt (vi-VN)

### Tên ứng dụng — giữ tên tiếng Anh

```
Kestrel – IPTV Player
```

### Mô tả ngắn (≤ 80)

```
Trình phát IPTV nhanh để xem TV trực tiếp: đăng nhập bằng Xtream Codes API
```

### Mô tả đầy đủ

```
Kestrel là trình phát IPTV nhanh, gọn để xem truyền hình trực tiếp trên Android. Đăng nhập bằng URL server, tên đăng nhập và mật khẩu do nhà cung cấp IPTV cấp (Xtream Codes API), danh sách kênh sẽ mở ra sau vài giây, sắp xếp theo thể loại của nhà cung cấp.

★ ĐĂNG NHẬP LÀ XEM
• Đăng nhập bằng URL server, tên đăng nhập và mật khẩu, không cần địa chỉ MAC
• Nhiều profile: chuyển giữa các nhà cung cấp hoặc gói thuê bao chỉ với một chạm
• Xem nhanh thông tin tài khoản: ngày hết hạn, trạng thái và số kết nối cho phép
• Thông báo rõ ràng khi đăng nhập lỗi: sai mật khẩu, tài khoản hết hạn, server lỗi hoặc quá số kết nối

★ LỊCH TV CHO ĐIỆN THOẠI
• Kênh được nhóm theo thể loại, có logo và số kênh
• Yêu thích: gắn sao cho các kênh hay xem
• Tìm kiếm tức thì theo kênh và thể loại, kể cả với hàng chục nghìn kênh
• EPG: xem chương trình đang phát và sắp phát của kênh đang chọn
• Màn hình dọc, ngang và chế độ video toàn màn hình
• Giao diện tối và sáng

★ PHÁT MƯỢT, ỔN ĐỊNH
• Trình phát Media3 / ExoPlayer hiện đại cho luồng trực tiếp HLS và MPEG-TS, hỗ trợ HEVC (H.265)
• Tự động thử lại khi luồng bị đứng hoặc rớt, bắt lại điểm phát trực tiếp mới nhất
• Tự kết nối lại server ở chế độ nền, kênh đang xem vẫn tiếp tục phát
• Tự điều chỉnh bộ đệm theo mạng và cảnh báo khi mạng quá chậm cho kênh đang xem

★ THÔNG TIN LUỒNG CHO NGƯỜI DÙNG NÂNG CAO
• Độ phân giải, tốc độ khung hình, codec video và bitrate
• Thời lượng đệm, độ trễ so với trực tiếp và băng thông đo được
• Chất lượng mạng và lỗi server gần nhất, kèm gợi ý cách khắc phục

★ KESTREL PRO
Nâng cấp Pro để tắt toàn bộ quảng cáo: không quảng cáo khi đổi kênh, không phải xem quảng cáo để tiếp tục và không còn lời nhắc nâng cấp.
• Gói tháng hoặc năm, dùng thử miễn phí 3 ngày cho người dùng đủ điều kiện
• Pro Forever: trả một lần, dùng mãi mãi
• Khôi phục giao dịch trên mọi thiết bị đăng nhập cùng tài khoản Google
Gói thuê bao tự động gia hạn. Huỷ bất cứ lúc nào trong Google Play Store.

BẮT ĐẦU
1. Lấy URL server, tên đăng nhập và mật khẩu từ nhà cung cấp IPTV.
2. Mở Kestrel, chạm Add profile và nhập thông tin.
3. Chạm vào profile để kết nối và chọn kênh.

MẸO
• Nếu không kết nối được server, kiểm tra lại URL (kể cả cổng, ví dụ http://example.com:8080) hoặc thử đổi DNS sang Cloudflare (1.1.1.1).
• Mỗi gói thuê bao giới hạn số kết nối. Hãy tắt các thiết bị khác nếu gặp lỗi "quá số kết nối".

Ứng dụng miễn phí và có quảng cáo.

TUYÊN BỐ MIỄN TRỪ TRÁCH NHIỆM
Kestrel chỉ là trình phát media. Ứng dụng không bao gồm, cung cấp, bán hay lưu trữ bất kỳ kênh truyền hình, danh sách phát, server hoặc gói thuê bao nào. Bạn phải dùng tài khoản của chính mình từ một nhà cung cấp IPTV hợp pháp, và tự chịu trách nhiệm về quyền xem nội dung. Kestrel không liên kết với bất kỳ nhà cung cấp IPTV nào và không ủng hộ việc phát nội dung có bản quyền khi chưa được phép.
```

### Chú thích ảnh chụp màn hình

1. `Đăng nhập là xem`
2. `Kênh theo thể loại`
3. `Yêu thích & tìm kiếm nhanh`
4. `Đang phát gì — EPG`
5. `Phát trực tiếp mượt mà`
6. `Thông tin luồng chi tiết`
7. `Nâng cấp Pro: không quảng cáo`
