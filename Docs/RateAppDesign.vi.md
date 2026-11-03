# Thiết kế: Panel đánh giá ứng dụng (Rate app)

Trạng thái: đã code, chưa kiểm tra trên thiết bị.

## 1. Mục tiêu

Hỏi người dùng đã gắn bó với app đánh giá trên Google Play, đúng lúc không làm phiền việc xem:
chỉ ở Home hoặc màn TV (không fullscreen), và không hỏi lại khi họ đã trả lời xong.

## 2. Hành vi

Panel là `AlertDialog` native với 3 nút:

| Nút | Kết quả |
|---|---|
| Rate | Dừng vĩnh viễn. Mở sheet đánh giá của Google ngay trong app (không nhảy ra store). |
| Not now (hoặc Back) | Hiện lại sau khi thời gian app tăng thêm 20 phút. |
| Never | Dừng vĩnh viễn. |

"Dừng vĩnh viễn" là cờ `stop` lưu trong PlayerPrefs, còn nguyên sau khi mở lại app. Khi `stop` bật, bộ đếm thời gian cũng ngừng.

## 3. Điều kiện hiện

Cả bốn điều kiện đều phải đúng:

1. Chưa `stop`.
2. Tổng thời gian foreground của app `appSeconds >= nextAt`. Ban đầu `nextAt = 1200` (20 phút).
3. Thời gian foreground của session hiện tại `>= 300` giây.
4. Đang ở Home, hoặc ở màn TV không fullscreen và không PiP.

Không hiện khi: ad full-screen đang chạy, đang consent, app ở background, đang `adTransition`, đang `RewardPrompt`, hoặc có dialog khác mở (profile editor, Pro, upsell).

### Quy tắc Not now

`nextAt = appSeconds + 1200`, tính từ lúc người dùng bấm, không phải từ mốc cũ.
Ví dụ: prompt bị hoãn tới phút 33 rồi bấm Not now thì lần sau ở phút 53.

### Định nghĩa thời gian

- `appSeconds`: tổng thời gian foreground qua mọi lần mở app, không chỉ lúc phát video. Người chỉ duyệt Home vẫn được tính.
- `sessionSeconds`: thời gian foreground từ lúc process khởi động, đặt lại mỗi lần mở app.
- Cả hai chỉ tăng khi `!appPaused`. Lưu mỗi 30 giây và khi app bị pause.

## 4. Kiến trúc

```
AppFlow.Update (Unity)                       StbHost (native)
  RatePromptController.Tick(dt)
  IsDue && màn hợp lệ && mỗi 10 s  ──────▶  showRatePrompt()
                                               kiểm Home/TV, không PiP, không dialog khác
                                               hiện AlertDialog
  RatePromptController.Record(choice) ◀────  emit ui.rate.rate | later | never
  (rate) ads.MarkExternalOverlay()           (rate) launchReview()
```

| Thành phần | Vai trò |
|---|---|
| `RatePromptState` / `RatePromptPolicy` (`Assets/App/Services/RatePrompt.cs`) | Quy tắc thuần, không phụ thuộc Unity, có test. |
| `RatePromptController` (cùng file) | Lưu PlayerPrefs `StbEmu.Rate.State` (JSON), cộng thời gian, nhận lựa chọn. |
| `AppFlow` | Tick, quyết định thời điểm gọi native, nhận sự kiện `ui.rate.*`. |
| `AndroidStbHost.ShowRatePrompt` | Cầu nối Unity sang native. |
| `StbHost.showRatePrompt` / `launchReview` | Dialog, kiểm vị trí màn, gọi Play In-App Review. |

Native tự kiểm Home/TV (cùng điều kiện với banner: `home.isShown() || (tvScreen.isShown() && tvScreen.canShowBanner())`), vì Unity không biết chắc PiP. Unity chỉ giữ ngưỡng thời gian và các điều kiện về ad.

## 5. Sự kiện native sang Unity

| Sự kiện | Khi nào | Unity làm |
|---|---|---|
| `ui.rate.rate` | Bấm Rate | `stop = true` |
| `ui.rate.sheet` | `requestReviewFlow` thành công, ngay trước `launchReviewFlow` | `MarkExternalOverlay()` |
| `ui.rate.later` | Bấm Not now hoặc Back | `nextAt = appSeconds + 1200` |
| `ui.rate.never` | Bấm Never | `stop = true` |

`ui.rate.sheet` chỉ phát khi sheet thật sự được mở, nên khi quota hết hoặc request lỗi thì không chặn nhầm App Open. Nó phải phát trước `launchReviewFlow()` để Unity xử lý trước khi activity bị pause; thứ tự này chưa kiểm chứng trên máy thật.

## 6. Play In-App Review

- Thư viện: `com.google.android.play:review:2.0.2`, khai báo ở `Native/StbHost/build.gradle` và `Assets/Plugins/Android/mainTemplate.gradle` (ngoài vùng Android Resolver).
- Luồng: `requestReviewFlow()` sau đó `launchReviewFlow(activity, info)`.
- Giới hạn của Google, không tránh được:
  - Có quota không công bố; vượt quota thì sheet không hiện và API không báo lỗi.
  - API không cho biết người dùng đã đánh giá hay chưa.
  - Chỉ hoạt động khi app cài từ Play. Bản sideload bấm Rate sẽ không thấy gì.
- Không có fallback nhảy ra store (theo yêu cầu). Khi `requestReviewFlow` lỗi thì chỉ ghi log.

## 7. Tương tác với phần khác

- **App Open ad**: sheet review pause app. Khi nhận `ui.rate.sheet`, `GoogleAdsMobileController.MarkExternalOverlay()` làm lần resume kế tiếp không mở cửa sổ App Open (như resume từ consent hoặc ad full-screen). Cờ được xóa ở lần resume đó. Nếu sheet được mở nhưng app không bị pause thì cờ còn lại tới lần pause kế tiếp, nên có thể mất đúng một lần App Open.
- **Upsell Pro**: hai dialog loại trừ nhau theo cả hai chiều (`upsell()` bỏ qua khi rate đang mở; `showRatePrompt` bỏ qua khi upsell hoặc Pro đang mở).
- **Orientation**: không động tới. Dialog không gọi `setRequestedOrientation`.
- **Pro**: người dùng Pro vẫn được hỏi, không có điều kiện riêng.

## 8. Test

- `Assets/App/Tests/EditMode/RatePromptTests.cs`: ngưỡng đầu 20 phút và 300 giây session, chu kỳ 20 phút sau Not now, Not now tính từ lúc bấm, `stop` chặn mãi.
- Chưa test: Tick và lưu PlayerPrefs, dialog native, sheet của Google, chặn App Open sau review. Các phần này cần chạy trên máy thật, cài từ Play (internal test track), cả dọc lẫn ngang.

## 9. Ngoài phạm vi

- Không có Remote Config cho các ngưỡng (20 phút, 300 giây, chu kỳ 20 phút là hằng số trong `RatePromptPolicy`).
- Không có fallback sang store khi sheet lỗi.
- Không ghi sự kiện analytics cho prompt.
- Không phân biệt người dùng đã đánh giá từ trước (API không cho biết).
