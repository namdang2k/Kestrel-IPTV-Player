# Google Play Store Listing (ASO) — Kestrel – IPTV Player

- Package: `com.stbemu.kestrel` · Version: 1.0.1
- Primary language: **English (en-US)**. Vietnamese (vi-VN) translation at the end.
- Play limits: App name ≤ 30 chars · Short description ≤ 80 chars · Full description ≤ 4000 chars.
- Replaces `Docs/ASO_GooglePlay.txt` (old "STB Portal Player" listing for Stalker/MAC portals, no longer accurate).

## Before publishing

1. **Scope matches the build.** Supports **Xtream Codes API login** and **M3U playlists (URL or local file)**, with
   **Live TV, VOD movies and Series** (categorized). Do not claim catch-up playback, MAC/Stalker portals, Chromecast,
   Android TV remote support, or "resume where you left off" for VOD: they are not implemented. Catch-up channels only show a badge.
2. **Trademarks.** No "IPTV Smarters", "TiviMate", "MAG", "Infomir", "StbEmu" or other app/brand names in the title, icon,
   screenshots or text. "Xtream" / "Xtream Codes API" and "M3U" are used descriptively (compatibility) in the short and full description.
3. **IPTV policy.** Play reviews IPTV players strictly. Keep the disclaimer. Screenshots/video must not show copyrighted
   channels, logos of real broadcasters or a real provider's server. Use a demo server with neutral test streams or blurred content.
4. **Play Console declarations.**
   - Contains ads: **Yes** (AdMob: banner, interstitial, rewarded, app open).
   - In-app purchases: **Yes** — Monthly / Yearly subscription (`kestrel_pro`), Pro Forever (`kestrel_pro_forever`).
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
M3U & Xtream IPTV player with Live TV, EPG, VOD, Series. Fast ExoPlayer.
```

Alternatives:

- `Watch your IPTV provider's live channels with EPG, favorites and quick search`
- `Simple, fast live TV player for your IPTV subscription. Sign in and watch.`

### Full description

```
Kestrel is a fast, modern IPTV player built for Xtream Codes API and M3U playlists. It turns your Android device into a simple home for Live TV, Movies and Series from your own IPTV provider.

Powered by the Media3 / ExoPlayer engine for smooth, high-quality playback. Sign in with your Xtream credentials or load your M3U playlist and start watching in seconds.

★ CORE FEATURES
• Xtream Codes support: log in with server URL, username and password, no MAC address needed
• M3U playlist support: load a playlist from a URL or a local file
• Live TV, VOD & Series: neatly categorized for easy browsing
• EPG: see what's on now and next for the selected channel
• Favorites: star the channels you watch most
• Instant search across channels and categories, even with tens of thousands of channels
• Multiple profiles: switch between providers or playlists in one tap
• Portrait and landscape, full-screen video mode, dark and light themes

★ SMOOTH, RELIABLE PLAYBACK
• Media3 / ExoPlayer for HLS and MPEG-TS streams, including HEVC (H.265)
• Automatic retry when a stream stalls or drops, rejoining the live edge
• Reconnects to your server in the background and keeps the current channel playing
• Adapts buffering to your network and warns you when the connection is too slow

★ STREAM INFO FOR POWER USERS
• Resolution, frame rate, video codec and bitrate
• Buffered time, live latency and measured bandwidth

★ KESTREL PRO
Go Pro to remove all ads: no ads when changing channels, no "watch an ad" breaks and no upgrade reminders.
• Monthly or Yearly subscription with a 3-day free trial for eligible users
• Pro Forever: pay once, keep it forever
• Restore Purchase on any device signed in to the same Google account
Subscriptions renew automatically. Cancel anytime in the Google Play Store.

HOW TO START
1. Get the server URL, username and password (or an M3U playlist link) from your IPTV provider.
2. Open Kestrel, tap Add profile and enter them. M3U is selected by default.
3. Tap the profile to connect and pick a channel.

TIPS
• If the server cannot be reached, check the URL (including the port, e.g. http://example.com:8080) or try Cloudflare DNS (1.1.1.1).
• Each subscription allows a limited number of connections. Close other devices if you see "too many connections".

The app is free and supported by ads.

DISCLAIMER
Kestrel is a media player only. It does not include, provide, sell or host any TV channels, playlists, servers or subscriptions. You must use your own content from a legitimately authorized IPTV provider, and you are responsible for having the rights to the content you watch. Kestrel is not affiliated with any IPTV provider, and we do not endorse streaming copyrighted content without permission.
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

iptv player, iptv, m3u, m3u playlist, live tv, vod, series, xtream codes, xtream codes api, iptv xtream player, epg, tv guide, tv channels,
hls player, m3u8 player, hevc player, iptv subscription, video player

### What's new (≤ 500)

```
• M3U playlists: load from a URL or a local file
• VOD movies and Series with episode selection
• New profiles default to M3U; the profile name is optional
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
Trình phát IPTV M3U & Xtream: Live TV, EPG, VOD, Series. ExoPlayer nhanh.
```

### Mô tả đầy đủ

```
Kestrel là trình phát IPTV nhanh, hiện đại dành cho Xtream Codes API và danh sách phát M3U. Ứng dụng biến thiết bị Android của bạn thành nơi xem Live TV, Phim và Series từ nhà cung cấp IPTV của chính bạn.

Dùng engine Media3 / ExoPlayer để phát mượt, chất lượng cao. Đăng nhập bằng tài khoản Xtream hoặc nạp danh sách M3U là xem được ngay.

★ TÍNH NĂNG CHÍNH
• Hỗ trợ Xtream Codes: đăng nhập bằng URL server, tên đăng nhập và mật khẩu, không cần địa chỉ MAC
• Hỗ trợ danh sách M3U: nạp từ URL hoặc file trong máy
• Live TV, VOD & Series: sắp xếp theo thể loại, dễ duyệt
• EPG: xem chương trình đang phát và sắp phát của kênh đang chọn
• Yêu thích: gắn sao cho các kênh hay xem
• Tìm kiếm tức thì theo kênh và thể loại, kể cả với hàng chục nghìn kênh
• Nhiều profile: chuyển giữa các nhà cung cấp hoặc danh sách phát chỉ với một chạm
• Màn hình dọc, ngang, video toàn màn hình, giao diện tối và sáng

★ PHÁT MƯỢT, ỔN ĐỊNH
• Media3 / ExoPlayer cho luồng HLS và MPEG-TS, hỗ trợ HEVC (H.265)
• Tự động thử lại khi luồng bị đứng hoặc rớt, bắt lại điểm phát trực tiếp mới nhất
• Tự kết nối lại server ở chế độ nền, kênh đang xem vẫn tiếp tục phát
• Tự điều chỉnh bộ đệm theo mạng và cảnh báo khi mạng quá chậm

★ THÔNG TIN LUỒNG CHO NGƯỜI DÙNG NÂNG CAO
• Độ phân giải, tốc độ khung hình, codec video và bitrate
• Thời lượng đệm, độ trễ so với trực tiếp và băng thông đo được

★ KESTREL PRO
Nâng cấp Pro để tắt toàn bộ quảng cáo: không quảng cáo khi đổi kênh, không phải xem quảng cáo để tiếp tục và không còn lời nhắc nâng cấp.
• Gói tháng hoặc năm, dùng thử miễn phí 3 ngày cho người dùng đủ điều kiện
• Pro Forever: trả một lần, dùng mãi mãi
• Khôi phục giao dịch trên mọi thiết bị đăng nhập cùng tài khoản Google
Gói thuê bao tự động gia hạn. Huỷ bất cứ lúc nào trong Google Play Store.

BẮT ĐẦU
1. Lấy URL server, tên đăng nhập và mật khẩu (hoặc link danh sách M3U) từ nhà cung cấp IPTV.
2. Mở Kestrel, chạm Add profile và nhập thông tin. M3U được chọn sẵn.
3. Chạm vào profile để kết nối và chọn kênh.

MẸO
• Nếu không kết nối được server, kiểm tra lại URL (kể cả cổng, ví dụ http://example.com:8080) hoặc thử đổi DNS sang Cloudflare (1.1.1.1).
• Mỗi gói thuê bao giới hạn số kết nối. Hãy tắt các thiết bị khác nếu gặp lỗi "quá số kết nối".

Ứng dụng miễn phí và có quảng cáo.

TUYÊN BỐ MIỄN TRỪ TRÁCH NHIỆM
Kestrel chỉ là trình phát media. Ứng dụng không bao gồm, cung cấp, bán hay lưu trữ bất kỳ kênh truyền hình, danh sách phát, server hoặc gói thuê bao nào. Bạn phải dùng nội dung của chính mình từ một nhà cung cấp IPTV được cấp phép hợp pháp, và tự chịu trách nhiệm về quyền xem nội dung. Kestrel không liên kết với bất kỳ nhà cung cấp IPTV nào và không ủng hộ việc phát nội dung có bản quyền khi chưa được phép.
```

### Chú thích ảnh chụp màn hình

1. `Đăng nhập là xem`
2. `Kênh theo thể loại`
3. `Yêu thích & tìm kiếm nhanh`
4. `Đang phát gì — EPG`
5. `Phát trực tiếp mượt mà`
6. `Thông tin luồng chi tiết`
7. `Nâng cấp Pro: không quảng cáo`
