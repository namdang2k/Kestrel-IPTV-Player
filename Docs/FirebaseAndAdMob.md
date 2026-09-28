# Firebase, AdMob v? UMP

Hai GameObject ri?ng trong Main ch?a `FirebaseController` v?
`GoogleAdsMobileController` (namespace `StbEmu.Services`). C? hai c? singleton
`Instance`, d?ng `DontDestroyOnLoad`, h?y b?n tr?ng khi Main ???c t?i l?i.
Kh?i t?o ch?y t? Start, kh?ng ch?n Loading. N?u chuy?n controller sang scene
kh?c, gi? m?i controller tr?n m?t GameObject root ri?ng.

## ID qu?ng c?o

Ch?nh `Assets/Resources/Services/AdMobSettings.asset` trong Inspector:

- `Use Test Ads`: ?ang b?t; Editor lu?n d?ng ID test k? c? khi t?t m?c n?y.
- `Android` / `Ios`: App ID (`~`), Banner, Interstitial, Rewarded ID (`/`).
- C?c ID Android hi?n c? ???c gi? nguy?n. C?u h?nh iOS c?n tr?ng.
- T?o asset m?i qua Create > StbEmu > AdMob Settings n?u c?n. Asset ch?nh ?
  ???ng d?n tr?n l? ngu?n c?u h?nh khi build; controller ?? tham chi?u asset n?y.

`AdMobSettingsBuildSync` t? ??ng b? App ID sang settings c?a Google SDK tr??c
build Android/iOS. C?ng c? menu StbEmu > Services > Sync AdMob App IDs.
Thi?u App ID c?a n?n t?ng build s? b?o l?i tr??c build. Thay App ID c?n build
l?i v? SDK ??c App ID t? Android manifest / iOS Info.plist.

## UMP consent

Controller g?i ConsentInformation.Update m?i l?n kh?i ??ng, r?i
LoadAndShowConsentFormIfRequired. UMP t? x?c ??nh v? tr? v? t?nh tr?ng consent;
kh?ng d?ng ng?n ng? m?y ?? suy ?o?n qu?c gia. Form s? hi?n ? EEA v? c?c khu v?c
kh?c theo message ?? c?u h?nh. Ch? kh?i t?o qu?ng c?o khi CanRequestAds tr? v?
true. N?u ki?m tra consent l?i, SDK c? th? d?ng consent c?n h?p l? t? phi?n
tr??c; n?u kh?ng c? th? kh?ng t?i qu?ng c?o. G?i Initialize ?? th? l?i.

Trong AdMob > Privacy & messaging, t?o/publish European regulations message
cho ??ng App ID. Code kh?ng th? t?o ho?c publish message trong t?i kho?n.
Test tr?n thi?t b? th?t ho?c c?u h?nh UMP debug geography/test device theo t?i
li?u Google; ch? ?? test ads kh?ng t? ?p v? tr? ??a l? UMP.

API `PrivacyOptionsRequired`, event `ConsentUpdated`, v? `ShowPrivacyOptions()`
d?ng ?? n?i n?t Privacy options trong m?n h?nh c?i ??t. N?t c?n ???c hi?n th?
khi PrivacyOptionsRequired l? true. Khi m? l?i form, c?c qu?ng c?o ?? cache
???c h?y v? c?c request c? b? b? qua.

## S? d?ng (g?i tr?n Unity main thread)

```csharp
using StbEmu.Services;

// Firebase: await khi c?n d?ng ngay, c?c l?i g?i ??ng th?i d?ng chung task.
if (await FirebaseController.Instance.InitializeAsync())
    FirebaseController.Instance.LogEvent("profile_opened");

var ads = GoogleAdsMobileController.Instance;
// Ch? IsReady ho?c ??ng k? Initialized tr??c khi g?i Load/ShowBanner.
if (ads.IsReady)
{
    ads.LoadInterstitial();
    ads.LoadRewarded();
}
// G?i t?i v? tr? qu?ng c?o ph? h?p, sau khi load th?nh c?ng.
if (ads.IsInterstitialReady) ads.ShowInterstitial();
if (ads.IsRewardedReady)
    ads.ShowRewarded(reward => GrantReward(reward.Type, reward.Amount));
// Banner ch? ???c t?i/hi?n khi ch? ??ng g?i:
ads.ShowBanner();
ads.HideBanner();
```

`GrantReward` l? h?m nghi?p v? c?a app, kh?ng ph?i API SDK. Show tr? false khi
ch?a s?n s?ng/?ang hi?n full screen. Interstitial v? Rewarded ???c load l?i sau
khi ??ng; load th?t b?i kh?ng l?p request v? h?n. Qu?ng c?o cache qu? 55 ph?t
kh?ng ???c show, g?i Load l?i. C?c s? ki?n qu?ng c?o ???c chuy?n v? Unity main
thread. Kh?ng t? g?n qu?ng c?o v?o h?nh vi xem portal ho?c t? c?p ph?n th??ng.

Firebase cung c?p IsReady, LastError, Initialized, Failed, LogEvent, SetUserId,
LogException. Hai t?y ch?n collection Analytics/Crashlytics ? Inspector ??c l?p
v?i UMP (UMP ch? qu?n l? consent qu?ng c?o).

## Ki?m tra tr?n thi?t b?

- Ch?y Loading r?i Main; t?i l?i Main v? ki?m tra m?i controller ch? c?n m?t.
- Ki?m tra Firebase Initialized/Failed; g?i event test v? xem Firebase console.
- D?ng UMP debug device ?? ki?m tra form EEA, ch?p nh?n/t? ch?i, m? l?i privacy
  options, kh?i ??ng l?i app v? tr??ng h?p offline.
- D?ng ads test: load/show t?ng ??nh d?ng; Rewarded ch? c?p th??ng khi nh?n
  callback, kh?ng c?p khi ch? ??ng qu?ng c?o.

?? ki?m tra bi?n d?ch b?ng compiler v? DLL SDK c?a Unity cho Editor/Android,
bao g?m build sync. Ch?a ki?m th? SDK m?ng, form UMP hay qu?ng c?o tr?n thi?t b?.

Ngu?n API:
- https://firebase.google.com/docs/unity/setup
- https://developers.google.com/admob/unity/privacy
- https://developers.google.com/admob/unity/interstitial
- https://github.com/googleads/googleads-mobile-unity/tree/main/samples/HelloWorld/Assets/Scripts

## App Open

`Android / App Open Id` v? `Ios / App Open Id` trong AdMobSettings ch?a ad unit
ID th?t c?a App Open (kh?c App ID c? d?u `~`). Use Test Ads ho?c Editor s?
ch?n ID test App Open c?a Google.

Sau khi controller IsReady, g?i `LoadAppOpen()`. Khi `IsAppOpenReady` l? true,
g?i `ShowAppOpen()` t?i ?i?m m? app ph? h?p. Show tr? false n?u consent ch?a
cho ph?p, qu?ng c?o ch?a s?n s?ng, ho?c ?ang hi?n qu?ng c?o full screen kh?c.
App Open d?ng chung c?c event Loaded/Closed v?i AdFormat.AppOpen, t? load l?i
sau khi ??ng, h?y khi ??i consent ho?c controller b? h?y. Cache h?t h?n sau
4 gi?. Controller t? x? l? c? h?i show l?c startup/resume theo quy t?c b?n d??i.

API/test ID: https://github.com/googleads/googleads-mobile-unity/blob/main/samples/HelloWorld/Assets/Scripts/AppOpenAdController.cs

## Tracking v? callback

`AdCallbacks` d?ng chung cho c?c show request:
`OnShown`, `OnClosed`, `OnFailed(string)`, `OnImpression`, `OnClicked`,
`OnPaid(AdValue)`. Callback ch?y tr?n Unity main thread. M?i request full screen
ch? k?t th?c qua Closed ho?c Failed m?t l?n; khi b? ch?n/ch?a ready th? g?i
Failed ngay v? tr? false. OnShown ch? callback SDK, kh?ng ph?i l?c g?i Show.
V?i Banner, OnShown ch? impression ??u ti?n; HideBanner ch? ?n, DestroyBanner
k?t th?c request v? g?i Closed. Banner auto-refresh v?n g?i Impression/Paid.

```csharp
var ads = GoogleAdsMobileController.Instance;
var callbacks = new AdCallbacks
{
    OnShown = () => UnityEngine.Debug.Log("Ad shown"),
    OnClosed = () => UnityEngine.Debug.Log("Ad closed"),
    OnFailed = reason => UnityEngine.Debug.Log("Ad skipped/failed: " + reason),
    OnImpression = () => UnityEngine.Debug.Log("Impression"),
    OnClicked = () => UnityEngine.Debug.Log("Clicked"),
    OnPaid = value => UnityEngine.Debug.Log(value.Value / 1000000d)
};
ads.ShowInterstitial(callbacks, placement: "profile_exit");
ads.ShowBanner(callbacks: callbacks, placement: "profiles");
ads.ShowRewarded(reward => GrantReward(reward.Type, reward.Amount),
    callbacks, placement: "unlock_feature");
// App Open th??ng do controller t? show; nghe s? ki?n chung ?? x? l? UI:
ads.Closed += format => UnityEngine.Debug.Log("Closed: " + format);
```

V? d? tr?n li?t k? c?c API ri?ng, kh?ng g?i to?n b? qu?ng c?o c?ng m?t l?c.
Global events: Loaded, Shown, Closed, Failed, ImpressionRecorded, Clicked, Paid.
Reward ch? ???c c?p b?i callback SDK Rewarded, kh?ng c?p ? OnClosed.

Tracking g?i qua FirebaseController: `ad_load_request`, `ad_loaded`,
`ad_load_failed`, `ad_show_request`, `ad_show`, `ad_show_failed`, `ad_closed`,
`ad_impression_observed`, `ad_clicked`, `ad_paid`, `ad_reward_earned`.
Th?ng tin chung g?m ad_platform, ad_format, ad_unit_name, placement, is_test_ad;
l?i c? error_message, doanh thu c? value/currency/precision, reward c?
reward_type/reward_amount. Gi? tr? paid chuy?n t? micros sang ??n v? ti?n t?.
Firebase ch?a ready s? gi? t?i ?a 64 event trong RAM, b? event c? nh?t khi ??y;
kh?ng l?u queue qua l?n kh?i ??ng v? kh?ng ghi khi t?t analytics collection.

`ad_impression` chu?n ?? Firebase/AdMob t? ghi khi ?? li?n k?t t?i kho?n.
`Log Manual Ad Impressions` tr?n controller m?c ??nh t?t ?? tr?nh ghi tr?ng.
Ch? b?t n?u kh?ng d?ng reporting t? ??ng: OnAdPaid s? g?i `ad_impression`
thay cho custom `ad_paid`, k?m value/currency. `ad_impression_observed` l? event
ch?n ?o?n ri?ng, kh?ng ph?i event doanh thu chu?n.
Ngu?n: https://firebase.google.com/docs/analytics/measure-ad-revenue

## Th?i gian v? session

C?u h?nh trong AdMobSettings:

- Interstitial Interval Seconds: 60s m?c ??nh.
- App Open Interval Seconds: 30s m?c ??nh.
- First Session Interstitial Delay Seconds: 60s m?c ??nh, t?nh t? l?c app
  b?t ??u, ch? ?p d?ng cho Interstitial ??u ti?n c?a session 1. Asset hi?n t?i
  ?? ???c c?u h?nh th?nh 600s v? gi? tr? n?y ???c gi? nguy?n.

Kho?ng c?ch ???c t?nh t? callback SDK b?o qu?ng c?o tr??c th?c s? m? ??n
l?n y?u c?u show ti?p theo. Kh?ng t?nh t? load, l?n show b? t? ch?i, ho?c t?
th?i ?i?m ??ng qu?ng c?o. Interstitial v? App Open c? b? ??m ??c l?p; ??t 0
?? t?t gi?i h?n t??ng ?ng. B? ??m cooldown gi? qua scene nh?ng reset khi m?
l?i ?ng d?ng. Kh?ng y?u c?u app ? background ?? 30s; ??y l? kho?ng c?ch gi?a
hai l?n qu?ng c?o m?.

Session 1 = l?n m? ??u ti?n sau c?i ??t/x?a d? li?u. Marker ???c l?u b?ng
PlayerPrefs (`StbEmu.Ads.HasStartedSession`). Scene reload kh?ng t?o session
m?i. Trong Editor, m?i l?n Play l? m?t session v? marker c?ng ???c l?u.

App Open ch? ???c ph?p ?:
1. ??u session 2 tr? ?i (kh?ng t? show ? startup session 1).
2. M?t l?n quay l?i sau OnApplicationPause(true -> false), k? c? session 1,
   khi kh?ng b? cooldown.

Controller preload sau khi init/consent ho?n t?t, v? t? show khi ??ng c? h?i
tr?n. C? h?i h?t h?n sau 10s ?? kh?ng chen ngang khi t?i l?u. Kh?ng t?o c? h?i
m?i khi quay l?i t? qu?ng c?o full screen ho?c UMP. N?u resume g?p cooldown,
b? l??t ??, kh?ng ch? ??n gi?a session ?? t? show. ShowAppOpen g?i th? c?ng
c?ng ph?i tu?n th? c? h?i v?o app n?y.

`IsInterstitialReady` / `IsAppOpenReady` ch? b?o cache v? consent s?n s?ng;
`GetTimingBlockReason(format)` cho bi?t cooldown/first delay. Show c?n ki?m tra
full screen lock v? c? h?i v?o app. C?c l? do Failed g?m first_session_delay,
interstitial_cooldown, app_open_cooldown, app_open_not_at_entry,
another_ad_showing, ads_not_available, ad_not_ready, ho?c l?i t? SDK.

Ch?y `powershell -File Tools/Test-AdServices.ps1` ?? ki?m tra ch?nh s?ch th?i
gian, startup/resume v? callback (34 checks, kh?ng g?i SDK native). ?? compile
runtime Editor/Android. V?n c?n th? UMP, pause/resume v? Firebase DebugView
tr?n thi?t b? th?t.
