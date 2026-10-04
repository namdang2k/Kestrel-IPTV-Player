using System;
using Firebase.Analytics;
using GoogleMobileAds.Api;
using GoogleMobileAds.Common;
using GoogleMobileAds.Ump.Api;
using UnityEngine;

namespace StbEmu.Services
{
    [DisallowMultipleComponent]
    public sealed class GoogleAdsMobileController : MonoBehaviour
    {
        public static GoogleAdsMobileController Instance { get; private set; }
        [SerializeField] AdMobSettings settings;
        [SerializeField] bool initializeOnStart = true;
        [Tooltip("Enable only if automatic AdMob/Firebase ad_impression reporting is not in use.")]
        [SerializeField] bool logManualAdImpressions;
        public event Action<AdFormat> Shown;
        public event Action<AdFormat> ImpressionRecorded;
        public event Action<AdFormat> Clicked;
        public event Action<AdFormat, AdValue> Paid;
        AdShowSession bannerSession, interstitialSession, rewardedSession, appOpenSession;
        string bannerPlacement = "", interstitialPlacement = "", rewardedPlacement = "", appOpenPlacement = "";
        bool bannerWantsVisible;

        public bool IsReady { get; private set; }
        public bool IsInitializing { get; private set; }
        public bool IsShowingFullScreen { get; private set; }
        public bool IsConsentFlowRunning { get; private set; }
        bool appOpenSuppressed;
        public void SetAppOpenSuppressed(bool suppressed)
        {
            appOpenSuppressed = suppressed;
            if (suppressed) appOpenEntry?.Consume();
        }
        public string LastError { get; private set; }
        public event Action Initialized;
        public event Action<string> Failed;
        public event Action<AdFormat> Loaded;
        public event Action<AdFormat> Closed;
        public event Action ConsentUpdated;
        public bool PrivacyOptionsRequired => ConsentInformation.PrivacyOptionsRequirementStatus == PrivacyOptionsRequirementStatus.Required;
        const string FirstSessionCompletedKey = "StbEmu.Ads.HasStartedSession";
        public bool IsFirstSession { get; private set; }
        static bool? firstSessionForLaunch;
        static AdDisplayTiming timingForLaunch;
        static bool startupOpportunityCreated;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
        static void ResetLaunchState()
        {
            firstSessionForLaunch = null;
            timingForLaunch = null;
            startupOpportunityCreated = false;
        }
        AdDisplayTiming displayTiming;
        AppOpenEntryPolicy appOpenEntry;
        bool disposed;
        int generation;
        BannerView banner;
        InterstitialAd interstitial;
        RewardedAd rewarded;
        AppOpenAd appOpen;
        bool loadingInterstitial, loadingRewarded, loadingAppOpen;
        readonly int[] adUnitIndexes = new int[4];
        readonly DateTime[] adRetryBlockedUntil = new DateTime[4];
        DateTime interstitialLoadedAt, rewardedLoadedAt, appOpenLoadedAt;
        public bool IsInterstitialReady => CanRequest && interstitial != null && interstitial.CanShowAd() && DateTime.UtcNow - interstitialLoadedAt < TimeSpan.FromMinutes(55);
        public bool IsRewardedReady => CanRequest && rewarded != null && rewarded.CanShowAd() && DateTime.UtcNow - rewardedLoadedAt < TimeSpan.FromMinutes(55);
        public bool IsAppOpenReady => CanRequest && appOpen != null && appOpen.CanShowAd() && DateTime.UtcNow - appOpenLoadedAt < TimeSpan.FromHours(4);
        bool CanRequest => !disposed && IsReady && !IsConsentFlowRunning && ConsentInformation.CanRequestAds();
        static bool ForIos => Application.platform == RuntimePlatform.IPhonePlayer;

        void Awake()
        {
            if (Instance != null && Instance != this) { Destroy(gameObject); return; }
            Instance = this;
            if (!firstSessionForLaunch.HasValue)
            {
                firstSessionForLaunch = PlayerPrefs.GetInt(FirstSessionCompletedKey, 0) == 0;
                PlayerPrefs.SetInt(FirstSessionCompletedKey, 1);
                PlayerPrefs.Save();
                timingForLaunch = new AdDisplayTiming(firstSessionForLaunch.Value);
            }
            IsFirstSession = firstSessionForLaunch.Value;
            displayTiming = timingForLaunch;
            appOpenEntry = new AppOpenEntryPolicy(IsFirstSession || startupOpportunityCreated, Time.realtimeSinceStartupAsDouble);
            startupOpportunityCreated = true;
            DontDestroyOnLoad(gameObject);
            if (settings == null) settings = Resources.Load<AdMobSettings>(AdMobSettings.ResourcePath);
        }

        void Start() { if (initializeOnStart) Initialize(); }

        // All public methods must be called from Unity's main thread.
        public void Initialize()
        {
            if (disposed || IsReady || IsInitializing) return;
            if (settings == null) { Fail("Assign AdMobSettings or add it under Resources/Services/AdMobSettings."); return; }
            if (!Application.isEditor && Application.platform != RuntimePlatform.Android && !ForIos)
            { Fail("Mobile Ads supports Android and iOS only."); return; }
            var appId = settings.GetAppId(ForIos);
            if (!AdMobSettings.IsValidAppId(appId))
            {
                Fail("Missing or invalid " + (ForIos ? "iOS" : "Android") + " AdMob App ID in AdMobSettings.");
                return;
            }
            Debug.Log($"[Ads] Init platform={(ForIos ? "iOS" : "Android")} appId={appId}", this);
            IsInitializing = true;
            IsConsentFlowRunning = true;
            LastError = null;
            // OnMain queues onto this executor, which only MobileAds creates; the consent callbacks run before MobileAds exists.
            MobileAdsEventExecutor.Initialize();
            ConsentInformation.Update(new ConsentRequestParameters(), error => OnMain(() =>
            {
                if (error != null) { Fail(error.Message); FinishConsent(); return; }
                ConsentForm.LoadAndShowConsentFormIfRequired(formError => OnMain(() =>
                {
                    if (formError != null) Fail(formError.Message);
                    FinishConsent();
                }));
            }));
        }

        void FinishConsent()
        {
            IsConsentFlowRunning = false;
            Debug.Log($"[Ads] Consent done: status={ConsentInformation.ConsentStatus}, canRequestAds={ConsentInformation.CanRequestAds()}");
            ConsentUpdated?.Invoke();
            if (!ConsentInformation.CanRequestAds())
            {
                IsInitializing = false;
                Fail("Ads cannot be requested with the current consent status.");
                return;
            }
            MobileAds.Initialize(status => OnMain(() =>
            {
                IsInitializing = false;
                if (status == null) { Fail("Mobile Ads initialization returned no status."); return; }
                IsReady = true;
                LastError = null;
                Debug.Log("[Ads] Mobile Ads initialized" + (settings.UseTestAds || Application.isEditor ? " (test ads)" : "") +
                    (IsFirstSession ? ", first session: no app open at startup" : ""));
                foreach (var adapter in status.getAdapterStatusMap())
                    Debug.Log($"[Ads] Adapter {adapter.Key}: {adapter.Value.InitializationState} {adapter.Value.Description}");
                AdShowSession.Invoke(() => Initialized?.Invoke());
                HandleAppOpenEntry();
            }));
        }

        public void ShowPrivacyOptions()
        {
            if (disposed || IsInitializing || IsConsentFlowRunning || !PrivacyOptionsRequired || IsShowingFullScreen) return;
            IsConsentFlowRunning = true;
            DestroyAds();
            ConsentForm.ShowPrivacyOptionsForm(error => OnMain(() =>
            {
                IsConsentFlowRunning = false;
                if (error != null) Fail(error.Message);
                ConsentUpdated?.Invoke();
                if (!IsReady && ConsentInformation.CanRequestAds()) Initialize();
            }));
        }

        bool TryGetId(AdFormat format, out string id)
        {
            id = null;
            if (!CanRequest) return false;
            var ids = settings.GetAdUnitIds(format, ForIos, Application.isEditor);
            var index = (int)format;
            while (adUnitIndexes[index] < ids.Count)
            {
                id = ids[adUnitIndexes[index]];
                if (AdMobSettings.IsValidAdUnitId(id)) return true;
                adUnitIndexes[index]++;
            }
            Fail("Missing or invalid " + format + " ad unit ID in AdMobSettings.");
            return false;
        }

        bool IsRetryBlocked(AdFormat format) => DateTime.UtcNow < adRetryBlockedUntil[(int)format];

        static bool IsNoFill(LoadAdError error) => error != null && error.GetCode() == 3;

        static string DescribeLoadError(LoadAdError error)
        {
            if (error == null) return "Load returned no ad.";
            var response = error.GetResponseInfo();
            return $"code={error.GetCode()} domain={error.GetDomain()} message={error.GetMessage()}" +
                (response == null ? "" : $" response={response}");
        }

        void ApplyLoadFailureBackoff(AdFormat format, LoadAdError error)
        {
            // Code 3 / HTTP 403 is an ad-server rejection or no-fill condition. Do not hammer
            // the endpoint after the configured fallback IDs are exhausted; try again later.
            if (IsNoFill(error)) adRetryBlockedUntil[(int)format] = DateTime.UtcNow.AddMinutes(2);
        }

        bool TryNextAdUnit(AdFormat format)
        {
            var ids = settings.GetAdUnitIds(format, ForIos, Application.isEditor);
            var index = (int)format;
            if (adUnitIndexes[index] + 1 >= ids.Count)
            {
                adUnitIndexes[index] = 0;
                return false;
            }
            adUnitIndexes[index]++;
            Debug.Log($"[Ads] {format} ad unit failed; trying fallback {adUnitIndexes[index] + 1}/{ids.Count}");
            return true;
        }

        public bool ShowBanner(AdPosition position = AdPosition.Bottom, AdCallbacks callbacks = null, string placement = "")
        {
            if (!TryGetId(AdFormat.Banner, out var id)) return RejectShow(AdFormat.Banner, callbacks, placement);
            DestroyBanner();
            var session = new AdShowSession(callbacks);
            var view = new BannerView(id, AdSize.Banner, position);
            banner = view;
            bannerSession = session;
            bannerPlacement = placement;
            bannerWantsVisible = true;
            Track("ad_load_request", AdFormat.Banner, placement);
            view.OnBannerAdLoaded += () => OnMain(() =>
            {
                if (banner != view) return;
                Track("ad_loaded", AdFormat.Banner, placement);
                AdShowSession.Invoke(() => Loaded?.Invoke(AdFormat.Banner));
                if (bannerWantsVisible) view.Show(); else view.Hide();
            });
            view.OnBannerAdLoadFailed += error => OnMain(() =>
            {
                if (banner != view) return;
                banner = null;
                bannerSession = null;
                view.Destroy();
                if (TryNextAdUnit(AdFormat.Banner))
                {
                    ShowBanner(position, callbacks, placement);
                    return;
                }
                ApplyLoadFailureBackoff(AdFormat.Banner, error);
                var message = DescribeLoadError(error);
                ReportAdFailure("ad_load_failed", AdFormat.Banner, placement, message);
                session.Fail(message);
            });
            bool bannerShown = false;
            view.OnAdImpressionRecorded += () => OnMain(() =>
            {
                if (banner != view) return;
                // Loading a banner isn't proof it was shown. The first impression is.
                if (!bannerShown)
                {
                    bannerShown = true;
                    Track("ad_show", AdFormat.Banner, placement);
                    session.Shown();
                    AdShowSession.Invoke(() => Shown?.Invoke(AdFormat.Banner));
                }
                RecordImpression(AdFormat.Banner, placement, session);
            });
            view.OnAdClicked += () => OnMain(() => { if (banner == view) RecordClick(AdFormat.Banner, placement, session); });
            view.OnAdPaid += value => OnMain(() => { if (banner == view) RecordPaid(AdFormat.Banner, placement, value, session); });
            view.LoadAd(new AdRequest());
            return true;
        }

        public void HideBanner()
        {
            bannerWantsVisible = false;
            banner?.Hide();
        }

        public void DestroyBanner()
        {
            var view = banner;
            var session = bannerSession;
            banner = null;
            bannerSession = null;
            bannerWantsVisible = false;
            view?.Destroy();
            if (session == null) return;
            Track("ad_closed", AdFormat.Banner, bannerPlacement);
            session.Close();
            AdShowSession.Invoke(() => Closed?.Invoke(AdFormat.Banner));
        }

        public void LoadInterstitial()
        {
            if (loadingInterstitial || IsShowingFullScreen || IsInterstitialReady || IsRetryBlocked(AdFormat.Interstitial)
                || !TryGetId(AdFormat.Interstitial, out var id)) return;
            interstitial?.Destroy();
            interstitial = null;
            loadingInterstitial = true;
            int requestGeneration = generation;
            Track("ad_load_request", AdFormat.Interstitial);
            InterstitialAd.Load(id, new AdRequest(), (ad, error) => MobileAdsEventExecutor.ExecuteInUpdate(() =>
            {
                if (disposed || this == null || requestGeneration != generation) { ad?.Destroy(); return; }
                loadingInterstitial = false;
                if (error != null || ad == null)
                {
                    ad?.Destroy();
                    if (TryNextAdUnit(AdFormat.Interstitial))
                    {
                        LoadInterstitial();
                        return;
                    }
                    ApplyLoadFailureBackoff(AdFormat.Interstitial, error);
                    ReportAdFailure("ad_load_failed", AdFormat.Interstitial, "", DescribeLoadError(error));
                    return;
                }
                if (!CanRequest) { ad.Destroy(); return; }
                interstitial = ad;
                interstitialLoadedAt = DateTime.UtcNow;
                ad.OnAdFullScreenContentOpened += () => OnMain(() =>
                {
                    if (interstitial != ad) return;
                    displayTiming.RecordShown(AdFormat.Interstitial, Time.realtimeSinceStartupAsDouble);
                    Track("ad_show", AdFormat.Interstitial, interstitialPlacement);
                    interstitialSession?.Shown();
                    AdShowSession.Invoke(() => Shown?.Invoke(AdFormat.Interstitial));
                });
                ad.OnAdImpressionRecorded += () => OnMain(() => { if (interstitial == ad) RecordImpression(AdFormat.Interstitial, interstitialPlacement, interstitialSession); });
                ad.OnAdClicked += () => OnMain(() => { if (interstitial == ad) RecordClick(AdFormat.Interstitial, interstitialPlacement, interstitialSession); });
                ad.OnAdPaid += value => OnMain(() => { if (interstitial == ad) RecordPaid(AdFormat.Interstitial, interstitialPlacement, value, interstitialSession); });
                ad.OnAdFullScreenContentClosed += () => OnMain(() => FinishInterstitial(ad, null));
                ad.OnAdFullScreenContentFailed += failure => OnMain(() => FinishInterstitial(ad, failure.ToString()));
                Track("ad_loaded", AdFormat.Interstitial);
                AdShowSession.Invoke(() => Loaded?.Invoke(AdFormat.Interstitial));
            }));
        }

        void FinishInterstitial(InterstitialAd ad, string error)
        {
            if (interstitial != ad) return;
            var session = interstitialSession;
            var placement = interstitialPlacement;
            interstitial = null;
            interstitialSession = null;
            IsShowingFullScreen = false;
            ad.Destroy();
            if (error != null)
            {
                ReportAdFailure("ad_show_failed", AdFormat.Interstitial, placement, error);
                session?.Fail(error);
            }
            else
            {
                Track("ad_closed", AdFormat.Interstitial, placement);
                session?.Close();
                AdShowSession.Invoke(() => Closed?.Invoke(AdFormat.Interstitial));
            }
            LoadInterstitial();
        }

        public bool ShowInterstitial(AdCallbacks callbacks = null, string placement = "")
        {
            if (IsShowingFullScreen || !IsInterstitialReady) return RejectShow(AdFormat.Interstitial, callbacks, placement);
            var timingBlock = GetTimingBlockReason(AdFormat.Interstitial);
            if (timingBlock != null) return RejectShow(AdFormat.Interstitial, callbacks, placement, timingBlock);
            IsShowingFullScreen = true;
            interstitialSession = new AdShowSession(callbacks);
            interstitialPlacement = placement;
            var ad = interstitial;
            Track("ad_show_request", AdFormat.Interstitial, placement);
            try
            {
                ad.Show();
                return true;
            }
            catch (Exception error)
            {
                FinishInterstitial(ad, error.Message);
                return false;
            }
        }

        public void LoadAppOpen()
        {
            if (loadingAppOpen || IsShowingFullScreen || IsAppOpenReady || IsRetryBlocked(AdFormat.AppOpen)
                || !TryGetId(AdFormat.AppOpen, out var id)) return;
            appOpen?.Destroy();
            appOpen = null;
            loadingAppOpen = true;
            int requestGeneration = generation;
            Track("ad_load_request", AdFormat.AppOpen);
            AppOpenAd.Load(id, new AdRequest(), (ad, error) => MobileAdsEventExecutor.ExecuteInUpdate(() =>
            {
                if (disposed || this == null || requestGeneration != generation) { ad?.Destroy(); return; }
                loadingAppOpen = false;
                if (error != null || ad == null)
                {
                    ad?.Destroy();
                    if (TryNextAdUnit(AdFormat.AppOpen))
                    {
                        LoadAppOpen();
                        return;
                    }
                    ApplyLoadFailureBackoff(AdFormat.AppOpen, error);
                    ReportAdFailure("ad_load_failed", AdFormat.AppOpen, "", DescribeLoadError(error));
                    return;
                }
                if (!CanRequest) { ad.Destroy(); return; }
                appOpen = ad;
                appOpenLoadedAt = DateTime.UtcNow;
                ad.OnAdFullScreenContentOpened += () => OnMain(() =>
                {
                    if (appOpen != ad) return;
                    displayTiming.RecordShown(AdFormat.AppOpen, Time.realtimeSinceStartupAsDouble);
                    Track("ad_show", AdFormat.AppOpen, appOpenPlacement);
                    appOpenSession?.Shown();
                    AdShowSession.Invoke(() => Shown?.Invoke(AdFormat.AppOpen));
                });
                ad.OnAdImpressionRecorded += () => OnMain(() => { if (appOpen == ad) RecordImpression(AdFormat.AppOpen, appOpenPlacement, appOpenSession); });
                ad.OnAdClicked += () => OnMain(() => { if (appOpen == ad) RecordClick(AdFormat.AppOpen, appOpenPlacement, appOpenSession); });
                ad.OnAdPaid += value => OnMain(() => { if (appOpen == ad) RecordPaid(AdFormat.AppOpen, appOpenPlacement, value, appOpenSession); });
                ad.OnAdFullScreenContentClosed += () => OnMain(() => FinishAppOpen(ad, null));
                ad.OnAdFullScreenContentFailed += failure => OnMain(() => FinishAppOpen(ad, failure.ToString()));
                Track("ad_loaded", AdFormat.AppOpen);
                AdShowSession.Invoke(() => Loaded?.Invoke(AdFormat.AppOpen));
                TryShowAppOpenAtEntry();
            }));
        }

        void FinishAppOpen(AppOpenAd ad, string error)
        {
            if (appOpen != ad) return;
            var session = appOpenSession;
            var placement = appOpenPlacement;
            appOpen = null;
            appOpenSession = null;
            IsShowingFullScreen = false;
            ad.Destroy();
            if (error != null)
            {
                ReportAdFailure("ad_show_failed", AdFormat.AppOpen, placement, error);
                session?.Fail(error);
            }
            else
            {
                Track("ad_closed", AdFormat.AppOpen, placement);
                session?.Close();
                AdShowSession.Invoke(() => Closed?.Invoke(AdFormat.AppOpen));
            }
            LoadAppOpen();
        }

        public bool ShowAppOpen(AdCallbacks callbacks = null, string placement = "")
        {
            if (appOpenSuppressed || appOpenEntry == null || !appOpenEntry.CanShow(Time.realtimeSinceStartupAsDouble))
                return RejectShow(AdFormat.AppOpen, callbacks, placement, "app_open_not_at_entry");
            if (IsShowingFullScreen || !IsAppOpenReady) return RejectShow(AdFormat.AppOpen, callbacks, placement);
            var timingBlock = GetTimingBlockReason(AdFormat.AppOpen);
            if (timingBlock != null) return RejectShow(AdFormat.AppOpen, callbacks, placement, timingBlock);
            IsShowingFullScreen = true;
            appOpenEntry.Consume();
            appOpenSession = new AdShowSession(callbacks);
            appOpenPlacement = placement;
            var ad = appOpen;
            Track("ad_show_request", AdFormat.AppOpen, placement);
            try
            {
                ad.Show();
                return true;
            }
            catch (Exception error)
            {
                FinishAppOpen(ad, error.Message);
                return false;
            }
        }

        public void LoadRewarded()
        {
            if (loadingRewarded || IsShowingFullScreen || IsRewardedReady || IsRetryBlocked(AdFormat.Rewarded)
                || !TryGetId(AdFormat.Rewarded, out var id)) return;
            rewarded?.Destroy();
            rewarded = null;
            loadingRewarded = true;
            int requestGeneration = generation;
            Track("ad_load_request", AdFormat.Rewarded);
            RewardedAd.Load(id, new AdRequest(), (ad, error) => MobileAdsEventExecutor.ExecuteInUpdate(() =>
            {
                if (disposed || this == null || requestGeneration != generation) { ad?.Destroy(); return; }
                loadingRewarded = false;
                if (error != null || ad == null)
                {
                    ad?.Destroy();
                    if (TryNextAdUnit(AdFormat.Rewarded))
                    {
                        LoadRewarded();
                        return;
                    }
                    ApplyLoadFailureBackoff(AdFormat.Rewarded, error);
                    ReportAdFailure("ad_load_failed", AdFormat.Rewarded, "", DescribeLoadError(error));
                    return;
                }
                if (!CanRequest) { ad.Destroy(); return; }
                rewarded = ad;
                rewardedLoadedAt = DateTime.UtcNow;
                ad.OnAdFullScreenContentOpened += () => OnMain(() =>
                {
                    if (rewarded != ad) return;
                    displayTiming.RecordShown(AdFormat.Rewarded, Time.realtimeSinceStartupAsDouble);
                    Track("ad_show", AdFormat.Rewarded, rewardedPlacement);
                    rewardedSession?.Shown();
                    AdShowSession.Invoke(() => Shown?.Invoke(AdFormat.Rewarded));
                });
                ad.OnAdImpressionRecorded += () => OnMain(() => { if (rewarded == ad) RecordImpression(AdFormat.Rewarded, rewardedPlacement, rewardedSession); });
                ad.OnAdClicked += () => OnMain(() => { if (rewarded == ad) RecordClick(AdFormat.Rewarded, rewardedPlacement, rewardedSession); });
                ad.OnAdPaid += value => OnMain(() => { if (rewarded == ad) RecordPaid(AdFormat.Rewarded, rewardedPlacement, value, rewardedSession); });
                ad.OnAdFullScreenContentClosed += () => OnMain(() => FinishRewarded(ad, null));
                ad.OnAdFullScreenContentFailed += failure => OnMain(() => FinishRewarded(ad, failure.ToString()));
                Track("ad_loaded", AdFormat.Rewarded);
                AdShowSession.Invoke(() => Loaded?.Invoke(AdFormat.Rewarded));
            }));
        }

        void FinishRewarded(RewardedAd ad, string error)
        {
            if (rewarded != ad) return;
            var session = rewardedSession;
            var placement = rewardedPlacement;
            rewarded = null;
            rewardedSession = null;
            IsShowingFullScreen = false;
            ad.Destroy();
            if (error != null)
            {
                ReportAdFailure("ad_show_failed", AdFormat.Rewarded, placement, error);
                session?.Fail(error);
            }
            else
            {
                Track("ad_closed", AdFormat.Rewarded, placement);
                session?.Close();
                AdShowSession.Invoke(() => Closed?.Invoke(AdFormat.Rewarded));
            }
            LoadRewarded();
        }

        public bool ShowRewarded(Action<Reward> onReward, AdCallbacks callbacks = null, string placement = "")
        {
            if (IsShowingFullScreen || !IsRewardedReady) return RejectShow(AdFormat.Rewarded, callbacks, placement);
            IsShowingFullScreen = true;
            rewardedSession = new AdShowSession(callbacks);
            rewardedPlacement = placement;
            var ad = rewarded;
            Track("ad_show_request", AdFormat.Rewarded, placement);
            try
            {
                bool granted = false;
                ad.Show(reward => OnMain(() =>
                {
                    if (granted) return;
                    granted = true;
                    Track("ad_reward_earned", AdFormat.Rewarded, placement,
                        new Parameter("reward_type", reward.Type ?? ""), new Parameter("reward_amount", reward.Amount));
                    AdShowSession.Invoke(() => onReward?.Invoke(reward));
                }));
                return true;
            }
            catch (Exception error)
            {
                FinishRewarded(ad, error.Message);
                return false;
            }
        }

        void OnApplicationPause(bool paused)
        {
            if (Instance != this || appOpenEntry == null) return;
            if (paused)
                appOpenEntry.Pause(IsShowingFullScreen || IsConsentFlowRunning);
            else
            {
                appOpenEntry.Resume(Time.realtimeSinceStartupAsDouble, IsShowingFullScreen || IsConsentFlowRunning);
                HandleAppOpenEntry();
            }
        }

        void HandleAppOpenEntry()
        {
            if (appOpenEntry != null)
                Debug.Log($"[Ads] App entry: eligible={appOpenEntry.CanShow(Time.realtimeSinceStartupAsDouble)}, suppressed={appOpenSuppressed}, " +
                    $"canRequest={CanRequest}, fullScreen={IsShowingFullScreen}, timing={GetTimingBlockReason(AdFormat.AppOpen) ?? "ok"}, " +
                    $"ready={IsAppOpenReady}, loading={loadingAppOpen}");
            if (appOpenSuppressed || !CanRequest || appOpenEntry == null || !appOpenEntry.IsForeground) return;
            if (IsShowingFullScreen || GetTimingBlockReason(AdFormat.AppOpen) != null)
                appOpenEntry.Consume(); // Do not display later, after the user has resumed using the app.
            if (IsAppOpenReady) TryShowAppOpenAtEntry();
            else LoadAppOpen(); // Preload even in session 1, for a later legitimate resume.
        }

        void TryShowAppOpenAtEntry()
        {
            if (appOpenSuppressed || !IsAppOpenReady || appOpenEntry == null || !appOpenEntry.CanShow(Time.realtimeSinceStartupAsDouble))
            {
                if (appOpenEntry != null && appOpenEntry.IsForeground) // a load finishing outside the 10 s entry window lands here
                    Debug.Log($"[Ads] App open kept for the next entry: suppressed={appOpenSuppressed}, ready={IsAppOpenReady}, " +
                        $"eligible={appOpenEntry.CanShow(Time.realtimeSinceStartupAsDouble)}");
                return;
            }
            if (IsShowingFullScreen || GetTimingBlockReason(AdFormat.AppOpen) != null)
            {
                appOpenEntry.Consume();
                return;
            }
            ShowAppOpen(placement: "app_entry");
        }

        public string GetTimingBlockReason(AdFormat format) => settings == null || displayTiming == null ? null :
            displayTiming.GetBlockReason(format, Time.realtimeSinceStartupAsDouble,
                settings.InterstitialIntervalSeconds, settings.AppOpenIntervalSeconds,
                settings.FirstSessionInterstitialDelaySeconds);

        bool RejectShow(AdFormat format, AdCallbacks callbacks, string placement, string blockReason = null)
        {
            string reason = blockReason ?? (IsShowingFullScreen ? "another_ad_showing" : !CanRequest ? "ads_not_available" : "ad_not_ready");
            // A policy block (cooldown, first-session delay, not at entry) is expected, not a failure.
            if (blockReason != null) Debug.Log($"[Ads] {format} skipped: {reason}");
            if (blockReason != null) Track("ad_show_skipped", format, placement, new Parameter("reason", reason));
            else ReportAdFailure("ad_show_failed", format, placement, reason);
            new AdShowSession(callbacks).Fail(reason);
            return false;
        }

        void ReportAdFailure(string eventName, AdFormat format, string placement, string error)
        {
            Track(eventName, format, placement, new Parameter("error_message", Clip(error)));
            Fail(error);
        }

        void RecordImpression(AdFormat format, string placement, AdShowSession session)
        {
            // ad_impression itself is automatically recorded by linked AdMob/Firebase.
            Track("ad_impression_observed", format, placement);
            session?.Impression();
            AdShowSession.Invoke(() => ImpressionRecorded?.Invoke(format));
        }

        void RecordClick(AdFormat format, string placement, AdShowSession session)
        {
            Track("ad_clicked", format, placement);
            session?.Clicked();
            AdShowSession.Invoke(() => Clicked?.Invoke(format));
        }

        void RecordPaid(AdFormat format, string placement, AdValue value, AdShowSession session)
        {
            // OnPaidEvent is the first callback that provides the impression's monetary value.
            // Keep this event explicit even when the SDK already reports automatic impressions;
            // the custom event gives Firebase the value in the same unit as revenue reporting.
            var valueParameters = new[]
            {
                new Parameter("value", value.Value / 1000000d),
                new Parameter("currency", value.CurrencyCode ?? ""),
                new Parameter("precision", value.Precision.ToString()),
            };
            Track("ad_impression", format, placement, valueParameters);
            if (!logManualAdImpressions)
                Track("ad_paid", format, placement, valueParameters);
            session?.Paid(value);
            AdShowSession.Invoke(() => Paid?.Invoke(format, value));
        }

        void Track(string eventName, AdFormat format, string placement = "", params Parameter[] extra)
        {
            Debug.Log($"[Ads] {eventName} {format}" + (string.IsNullOrEmpty(placement) ? "" : " @" + placement));
            var firebase = FirebaseController.Instance;
            if (firebase == null) return;
            var parameters = new Parameter[5 + extra.Length];
            parameters[0] = new Parameter("ad_platform", "AdMob");
            parameters[1] = new Parameter("ad_format", format.ToString().ToLowerInvariant());
            parameters[2] = new Parameter("ad_unit_name", CurrentAdUnitId(format));
            parameters[3] = new Parameter("placement", Clip(placement));
            parameters[4] = new Parameter("is_test_ad", settings != null && (settings.UseTestAds || Application.isEditor) ? 1L : 0L);
            Array.Copy(extra, 0, parameters, 5, extra.Length);
            try { firebase.LogEvent(eventName, parameters); }
            catch (Exception error) { Debug.LogWarning("[AdTracking] " + error.Message); }
        }

        string CurrentAdUnitId(AdFormat format)
        {
            if (settings == null) return "";
            var ids = settings.GetAdUnitIds(format, ForIos, Application.isEditor);
            var index = (int)format;
            return index >= 0 && index < adUnitIndexes.Length && adUnitIndexes[index] < ids.Count
                ? ids[adUnitIndexes[index]] ?? "" : "";
        }

        static string Clip(string text) => string.IsNullOrEmpty(text) ? "" : text.Substring(0, Math.Min(100, text.Length));

        void OnMain(Action action) => MobileAdsEventExecutor.ExecuteInUpdate(() =>
        {
            if (!disposed && this != null) action();
        });

        void Fail(string message)
        {
            LastError = message;
            Debug.LogWarning("[GoogleAdsMobileController] " + message, this);
            AdShowSession.Invoke(() => Failed?.Invoke(message));
        }

        void DestroyAds()
        {
            generation++;
            Array.Clear(adUnitIndexes, 0, adUnitIndexes.Length);
            Array.Clear(adRetryBlockedUntil, 0, adRetryBlockedUntil.Length);
            var cancelledInterstitial = interstitialSession;
            var cancelledRewarded = rewardedSession;
            var cancelledAppOpen = appOpenSession;
            interstitialSession = rewardedSession = appOpenSession = null;
            loadingInterstitial = loadingRewarded = loadingAppOpen = false;
            DestroyBanner();
            interstitial?.Destroy();
            interstitial = null;
            rewarded?.Destroy();
            rewarded = null;
            appOpen?.Destroy();
            appOpen = null;
            cancelledInterstitial?.Fail("cancelled");
            cancelledRewarded?.Fail("cancelled");
            cancelledAppOpen?.Fail("cancelled");
        }

        void OnDestroy()
        {
            disposed = true;
            if (Instance != this) return;
            DestroyAds();
            IsReady = IsInitializing = IsShowingFullScreen = IsConsentFlowRunning = false;
            Instance = null;
        }
    }
}
