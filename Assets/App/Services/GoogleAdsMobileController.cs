using System;
using Cysharp.Threading.Tasks;
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
        AdShowSession bannerSession, interstitialSession, rewardedSession, appOpenSession, rewardedInterstitialSession;
        string bannerPlacement = "", interstitialPlacement = "", rewardedPlacement = "", appOpenPlacement = "", rewardedInterstitialPlacement = "";
        bool isShowBanner; // set by ShowBanner/HideBanner; decides whether a loaded banner is displayed
        AdPosition bannerPosition = AdPosition.Bottom;
        AdCallbacks bannerCallbacks;
        int bannerWidthDp;
        double nextBannerLoadAt;
        bool bannerStageReady, nativeStageReady, fullScreenStageReady;

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
        const string SessionCountKey = "StbEmu.Ads.SessionCount";
        public bool IsFirstSession { get; private set; }
        public int SessionCount { get; private set; }
        static bool? firstSessionForLaunch;
        static int? sessionCountForLaunch;
        static AdDisplayTiming timingForLaunch;
        static bool startupOpportunityCreated;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
        static void ResetLaunchState()
        {
            firstSessionForLaunch = null;
            sessionCountForLaunch = null;
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
        RewardedInterstitialAd rewardedInterstitial;
        AppOpenAd appOpen;
        bool loadingBanner, loadingInterstitial, loadingRewarded, loadingAppOpen, loadingRewardedInterstitial;
        static readonly int FormatCount = Enum.GetValues(typeof(AdFormat)).Length;
        readonly int[] adUnitIndexes = new int[FormatCount];
        readonly int[] cntRetry = new int[FormatCount];
        readonly string[] requestedAdUnitIds = new string[FormatCount];
        DateTime interstitialLoadedAt, rewardedLoadedAt, appOpenLoadedAt, rewardedInterstitialLoadedAt;
        public bool IsInterstitialReady => CanRequest && interstitial != null && interstitial.CanShowAd() && DateTime.UtcNow - interstitialLoadedAt < TimeSpan.FromMinutes(55);
        public bool IsRewardedReady => CanRequest && rewarded != null && rewarded.CanShowAd() && DateTime.UtcNow - rewardedLoadedAt < TimeSpan.FromMinutes(55);
        public bool IsRewardedInterstitialReady => CanRequest && rewardedInterstitial != null && rewardedInterstitial.CanShowAd() &&
            DateTime.UtcNow - rewardedInterstitialLoadedAt < TimeSpan.FromMinutes(55);
        /// <summary>Ad break: rewarded first, rewarded interstitial as the fallback.</summary>
        public bool IsAdBreakReady => IsRewardedReady || IsRewardedInterstitialReady;
        public bool IsAppOpenReady => CanRequest && appOpen != null && appOpen.CanShowAd() && DateTime.UtcNow - appOpenLoadedAt < TimeSpan.FromHours(4);
        public bool CanShowNative => CanRequest && nativeStageReady && settings != null && SessionCount >= settings.SessionShowNative;
        public System.Collections.Generic.List<string> GetNativeAdUnitIds() =>
            CanShowNative ? settings.GetAdUnitIds(AdFormat.Native, Application.isEditor) : new System.Collections.Generic.List<string>();
        // Pro ownership blocks all ad requests and displays.
        static bool NoAds => InAppPurchaseController.Instance != null && InAppPurchaseController.Instance.IsPro;
        bool CanRequest => !disposed && !NoAds && IsReady && !IsConsentFlowRunning && ConsentInformation.CanRequestAds();

        void Awake()
        {
            if (Instance != null && Instance != this) { Destroy(gameObject); return; }
            Instance = this;
            if (!firstSessionForLaunch.HasValue)
            {
                bool hadStarted = PlayerPrefs.GetInt(FirstSessionCompletedKey, 0) != 0;
                firstSessionForLaunch = !hadStarted;
                int prevCount = PlayerPrefs.GetInt(SessionCountKey, hadStarted ? 1 : 0);
                sessionCountForLaunch = prevCount + 1;
                PlayerPrefs.SetInt(FirstSessionCompletedKey, 1);
                PlayerPrefs.SetInt(SessionCountKey, sessionCountForLaunch.Value);
                PlayerPrefs.Save();
                timingForLaunch = new AdDisplayTiming(firstSessionForLaunch.Value);
            }
            IsFirstSession = firstSessionForLaunch.Value;
            SessionCount = sessionCountForLaunch ?? 1;
            displayTiming = timingForLaunch;
            appOpenEntry = new AppOpenEntryPolicy(IsFirstSession || startupOpportunityCreated, Time.realtimeSinceStartupAsDouble);
            startupOpportunityCreated = true;
            DontDestroyOnLoad(gameObject);
            if (settings == null) settings = Resources.Load<AdMobSettings>(AdMobSettings.ResourcePath);
        }

        void Start()
        {
            if (InAppPurchaseController.Instance != null) InAppPurchaseController.Instance.OwnershipChanged += OnRemoteConfigLoaded; // Pro bought/expired mid-session
            if (initializeOnStart) Initialize();
        }

        // All public methods must be called from Unity's main thread.
        public void Initialize()
        {
            if (disposed || IsReady || IsInitializing) return;
            if (settings == null) { Fail("Assign AdMobSettings or add it under Resources/Services/AdMobSettings."); return; }
            if (!Application.isEditor && Application.platform != RuntimePlatform.Android)
            { Fail("Mobile Ads supports Android only."); return; }
            var appId = settings.GetAppId();
            if (!AdMobSettings.IsValidAppId(appId))
            {
                Fail("Missing or invalid Android AdMob App ID in AdMobSettings.");
                return;
            }
            Debug.Log($"[Ads] Init appId={appId}", this);
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
                RunStartupLoadSequenceAsync(++generation).Forget();
            }));
        }

        async UniTaskVoid RunStartupLoadSequenceAsync(int seqGeneration)
        {
            bannerStageReady = false;
            nativeStageReady = false;
            fullScreenStageReady = false;

            // 1. Wait 1s after opening/initializing so RemoteConfig and native layout settle before the first ad request.
            await UniTask.Delay(TimeSpan.FromSeconds(1), DelayType.Realtime, cancellationToken: destroyCancellationToken);
            if (disposed || seqGeneration != generation || !CanRequest) return;

            // 2. Stage 1: AppOpen (only if eligible at startup entry).
            if (!IsFirstSession && !appOpenSuppressed && appOpenEntry != null && appOpenEntry.IsForeground
                && appOpenEntry.CanShow(Time.realtimeSinceStartupAsDouble)
                && !IsShowingFullScreen && GetTimingBlockReason(AdFormat.AppOpen) == null)
            {
                HandleAppOpenEntry(allowLoad: true);
                await UniTask.WaitWhile(() => loadingAppOpen && !disposed && seqGeneration == generation && CanRequest,
                    cancellationToken: destroyCancellationToken);
                if (disposed || seqGeneration != generation || !CanRequest) return;
                if (IsShowingFullScreen)
                {
                    await UniTask.WaitWhile(() => IsShowingFullScreen && !disposed && seqGeneration == generation,
                        cancellationToken: destroyCancellationToken);
                    if (disposed || seqGeneration != generation || !CanRequest) return;
                }
            }

            // 3. Stage 2: Banner (exclusive load window before Native & FullScreen ads).
            bannerStageReady = true;
            if (isShowBanner && !IsShowingFullScreen && banner == null && !loadingBanner)
                LoadBanner();
            await UniTask.WaitWhile(() => loadingBanner && !disposed && seqGeneration == generation && CanRequest,
                cancellationToken: destroyCancellationToken);
            if (disposed || seqGeneration != generation || !CanRequest) return;

            // 4. Stage 3: Native (if eligible by SessionShowNative).
            nativeStageReady = true;
            ConsentUpdated?.Invoke();

            // 5. Stage 4: Wait 10s, then load Interstitial, Rewarded, RewardedInterstitial, and preload AppOpen.
            await UniTask.Delay(TimeSpan.FromSeconds(10), DelayType.Realtime, cancellationToken: destroyCancellationToken);
            if (disposed || seqGeneration != generation || !CanRequest) return;

            fullScreenStageReady = true;

            LoadInterstitial();
            await UniTask.WaitWhile(() => loadingInterstitial && !disposed && seqGeneration == generation && CanRequest,
                cancellationToken: destroyCancellationToken);
            if (disposed || seqGeneration != generation || !CanRequest) return;

            LoadRewarded();
            await UniTask.WaitWhile(() => loadingRewarded && !disposed && seqGeneration == generation && CanRequest,
                cancellationToken: destroyCancellationToken);
            if (disposed || seqGeneration != generation || !CanRequest) return;

            LoadRewardedInterstitial();
            await UniTask.WaitWhile(() => loadingRewardedInterstitial && !disposed && seqGeneration == generation && CanRequest,
                cancellationToken: destroyCancellationToken);
            if (disposed || seqGeneration != generation || !CanRequest) return;

            if (!IsAppOpenReady && !loadingAppOpen && !IsInvoking(nameof(LoadAppOpen)))
                LoadAppOpen();
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
                if (!ConsentInformation.CanRequestAds()) return;
                if (!IsReady) Initialize();
                else RunStartupLoadSequenceAsync(++generation).Forget();
            }));
        }

        bool TryGetId(AdFormat format, out string id)
        {
            id = null;
            if (!CanRequest) return false;
            var ids = settings.GetAdUnitIds(format, Application.isEditor);
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

        static string DescribeLoadError(LoadAdError error)
        {
            if (error == null) return "Load returned no ad.";
            var response = error.GetResponseInfo();
            return $"code={error.GetCode()} domain={error.GetDomain()} message={error.GetMessage()}" +
                (response == null ? "" : $" response={response}");
        }

        static string LoadMethod(AdFormat format)
        {
            switch (format)
            {
                case AdFormat.Interstitial: return nameof(LoadInterstitial);
                case AdFormat.Rewarded: return nameof(LoadRewarded);
                case AdFormat.AppOpen: return nameof(LoadAppOpen);
                case AdFormat.RewardedInterstitial: return nameof(LoadRewardedInterstitial);
                case AdFormat.Banner: return nameof(LoadBanner);
                default: return null;
            }
        }

        void ScheduleLoadRetry(AdFormat format)
        {
            int cnt = ++cntRetry[(int)format];
            float delay = Mathf.Min(60f, Mathf.Pow(2f, Mathf.Min(cnt, 6)));
            if (format == AdFormat.Banner) nextBannerLoadAt = Time.realtimeSinceStartupAsDouble + delay;
            var method = LoadMethod(format);
            if (method != null)
            {
                CancelInvoke(method);
                Invoke(method, delay);
            }
            Debug.Log($"[Ads] {format} load failed (cntRetry={cnt}); next load in {delay}s");
        }

        void NotifyLoaded(AdFormat format)
        {
            cntRetry[(int)format] = 0;
            if (format == AdFormat.Banner) nextBannerLoadAt = 0;
            var method = LoadMethod(format);
            if (method != null) CancelInvoke(method);
            AdShowSession.Invoke(() => Loaded?.Invoke(format));
        }

        bool TryNextAdUnit(AdFormat format)
        {
            var ids = settings.GetAdUnitIds(format, Application.isEditor);
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

        public bool HasBanner => banner != null;
        public float BannerHeightPixels { get; private set; }
        public bool AdsDisabled => NoAds;

        public void ResumeBanner(AdPosition position)
        {
            if (!CanRequest || banner == null || isShowBanner) return;
            isShowBanner = true;
            banner.SetPosition(position);
            if (BannerHeightPixels > 0) banner.Show();
        }

        public bool ShowBanner(AdPosition position = AdPosition.Bottom, AdCallbacks callbacks = null, string placement = "", int widthDp = 0)
        {
            if (banner != null && bannerWidthDp != widthDp) DestroyBannerView(); // layout width changed: reload at the new size
            bannerPosition = position;
            if (callbacks != null || banner == null) bannerCallbacks = callbacks;
            bannerPlacement = placement;
            bannerWidthDp = widthDp;
            if (banner != null) { ResumeBanner(position); return true; }
            if (!CanRequest || IsShowingFullScreen)
            {
                isShowBanner = false;
                return RejectShow(AdFormat.Banner, callbacks, placement);
            }
            isShowBanner = true;
            if (!bannerStageReady || loadingBanner || IsInvoking(nameof(LoadBanner))) return true;
            double remaining = nextBannerLoadAt - Time.realtimeSinceStartupAsDouble;
            if (remaining > 0)
            {
                Invoke(nameof(LoadBanner), (float)remaining);
                return true;
            }
            if (!TryGetId(AdFormat.Banner, out _))
            {
                isShowBanner = false;
                return RejectShow(AdFormat.Banner, callbacks, placement);
            }
            LoadBanner();
            return true;
        }

        // Loads the banner view. On failure retries with exponential backoff (cntRetry) so a transient no-fill doesn't leave the slot empty.
        // Once loaded it shows itself only if isShowBanner is still set by ShowBanner/HideBanner.
        void LoadBanner()
        {
            if (!bannerStageReady || !isShowBanner || IsShowingFullScreen || loadingBanner || banner != null || !TryGetId(AdFormat.Banner, out var id)) return;
            CancelInvoke(nameof(LoadBanner));
            loadingBanner = true;
            requestedAdUnitIds[(int)AdFormat.Banner] = id;
            var position = bannerPosition;
            var session = bannerSession ?? (bannerSession = new AdShowSession(bannerCallbacks));
            int safeWidth = MobileAds.Utils.GetDeviceSafeWidth();
            int adaptiveWidth = bannerWidthDp > 0 && safeWidth > 0 ? Math.Min(bannerWidthDp, safeWidth) : bannerWidthDp;
            var size = adaptiveWidth > 0 ? AdSize.GetPortraitAnchoredAdaptiveBannerAdSizeWithWidth(adaptiveWidth) : AdSize.Banner;
            var view = new BannerView(id, size, position);
            banner = view;
            BannerHeightPixels = 0;
            view.OnBannerAdLoaded += () => OnMain(() =>
            {
                if (banner != view) return;
                loadingBanner = false;
                BannerHeightPixels = view.GetHeightInPixels();
                Track("ad_loaded", AdFormat.Banner, bannerPlacement);
                NotifyLoaded(AdFormat.Banner);
                if (isShowBanner && !IsShowingFullScreen) view.Show(); else view.Hide();
            });
            view.OnBannerAdLoadFailed += error => OnMain(() =>
            {
                if (banner != view) return;
                loadingBanner = false;
                // Keep an already-rendered banner if a later auto-refresh gets no-fill; the AdView retries on its next refresh tick.
                if (BannerHeightPixels > 0) return;
                banner = null;
                BannerHeightPixels = 0;
                view.Destroy();
                if (!isShowBanner || IsShowingFullScreen || !CanRequest) return;
                if (TryNextAdUnit(AdFormat.Banner))
                {
                    LoadBanner();
                    return;
                }
                ScheduleLoadRetry(AdFormat.Banner);
                ReportAdFailure("ad_load_failed", AdFormat.Banner, bannerPlacement, DescribeLoadError(error));
            });
            bool bannerShown = false;
            view.OnAdImpressionRecorded += () => OnMain(() =>
            {
                if (banner != view) return;
                // Loading a banner isn't proof it was shown. The first impression is.
                if (!bannerShown)
                {
                    bannerShown = true;
                    session.Shown();
                    AdShowSession.Invoke(() => Shown?.Invoke(AdFormat.Banner));
                }
                RecordImpression(AdFormat.Banner, session);
            });
            view.OnAdClicked += () => OnMain(() => { if (banner == view) RecordClick(AdFormat.Banner, session); });
            view.OnAdPaid += value => OnMain(() => { if (banner == view) RecordPaid(AdFormat.Banner, bannerPlacement, value, session); });
            view.LoadAd(new AdRequest());
        }

        public void HideBanner()
        {
            CancelInvoke(nameof(LoadBanner));
            if (banner != null && BannerHeightPixels <= 0)
            {
                isShowBanner = false;
                DestroyBannerView();
                return;
            }
            if (!isShowBanner) return;
            isShowBanner = false;
            banner?.Hide();
        }

        void DestroyBannerView()
        {
            var view = banner;
            banner = null;
            loadingBanner = false;
            BannerHeightPixels = 0;
            CancelInvoke(nameof(LoadBanner));
            view?.Destroy();
        }

        public void DestroyBanner()
        {
            var session = bannerSession;
            bannerSession = null;
            isShowBanner = false;
            nextBannerLoadAt = 0;
            DestroyBannerView();
            if (session == null) return;
            session.Close();
            AdShowSession.Invoke(() => Closed?.Invoke(AdFormat.Banner));
        }

        public void LoadInterstitial()
        {
            if (loadingInterstitial || interstitialSession != null || IsInterstitialReady
                || !TryGetId(AdFormat.Interstitial, out var id)) return;
            CancelInvoke(nameof(LoadInterstitial));
            interstitial?.Destroy();
            interstitial = null;
            loadingInterstitial = true;
            requestedAdUnitIds[(int)AdFormat.Interstitial] = id;
            int requestGeneration = generation;
            InterstitialAd.Load(id, new AdRequest(), (ad, error) => MobileAdsEventExecutor.ExecuteInUpdate(() =>
            {
                if (disposed || this == null || requestGeneration != generation || requestedAdUnitIds[(int)AdFormat.Interstitial] != id)
                { ad?.Destroy(); return; }
                loadingInterstitial = false;
                if (error != null || ad == null)
                {
                    ad?.Destroy();
                    if (TryNextAdUnit(AdFormat.Interstitial))
                    {
                        LoadInterstitial();
                        return;
                    }
                    ScheduleLoadRetry(AdFormat.Interstitial);
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
                    interstitialSession?.Shown();
                    AdShowSession.Invoke(() => Shown?.Invoke(AdFormat.Interstitial));
                });
                ad.OnAdImpressionRecorded += () => OnMain(() => { if (interstitial == ad) RecordImpression(AdFormat.Interstitial, interstitialSession); });
                ad.OnAdClicked += () => OnMain(() => { if (interstitial == ad) RecordClick(AdFormat.Interstitial, interstitialSession); });
                ad.OnAdPaid += value => OnMain(() => { if (interstitial == ad) RecordPaid(AdFormat.Interstitial, interstitialPlacement, value, interstitialSession); });
                ad.OnAdFullScreenContentClosed += () => OnMain(() => FinishInterstitial(ad, null));
                ad.OnAdFullScreenContentFailed += failure => OnMain(() => FinishInterstitial(ad, failure.ToString()));
                Track("ad_loaded", AdFormat.Interstitial);
                NotifyLoaded(AdFormat.Interstitial);
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
                displayTiming.RecordClosed(Time.realtimeSinceStartupAsDouble);
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
            if (loadingAppOpen || appOpenSession != null || IsAppOpenReady
                || !TryGetId(AdFormat.AppOpen, out var id)) return;
            CancelInvoke(nameof(LoadAppOpen));
            appOpen?.Destroy();
            appOpen = null;
            loadingAppOpen = true;
            requestedAdUnitIds[(int)AdFormat.AppOpen] = id;
            int requestGeneration = generation;
            AppOpenAd.Load(id, new AdRequest(), (ad, error) => MobileAdsEventExecutor.ExecuteInUpdate(() =>
            {
                if (disposed || this == null || requestGeneration != generation || requestedAdUnitIds[(int)AdFormat.AppOpen] != id)
                { ad?.Destroy(); return; }
                loadingAppOpen = false;
                if (error != null || ad == null)
                {
                    ad?.Destroy();
                    if (TryNextAdUnit(AdFormat.AppOpen))
                    {
                        LoadAppOpen();
                        return;
                    }
                    ScheduleLoadRetry(AdFormat.AppOpen);
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
                    appOpenSession?.Shown();
                    AdShowSession.Invoke(() => Shown?.Invoke(AdFormat.AppOpen));
                });
                ad.OnAdImpressionRecorded += () => OnMain(() => { if (appOpen == ad) RecordImpression(AdFormat.AppOpen, appOpenSession); });
                ad.OnAdClicked += () => OnMain(() => { if (appOpen == ad) RecordClick(AdFormat.AppOpen, appOpenSession); });
                ad.OnAdPaid += value => OnMain(() => { if (appOpen == ad) RecordPaid(AdFormat.AppOpen, appOpenPlacement, value, appOpenSession); });
                ad.OnAdFullScreenContentClosed += () => OnMain(() => FinishAppOpen(ad, null));
                ad.OnAdFullScreenContentFailed += failure => OnMain(() => FinishAppOpen(ad, failure.ToString()));
                Track("ad_loaded", AdFormat.AppOpen);
                NotifyLoaded(AdFormat.AppOpen);
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
                displayTiming.RecordClosed(Time.realtimeSinceStartupAsDouble);
                session?.Close();
                AdShowSession.Invoke(() => Closed?.Invoke(AdFormat.AppOpen));
            }
            if (fullScreenStageReady) LoadAppOpen();
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
            if (loadingRewarded || rewardedSession != null || IsRewardedReady
                || !TryGetId(AdFormat.Rewarded, out var id)) return;
            CancelInvoke(nameof(LoadRewarded));
            rewarded?.Destroy();
            rewarded = null;
            loadingRewarded = true;
            requestedAdUnitIds[(int)AdFormat.Rewarded] = id;
            int requestGeneration = generation;
            RewardedAd.Load(id, new AdRequest(), (ad, error) => MobileAdsEventExecutor.ExecuteInUpdate(() =>
            {
                if (disposed || this == null || requestGeneration != generation || requestedAdUnitIds[(int)AdFormat.Rewarded] != id)
                { ad?.Destroy(); return; }
                loadingRewarded = false;
                if (error != null || ad == null)
                {
                    ad?.Destroy();
                    if (TryNextAdUnit(AdFormat.Rewarded))
                    {
                        LoadRewarded();
                        return;
                    }
                    ScheduleLoadRetry(AdFormat.Rewarded);
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
                    rewardedSession?.Shown();
                    AdShowSession.Invoke(() => Shown?.Invoke(AdFormat.Rewarded));
                });
                ad.OnAdImpressionRecorded += () => OnMain(() => { if (rewarded == ad) RecordImpression(AdFormat.Rewarded, rewardedSession); });
                ad.OnAdClicked += () => OnMain(() => { if (rewarded == ad) RecordClick(AdFormat.Rewarded, rewardedSession); });
                ad.OnAdPaid += value => OnMain(() => { if (rewarded == ad) RecordPaid(AdFormat.Rewarded, rewardedPlacement, value, rewardedSession); });
                ad.OnAdFullScreenContentClosed += () => OnMain(() => FinishRewarded(ad, null));
                ad.OnAdFullScreenContentFailed += failure => OnMain(() => FinishRewarded(ad, failure.ToString()));
                Track("ad_loaded", AdFormat.Rewarded);
                NotifyLoaded(AdFormat.Rewarded);
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
                // Watched (reward earned or not): interstitial and app open cooldowns restart from the close, not the open.
                displayTiming.RecordShown(AdFormat.Rewarded, Time.realtimeSinceStartupAsDouble);
                displayTiming.RecordClosed(Time.realtimeSinceStartupAsDouble);
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

        // Optional ad break fallback: an empty ID list is not an error, it just never loads.
        public void LoadRewardedInterstitial()
        {
            if (loadingRewardedInterstitial || rewardedInterstitialSession != null || IsRewardedInterstitialReady || settings == null
                || settings.GetAdUnitIds(AdFormat.RewardedInterstitial, Application.isEditor).Count == 0
                || !TryGetId(AdFormat.RewardedInterstitial, out var id)) return;
            CancelInvoke(nameof(LoadRewardedInterstitial));
            rewardedInterstitial?.Destroy();
            rewardedInterstitial = null;
            loadingRewardedInterstitial = true;
            requestedAdUnitIds[(int)AdFormat.RewardedInterstitial] = id;
            int requestGeneration = generation;
            RewardedInterstitialAd.Load(id, new AdRequest(), (ad, error) => MobileAdsEventExecutor.ExecuteInUpdate(() =>
            {
                if (disposed || this == null || requestGeneration != generation || requestedAdUnitIds[(int)AdFormat.RewardedInterstitial] != id)
                { ad?.Destroy(); return; }
                loadingRewardedInterstitial = false;
                if (error != null || ad == null)
                {
                    ad?.Destroy();
                    if (TryNextAdUnit(AdFormat.RewardedInterstitial))
                    {
                        LoadRewardedInterstitial();
                        return;
                    }
                    ScheduleLoadRetry(AdFormat.RewardedInterstitial);
                    ReportAdFailure("ad_load_failed", AdFormat.RewardedInterstitial, "", DescribeLoadError(error));
                    return;
                }
                if (!CanRequest) { ad.Destroy(); return; }
                rewardedInterstitial = ad;
                rewardedInterstitialLoadedAt = DateTime.UtcNow;
                ad.OnAdFullScreenContentOpened += () => OnMain(() =>
                {
                    if (rewardedInterstitial != ad) return;
                    displayTiming.RecordShown(AdFormat.RewardedInterstitial, Time.realtimeSinceStartupAsDouble);
                    rewardedInterstitialSession?.Shown();
                    AdShowSession.Invoke(() => Shown?.Invoke(AdFormat.RewardedInterstitial));
                });
                ad.OnAdImpressionRecorded += () => OnMain(() => { if (rewardedInterstitial == ad) RecordImpression(AdFormat.RewardedInterstitial, rewardedInterstitialSession); });
                ad.OnAdClicked += () => OnMain(() => { if (rewardedInterstitial == ad) RecordClick(AdFormat.RewardedInterstitial, rewardedInterstitialSession); });
                ad.OnAdPaid += value => OnMain(() => { if (rewardedInterstitial == ad) RecordPaid(AdFormat.RewardedInterstitial, rewardedInterstitialPlacement, value, rewardedInterstitialSession); });
                ad.OnAdFullScreenContentClosed += () => OnMain(() => FinishRewardedInterstitial(ad, null));
                ad.OnAdFullScreenContentFailed += failure => OnMain(() => FinishRewardedInterstitial(ad, failure.ToString()));
                Track("ad_loaded", AdFormat.RewardedInterstitial);
                NotifyLoaded(AdFormat.RewardedInterstitial);
            }));
        }

        void FinishRewardedInterstitial(RewardedInterstitialAd ad, string error)
        {
            if (rewardedInterstitial != ad) return;
            var session = rewardedInterstitialSession;
            var placement = rewardedInterstitialPlacement;
            rewardedInterstitial = null;
            rewardedInterstitialSession = null;
            IsShowingFullScreen = false;
            ad.Destroy();
            if (error != null)
            {
                ReportAdFailure("ad_show_failed", AdFormat.RewardedInterstitial, placement, error);
                session?.Fail(error);
            }
            else
            {
                displayTiming.RecordShown(AdFormat.RewardedInterstitial, Time.realtimeSinceStartupAsDouble);
                displayTiming.RecordClosed(Time.realtimeSinceStartupAsDouble);
                session?.Close();
                AdShowSession.Invoke(() => Closed?.Invoke(AdFormat.RewardedInterstitial));
            }
            LoadRewardedInterstitial();
        }

        public bool ShowRewardedInterstitial(Action<Reward> onReward, AdCallbacks callbacks = null, string placement = "")
        {
            if (IsShowingFullScreen || !IsRewardedInterstitialReady) return RejectShow(AdFormat.RewardedInterstitial, callbacks, placement);
            IsShowingFullScreen = true;
            rewardedInterstitialSession = new AdShowSession(callbacks);
            rewardedInterstitialPlacement = placement;
            var ad = rewardedInterstitial;
            try
            {
                bool granted = false;
                ad.Show(reward => OnMain(() =>
                {
                    if (granted) return;
                    granted = true;
                    Track("ad_reward_earned", AdFormat.RewardedInterstitial, placement,
                        new Parameter("reward_type", reward.Type ?? ""), new Parameter("reward_amount", reward.Amount));
                    AdShowSession.Invoke(() => onReward?.Invoke(reward));
                }));
                return true;
            }
            catch (Exception error)
            {
                FinishRewardedInterstitial(ad, error.Message);
                return false;
            }
        }

        /// <summary>Ad break: the rewarded ad when ready, otherwise the rewarded interstitial. Neither ready: rejected like ShowRewarded.</summary>
        public bool ShowAdBreak(AdCallbacks callbacks = null, string placement = "")
        {
            if (!IsRewardedReady && IsRewardedInterstitialReady) return ShowRewardedInterstitial(null, callbacks, placement);
            return ShowRewarded(null, callbacks, placement);
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

        void HandleAppOpenEntry(bool allowLoad = false)
        {
            if (appOpenEntry != null)
                Debug.Log($"[Ads] App entry: eligible={appOpenEntry.CanShow(Time.realtimeSinceStartupAsDouble)}, suppressed={appOpenSuppressed}, " +
                    $"canRequest={CanRequest}, fullScreen={IsShowingFullScreen}, timing={GetTimingBlockReason(AdFormat.AppOpen) ?? "ok"}, " +
                    $"ready={IsAppOpenReady}, loading={loadingAppOpen}");
            if (appOpenSuppressed || !CanRequest || appOpenEntry == null || !appOpenEntry.IsForeground) return;
            if (IsShowingFullScreen || GetTimingBlockReason(AdFormat.AppOpen) != null)
                appOpenEntry.Consume(); // Do not display later, after the user has resumed using the app.
            if (IsAppOpenReady) TryShowAppOpenAtEntry();
            else if ((allowLoad || fullScreenStageReady) && !IsInvoking(nameof(LoadAppOpen))) LoadAppOpen();
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

        public string GetTimingBlockReason(AdFormat format)
        {
            if (displayTiming == null || format == AdFormat.Banner || format == AdFormat.Native) return null;
            var policy = MonetizationController.Instance;
            return displayTiming.GetBlockReason(format, Time.realtimeSinceStartupAsDouble,
                policy.Get(MonetizationPolicy.Interstitial), policy.Get(MonetizationPolicy.AppOpen),
                policy.Get(MonetizationPolicy.FirstSessionDelay), policy.Get(MonetizationPolicy.MinGap));
        }

        bool RejectShow(AdFormat format, AdCallbacks callbacks, string placement, string blockReason = null)
        {
            string reason = blockReason ?? (IsShowingFullScreen ? "another_ad_showing" : !CanRequest ? "ads_not_available" : "ad_not_ready");
            // A policy block (cooldown, first-session delay, not at entry) is expected, not a failure.
            if (blockReason != null) Debug.Log($"[Ads] {format} skipped: {reason}");
            else ReportAdFailure("ad_show_failed", format, placement, reason);
            new AdShowSession(callbacks).Fail(reason);
            return false;
        }

        void ReportAdFailure(string eventName, AdFormat format, string placement, string error)
        {
            Track(eventName, format, placement, new Parameter("error_message", Clip(error)));
            Fail(error);
        }

        void RecordImpression(AdFormat format, AdShowSession session)
        {
            // ad_impression itself is automatically recorded by linked AdMob/Firebase.
            session?.Impression();
            AdShowSession.Invoke(() => ImpressionRecorded?.Invoke(format));
        }

        void RecordClick(AdFormat format, AdShowSession session)
        {
            session?.Clicked();
            AdShowSession.Invoke(() => Clicked?.Invoke(format));
        }

        void RecordPaid(AdFormat format, string placement, AdValue value, AdShowSession session)
        {
            // OnPaidEvent is the first callback that provides the impression's monetary value.
            // Use the custom event when automatic AdMob/Firebase reporting is active.
            // Only send ad_impression manually when automatic reporting is not in use.
            var valueParameters = new[]
            {
                new Parameter("value", value.Value / 1000000d),
                new Parameter("currency", value.CurrencyCode ?? ""),
                new Parameter("precision", value.Precision.ToString()),
            };
            Track(logManualAdImpressions ? "ad_impression" : "ad_paid",
                format, placement, valueParameters);
            session?.Paid(value);
            AdShowSession.Invoke(() => Paid?.Invoke(format, value));
        }

        public void TrackNativeEvent(string action, string adUnitId, string placement = "", string detail = "")
        {
            var unit = new Parameter("ad_unit_name", Clip(adUnitId));
            switch (action)
            {
                case "loaded": Track("ad_loaded", AdFormat.Native, placement, unit); break;
                case "load_failed": Track("ad_load_failed", AdFormat.Native, placement, unit, new Parameter("error_message", Clip(detail))); break;
                case "impression":
                    AdShowSession.Invoke(() => ImpressionRecorded?.Invoke(AdFormat.Native));
                    break;
                case "clicked":
                    AdShowSession.Invoke(() => Clicked?.Invoke(AdFormat.Native));
                    break;
            }
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
            var ids = settings.GetAdUnitIds(format, Application.isEditor);
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

        // Remote config or ownership changed mid-session. Pro: drop the banner and preloaded/in-flight ads.
        // Otherwise restart each fallback list and drop ads from IDs no longer configured.
        // An ad being shown finishes first.
        public void OnRemoteConfigLoaded()
        {
            if (disposed || settings == null) return;
            if (NoAds)
            {
                if (!IsShowingFullScreen) DestroyAds();
                return;
            }
            CancelInvoke();
            Array.Clear(adUnitIndexes, 0, adUnitIndexes.Length);
            Array.Clear(cntRetry, 0, cntRetry.Length);
            nextBannerLoadAt = 0;
            if (IsStale(AdFormat.Banner))
                DestroyBannerView();
            if (bannerStageReady && isShowBanner && !IsShowingFullScreen && banner == null && !loadingBanner)
                LoadBanner();
            if (interstitialSession == null && IsStale(AdFormat.Interstitial))
            {
                interstitial?.Destroy();
                interstitial = null;
                loadingInterstitial = false;
            }
            if (fullScreenStageReady && interstitialSession == null && !IsInterstitialReady && !loadingInterstitial)
                LoadInterstitial();
            if (rewardedSession == null && IsStale(AdFormat.Rewarded))
            {
                rewarded?.Destroy();
                rewarded = null;
                loadingRewarded = false;
            }
            if (fullScreenStageReady && rewardedSession == null && !IsRewardedReady && !loadingRewarded)
                LoadRewarded();
            if (rewardedInterstitialSession == null && IsStale(AdFormat.RewardedInterstitial))
            {
                rewardedInterstitial?.Destroy();
                rewardedInterstitial = null;
                loadingRewardedInterstitial = false;
            }
            if (fullScreenStageReady && rewardedInterstitialSession == null && !IsRewardedInterstitialReady && !loadingRewardedInterstitial)
                LoadRewardedInterstitial();
            bool reloadEntryAppOpen = loadingAppOpen && !fullScreenStageReady;
            if (appOpenSession == null && IsStale(AdFormat.AppOpen))
            {
                appOpen?.Destroy();
                appOpen = null;
                loadingAppOpen = false;
            }
            if ((fullScreenStageReady || reloadEntryAppOpen) && appOpenSession == null && !IsAppOpenReady && !loadingAppOpen)
                LoadAppOpen();
        }

        bool IsStale(AdFormat format)
        {
            var id = requestedAdUnitIds[(int)format];
            if (id == null || settings.GetAdUnitIds(format, Application.isEditor).Contains(id)) return false;
            Debug.Log($"[Ads] {format} ad unit {id} removed by remote config; reloading");
            requestedAdUnitIds[(int)format] = null;
            return true;
        }

        void DestroyAds()
        {
            generation++;
            bannerStageReady = nativeStageReady = fullScreenStageReady = false;
            CancelInvoke();
            Array.Clear(adUnitIndexes, 0, adUnitIndexes.Length);
            Array.Clear(cntRetry, 0, cntRetry.Length);
            var cancelledInterstitial = interstitialSession;
            var cancelledRewarded = rewardedSession;
            var cancelledAppOpen = appOpenSession;
            var cancelledRewardedInterstitial = rewardedInterstitialSession;
            interstitialSession = rewardedSession = appOpenSession = rewardedInterstitialSession = null;
            loadingBanner = loadingInterstitial = loadingRewarded = loadingAppOpen = loadingRewardedInterstitial = false;
            DestroyBanner();
            interstitial?.Destroy();
            interstitial = null;
            rewarded?.Destroy();
            rewarded = null;
            rewardedInterstitial?.Destroy();
            rewardedInterstitial = null;
            appOpen?.Destroy();
            appOpen = null;
            cancelledInterstitial?.Fail("cancelled");
            cancelledRewarded?.Fail("cancelled");
            cancelledRewardedInterstitial?.Fail("cancelled");
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
