using System;
using System.Threading;
using Cysharp.Threading.Tasks;
using Firebase.Analytics;
using StbEmu.Portal;
using StbEmu.Profiles;
using StbEmu.Storage;
using StbEmu.Services;
using UnityEngine;

namespace StbEmu.UI
{
    /// <summary>
    /// Composition root and screen flow: Profiles → Edit / Connecting → Portal (native overlay) or Error.
    /// Attach to Main. Instantiates the assigned canvas prefab, or loads it from Resources.
    /// </summary>
    public sealed class AppFlow : MonoBehaviour
    {
        static readonly TimeSpan ConnectTimeout = TimeSpan.FromSeconds(180);
        // Longer than native's 30 s portal-ready watchdog, so native reports the failure first (one Toast, one state change).
        static readonly TimeSpan ReconnectTimeout = TimeSpan.FromSeconds(180);

        enum State { Loading, Profiles, Edit, Connecting, Portal, Error, RewardPrompt }

        [SerializeField] GameObject appCanvasPrefab;
        [SerializeField] string appCanvasResourcePath = "AppCanvas";

        ProfileManager profiles;
        IProfileUi ui;
        StatusScreen status;
        State state = State.Loading;
        Profile current;


#if UNITY_ANDROID && !UNITY_EDITOR
        CancellationTokenSource connectCts;
        bool cancelledByUser;
        Android.AndroidStbHost host;
        Android.AndroidPortalView portal;
        bool reconnectingPortal;
        static StreamingAdPolicy viewing = new StreamingAdPolicy();
        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
        static void ResetViewingSession() => viewing = new StreamingAdPolicy();
        GoogleAdsMobileController ads;
        bool playerIsPlaying, appPaused, adTransition;
        string playingChannel = "";
        double nextAdPreload;
        double nextBannerPoll, nextBannerRequest;
        string bannerLayout = ""; // last "mode|widthDp" from native, which pushes it whenever its layout changes
        string nativeAdConfig = "";
        int bannerInset = -1;
        int rewardAttempt;
        const double AdBreakSeconds = 3; // ad break popup countdown before the rewarded ad plays
        double adBreakEnd;
        int adBreakShown; // last whole second written to the popup
        const double AdBreakRetrySeconds = 10; // a due break with no ad loaded asks again after this
        double nextAdBreakRequest;
        double upsellSeconds;
        string adAfterPlayChannel = ""; // picked while the portal was down: its interstitial waits for the new link to play
        bool portalRetried;   // one automatic reconnect before a no-stream portal failure shows Error
        int reconnectEpoch; // bumped by every reconnect outcome and by leaving the portal; a stale retry does nothing
#endif

        async UniTaskVoid Start()
        {
            // Portrait only; the native TV screen switches to sensor landscape while its video is full screen.
            Screen.orientation = ScreenOrientation.Portrait;

            // Black around the TV layout, not the skybox.
            var cam = Camera.main;
            if (cam != null)
            {
                cam.clearFlags = CameraClearFlags.SolidColor;
                cam.backgroundColor = Color.black;
            }

            var prefab = appCanvasPrefab;
            if (prefab == null && !string.IsNullOrWhiteSpace(appCanvasResourcePath))
                prefab = Resources.Load<GameObject>(appCanvasResourcePath);
            if (prefab == null || prefab.GetComponent<Canvas>() == null)
            {
                Debug.LogError("[AppFlow] Assign an AppCanvas prefab with a root Canvas, or set a valid Resources path.", this);
                enabled = false;
                return;
            }

            var canvas = Instantiate(prefab, transform, false).transform;
            canvas.name = "AppCanvas";
            canvas.gameObject.SetActive(true);
            UiKit.EnsureEventSystem();
            status = new StatusScreen(canvas);

#if UNITY_ANDROID && !UNITY_EDITOR
            host = new Android.AndroidStbHost();
            status.UseNativeLoading(host);
            host.NativeEvent += OnNativeEvent;
            // Profiles live in native views; the prefab's uGUI list/editor stay unused and hidden.
            foreach (var screen in new[] { "Profiles", "EditProfile" }) canvas.Find(screen)?.gameObject.SetActive(false);
            ui = new Android.AndroidProfileUi(host, Application.productName, Application.version);
#else
            ui = new CanvasProfileUi(canvas);
#endif
            ui.Add += () => ShowEdit(null);
            ui.Edit += ShowEdit;
            ui.Delete += p => DeleteAsync(p).Forget();
            ui.Selected += p => ConnectAsync(p).Forget();
            ui.Saved += p => SaveAsync(p).Forget();
            ui.Cancelled += ShowProfiles;

#if UNITY_ANDROID && !UNITY_EDITOR
            ads = GoogleAdsMobileController.Instance;
            if (ads != null)
            {
                ads.Shown += OnAdShown;
                ads.Closed += OnAdClosed;
                ads.Initialized += SyncNativeAdConfig;
                ads.ConsentUpdated += SyncNativeAdConfig;
            }
            if (RemoteConfigController.Instance != null) RemoteConfigController.Instance.Loaded += SyncNativeAdConfig;
            if (InAppPurchaseController.Instance != null) InAppPurchaseController.Instance.OwnershipChanged += SyncNativeAdConfig;
            SyncNativeAdConfig();
            portal = new Android.AndroidPortalView(host);
            portal.Error += OnPortalError;
            portal.PhaseChanged += phase =>
            {
                if (state != State.Connecting) return;
                string label = phase == "AUTHENTICATING" ? "Signing in" : phase.StartsWith("Retrying ") ? phase : "Loading channels";
                status.ShowConnecting(current.Name + " - " + label, CancelConnect);
            };
            portal.Unavailable += code => Debug.LogWarning($"Portal unavailable ({code}); current stream remains active.");
#endif

            profiles = new ProfileManager(new JsonProfileRepository(Application.persistentDataPath));
            try
            {
                await profiles.LoadAsync(destroyCancellationToken);
            }
            catch (ProfileStoreException e)
            {
                // ponytail: unreadable store → start empty in memory; the next save overwrites it (a .bak is kept by the repository).
                Debug.LogError(e);
            }
            if (!destroyCancellationToken.IsCancellationRequested) ShowProfiles();
        }

        void Update()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            if (host != null && Time.realtimeSinceStartupAsDouble >= nextBannerPoll)
            {
                nextBannerPoll = Time.realtimeSinceStartupAsDouble + 0.25;
                // Unity-side state (ads, screens) is re-checked locally; asking native is only a safety net.
                if (Time.realtimeSinceStartupAsDouble >= nextBannerRequest)
                {
                    nextBannerRequest = Time.realtimeSinceStartupAsDouble + 2;
                    host.RequestBannerLayout();
                }
                if (bannerLayout.Length > 0) UpdateBanner(bannerLayout);
            }
            if (ads != null && !ads.IsReady && state == State.Profiles && Time.realtimeSinceStartupAsDouble >= nextAdPreload)
            {
                // Retry a failed init (e.g. offline launch); may show the consent form, so never over a stream.
                nextAdPreload = Time.realtimeSinceStartupAsDouble + 30;
                ads.Initialize();
            }
            var policy = MonetizationController.Instance;
            // New timings wait for Home, so nothing lowers a threshold mid-stream.
            policy.SetHold(state == State.Connecting || state == State.Portal || state == State.RewardPrompt);
            bool streaming = state == State.Portal && playerIsPlaying && !appPaused && !adTransition && (ads == null || !ads.IsShowingFullScreen);
            if (streaming) policy.AddWatch(Time.unscaledDeltaTime);
            // Pro bought: drop a pending prompt.
            bool rewardBlocked = ads == null || ads.AdsDisabled;
            if (rewardBlocked && state == State.RewardPrompt && !adTransition) EndRewardPrompt();
            if (rewardBlocked && viewing.RewardRequired) viewing.GrantReward();
            // Every streaming second counts, ad loaded or not.
            viewing.Tick(Time.unscaledDeltaTime, streaming && !rewardBlocked, policy.Get(MonetizationPolicy.RewardedViewing));
            // A due break waits out the gap after the last full-screen ad (e.g. the interstitial that led into this stream).
            if (viewing.RewardRequired && state == State.Portal && !adTransition && ads != null
                && ads.GetTimingBlockReason(AdFormat.Rewarded) == null)
                RequestAdBreak();
            else if (state == State.RewardPrompt) TickAdBreak();
            if (InAppPurchaseController.Instance != null && InAppPurchaseController.Instance.IsPro)
            {
                upsellSeconds = 0;
                host.HideUpsell();
            }
            else if (!appPaused && !adTransition && state != State.RewardPrompt
                && (ads == null || (!ads.IsShowingFullScreen && !ads.IsConsentFlowRunning)))
            {
                if ((upsellSeconds += Time.unscaledDeltaTime) >= policy.Get(MonetizationPolicy.Upsell))
                {
                    upsellSeconds = 0;
                    host.ShowUpsell();
                }
            }
#endif
            // Android Back outside the TV screen (the native TV screen handles its own Back).
            // The ad break popup cannot be dismissed, so Back is ignored there.
            if (!Input.GetKeyDown(KeyCode.Escape)) return;
            switch (state)
            {
                case State.Profiles: Application.Quit(); break;
                case State.Edit:
                case State.Error: ShowProfiles(); break;
                case State.Connecting: CancelConnect(); break;
            }
        }

        void OnDestroy()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            rewardAttempt++;
            connectCts?.Cancel();
            connectCts?.Dispose();
            if (ads != null)
            {
                ads.Shown -= OnAdShown;
                ads.Closed -= OnAdClosed;
                ads.Initialized -= SyncNativeAdConfig;
                ads.ConsentUpdated -= SyncNativeAdConfig;
                ads.SetAppOpenSuppressed(false);
                ads.DestroyBanner();
            }
            if (RemoteConfigController.Instance != null) RemoteConfigController.Instance.Loaded -= SyncNativeAdConfig;
            if (InAppPurchaseController.Instance != null) InAppPurchaseController.Instance.OwnershipChanged -= SyncNativeAdConfig;
            if (host != null) host.NativeEvent -= OnNativeEvent;
            host?.Dispose();
#endif
        }

        void ShowProfiles()
        {
            HideAll();
            state = State.Profiles;
            ui.Show(profiles.Profiles);
        }

        void ShowEdit(Profile profile)
        {
            HideAll();
            state = State.Edit;
            ui.ShowEdit(profile);
        }

        void ShowError(string code)
        {
            HideAll();
            state = State.Error;
            status.ShowError(ErrorText.For(code), () => ConnectAsync(current).Forget(), ShowProfiles);
        }

        void HideAll()
        {
            ui.Hide();
            status.Hide();
        }

        async UniTaskVoid SaveAsync(Profile profile)
        {
            try
            {
                if (profiles.Find(profile.ProfileId) == null) await profiles.AddAsync(profile, destroyCancellationToken);
                else await profiles.UpdateAsync(profile, destroyCancellationToken);
                ShowProfiles();
            }
            catch (ProfileValidationException e)
            {
                ui.ShowErrors(e.Errors);
            }
            catch (Exception e) when (!(e is OperationCanceledException))
            {
                ui.ShowErrors(new[] { "Could not save: " + e.Message });
            }
        }

        async UniTaskVoid DeleteAsync(Profile profile)
        {
            try
            {
                await profiles.RemoveAsync(profile.ProfileId, destroyCancellationToken);
            }
            catch (Exception e) when (!(e is OperationCanceledException))
            {
                Debug.LogError(e); // ponytail: the row stays (manager rolled back); no user-facing message yet
            }
            if (!destroyCancellationToken.IsCancellationRequested) ShowProfiles();
        }

        async UniTaskVoid ConnectAsync(Profile profile)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            if (adTransition || state == State.RewardPrompt) return;
            connectCts?.Cancel();
            reconnectEpoch++;
            reconnectingPortal = false;
#endif
            current = profile;
            if (profile == null || ProfileValidator.Validate(profile, Array.Empty<Profile>()).Count != 0)
            {
#if UNITY_ANDROID && !UNITY_EDITOR
                await portal.CloseAsync(CancellationToken.None);
                host.Stop();
#endif
                TrackConnect(profile, "CONFIGURATION_INVALID");
                ShowError("CONFIGURATION_INVALID");
                return;
            }
            HideAll();
            state = State.Connecting;
            status.ShowConnecting(profile.Name, CancelConnect);

#if UNITY_ANDROID && !UNITY_EDITOR
            connectCts?.Cancel();
            connectCts = CancellationTokenSource.CreateLinkedTokenSource(destroyCancellationToken);
            var operationCts = connectCts;
            var snapshot = new PortalSession(profile);
            cancelledByUser = false;
            portalRetried = false;

            host.SetOverlayVisible(false);
            host.Stop();
            ads?.SetAppOpenSuppressed(true); // a channel replayed on connect would sound behind an app open shown mid-connect
            try
            {
                operationCts.CancelAfter(ConnectTimeout);
                await portal.OpenAsync(snapshot, operationCts.Token);
            }
            catch (OperationCanceledException)
            {
                if (!ReferenceEquals(connectCts, operationCts)) return;
                if (cancelledByUser) ShowProfiles();
                else if (!destroyCancellationToken.IsCancellationRequested)
                {
                    TrackConnect(profile, ErrorText.Timeout);
                    ShowError(ErrorText.Timeout);
                }
                return;
            }
            catch (PortalLoadException e)
            {
                if (!ReferenceEquals(connectCts, operationCts)) return;
                TrackConnect(profile, e.Reason);
                ShowError(e.Reason);
                return;
            }
            catch (Exception)
            {
                if (!ReferenceEquals(connectCts, operationCts)) return;
                TrackConnect(profile, "INVALID_RESPONSE");
                ShowError("INVALID_RESPONSE");
                return;
            }
            finally
            {
                operationCts.Dispose();
                if (ReferenceEquals(connectCts, operationCts)) ads?.SetAppOpenSuppressed(false);
                if (ReferenceEquals(connectCts, operationCts) && state != State.Connecting) connectCts = null;
            }
            if (!ReferenceEquals(connectCts, operationCts)) return;
            connectCts = null;
            TrackConnect(profile, null);
            HideAll();
            state = State.Portal;
            host.SetPortalVisible(true);
            host.SetOverlayVisible(true);
#else
            await UniTask.Yield();
            ShowError("Xtream playback only runs on an Android device.");
#endif
        }

        // error == null → success. User cancel and superseded connects are not tracked.
        static void TrackConnect(Profile profile, string error)
        {
            string url = profile?.ServerUrl ?? "";
            // Full url and username for support; the password is never sent.
            FirebaseController.Instance?.LogEvent("server_connect",
                new Parameter("url", Clip(url)),
                new Parameter("user", Clip(profile?.Username)),
                new Parameter("status", error == null ? "success" : "fail"),
                new Parameter("error", Clip(error)));
        }

        static string Clip(string text) => string.IsNullOrEmpty(text) ? "" : text.Substring(0, Math.Min(100, text.Length));

        void CancelConnect()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            cancelledByUser = true;
            connectCts?.Cancel(); // ConnectAsync catches it and shows the list
#else
            ShowProfiles();
#endif
        }

#if UNITY_ANDROID && !UNITY_EDITOR
        [Serializable]
        struct NativeAdConfigPayload
        {
            public bool enabled;
            public string[] adUnitIds;
        }

        void SyncNativeAdConfig()
        {
            if (host == null) return;
            var ids = ads != null && ads.CanShowNative ? ads.GetNativeAdUnitIds() : null;
            bool enabled = ids != null && ids.Count > 0;
            string json = JsonUtility.ToJson(new NativeAdConfigPayload
            {
                enabled = enabled,
                adUnitIds = enabled ? ids.ToArray() : Array.Empty<string>()
            });
            if (json == nativeAdConfig) return;
            nativeAdConfig = json;
            host.SetNativeAdConfig(json);
        }

        void OnNativeEvent(string type, string data)
        {
            if (type == "ui.fullscreen") { SetFullscreenOrientation(data); return; }
            if (type == "ui.banner-layout") { bannerLayout = data; UpdateBanner(data); return; }
            if (type == "ads.native")
            {
                var parts = data.Split(new[] { '|' }, 4);
                if (parts.Length >= 3)
                    ads?.TrackNativeEvent(parts[0], parts[1], parts[2], parts.Length > 3 ? parts[3] : "");
                return;
            }
            if (type == "ui.connect-cancel") { if (state == State.Connecting) CancelConnect(); return; }
            if (type == "ui.portal-retry") { if (state == State.Error) status.Retry(); return; }
            if (type == "ui.portal-back") { if (state == State.Error) status.Back(); return; }
            if (type == "channel.playing") { playingChannel = data; return; }
            if (type == "channel.ready") { OnChannelReady(data); return; }
            if (type == "isPlaying")
            {
                playerIsPlaying = data == "true";
                if (playerIsPlaying) viewing.RecordPlaying(playingChannel);
                return;
            }
            if (type == "error") { playerIsPlaying = false; return; }
            // Native asks once per outage, so a reconnect is never dropped for an ad.
            if (type == "ui.refresh" || type == "ui.portal-reconnect")
            {
                if (state == State.Portal || state == State.RewardPrompt) ReconnectPortalAsync().Forget();
                return;
            }
            if (state != State.Portal || adTransition) return;
            if (type == "ui.home") ReturnHomeWithAdAsync().Forget();
            else if (type == "ui.channel-selected") SelectChannelWithAdAsync(data).Forget();
            else if (type == "ui.channel-selected.reconnect") SelectChannelThenAd(data);
        }

        void OnApplicationPause(bool paused)
        {
            appPaused = paused;
            if (paused) ads?.HideBanner();
            // Back from the background mid-countdown: give the viewer the full countdown again.
            else if (state == State.RewardPrompt && !adTransition) StartAdBreakCountdown();
        }

        // Portrait everywhere except full-screen video: always landscape, flipping between both sides only when
        // the system auto-rotate is on ("landscape"); "landscape-fixed" stays on one side.
        static void SetFullscreenOrientation(string mode)
        {
            bool landscape = mode == "landscape" || mode == "landscape-fixed";
            Screen.autorotateToPortrait = Screen.autorotateToPortraitUpsideDown = !landscape;
            Screen.autorotateToLandscapeLeft = Screen.autorotateToLandscapeRight = landscape;
            Screen.orientation = mode == "landscape" ? ScreenOrientation.AutoRotation
                : mode == "landscape-fixed" ? ScreenOrientation.LandscapeLeft : ScreenOrientation.Portrait;
        }

        void UpdateBanner(string layout)
        {
            SyncNativeAdConfig();
            var parts = layout.Split('|');
            if (parts.Length != 2 || !int.TryParse(parts[0], out var mode) || !int.TryParse(parts[1], out var width)) return;
            bool allowed = ads != null && !ads.AdsDisabled && ads.IsReady && !ads.IsConsentFlowRunning
                && !ads.IsShowingFullScreen && !appPaused && !adTransition
                && (state == State.Profiles || state == State.Portal || state == State.Connecting || state == State.Error)
                && mode != 0 && width > 0;
            int inset = 0;
            if (!allowed) ads?.HideBanner();
            else
            {
                ads.ShowBanner(GoogleMobileAds.Api.AdPosition.Bottom,
                    placement: state == State.Profiles ? "profiles" : state == State.Portal ? "tv_portrait" : "portal_status",
                    widthDp: width);
                inset = Mathf.CeilToInt(ads.BannerHeightPixels);
            }
            if (bannerInset == inset) return;
            bannerInset = inset;
            host.SetBannerInset(inset);
        }

        void OnAdShown(AdFormat format)
        {
            if (format == AdFormat.Banner || format == AdFormat.Native) return;
            ads?.HideBanner();
            // The reward prompt leaves the stream playing; only the ad itself pauses it.
            if (state == State.Portal || state == State.RewardPrompt) host.SetPlaybackGate(true);
        }

        void OnAdClosed(AdFormat format)
        {
            if (format == AdFormat.Banner || format == AdFormat.Native) return;
            if (state == State.Portal && !adTransition) host.SetPlaybackGate(false);
            if (bannerLayout.Length > 0) UpdateBanner(bannerLayout);
        }

        // True when the ad was actually shown and closed.
        async UniTask<bool> ShowInterstitialAndWaitAsync(string placement, CancellationToken ct)
        {
            if (ads == null || !ads.IsInterstitialReady) return false;
            // Blocked by timing: still reported as skipped, but without pausing and hiding the stream for nothing.
            if (ads.GetTimingBlockReason(AdFormat.Interstitial) != null) { ads.ShowInterstitial(null, placement); return false; }
            var completion = new UniTaskCompletionSource();
            bool shown = false;
            host.SetPlaybackGate(true);
            ads.SetAppOpenSuppressed(true);
            try
            {
                ads.ShowInterstitial(new AdCallbacks
                {
                    OnClosed = () => { shown = true; completion.TrySetResult(); },
                    OnFailed = _ => completion.TrySetResult()
                }, placement);
                await completion.Task.AttachExternalCancellation(ct);
                return shown;
            }
            finally
            {
                if (this != null)
                {
                    host.SetPlaybackGate(false);
                    ads.SetAppOpenSuppressed(false);
                }
            }
        }

        async UniTask ReturnHomeWithAdAsync()
        {
            if (adTransition) return;
            adTransition = true;
            CloseAndShow(ShowProfiles);
            try { await ShowInterstitialAndWaitAsync("home", destroyCancellationToken); }
            finally { adTransition = false; }
        }

        async UniTask SelectChannelWithAdAsync(string channelId)
        {
            if (adTransition || state != State.Portal) return;
            adAfterPlayChannel = ""; // a newer pick replaces one still waiting for its link
            adTransition = true;
            try
            {
                if (viewing.ShouldShowChannelInterstitial(channelId))
                    await ShowInterstitialAndWaitAsync("channel_change", destroyCancellationToken);
                if (this != null && state == State.Portal) host.ContinueChannelSelection(channelId);
            }
            finally { adTransition = false; }
        }

        // Portal down at the pick: reconnect and fetch the link now; native parks the new stream under the gate
        // (channel.ready) and the ad plays while it buffers, so the viewer does not sit through an ad and then the reconnect.
        void SelectChannelThenAd(string channelId)
        {
            bool hold = viewing.ShouldShowChannelInterstitial(channelId) && ads != null && ads.IsInterstitialReady;
            adAfterPlayChannel = hold ? channelId : "";
            host.ContinueChannelSelection(channelId, hold);
        }

        // Native has parked this channel's stream under the gate; whoever runs next must lift it or the stream never starts.
        void OnChannelReady(string channelId)
        {
            bool adWaiting = channelId.Length > 0 && channelId == adAfterPlayChannel;
            adAfterPlayChannel = "";
            if (adTransition || state != State.Portal) return; // that ad or reward prompt lifts the gate when it ends
            if (adWaiting) ShowChannelAdAfterLinkAsync().Forget();
            else host.SetPlaybackGate(false);
        }

        async UniTaskVoid ShowChannelAdAfterLinkAsync()
        {
            adTransition = true;
            try { await ShowInterstitialAndWaitAsync("channel_change", destroyCancellationToken); }
            finally
            {
                adTransition = false;
                if (this != null) host.SetPlaybackGate(false); // also when no ad showed (not ready / cooldown)
            }
        }

        // Streaming time is up: break now if a rewarded (or rewarded interstitial) ad is ready, otherwise check again in 10s without triggering a load.
        void RequestAdBreak()
        {
            if (Time.realtimeSinceStartupAsDouble < nextAdBreakRequest) return;
            if (ads.IsAdBreakReady) ShowRewardPrompt();
            else ScheduleAdBreakRetry();
        }

        void ScheduleAdBreakRetry()
        {
            nextAdBreakRequest = Time.realtimeSinceStartupAsDouble + AdBreakRetrySeconds;
        }

        void ShowRewardPrompt()
        {
            state = State.RewardPrompt;
            host.HideUpsell();
            ads?.SetAppOpenSuppressed(true);
            HideAll();
            StartAdBreakCountdown();
        }

        void StartAdBreakCountdown()
        {
            adBreakEnd = Time.realtimeSinceStartupAsDouble + AdBreakSeconds;
            adBreakShown = -1;
            TickAdBreak();
        }

        // Ad break: counts down on the popup, then plays the ad. There is no way to decline.
        void TickAdBreak()
        {
            if (state != State.RewardPrompt || adTransition) return;
            int left = (int)Math.Ceiling(adBreakEnd - Time.realtimeSinceStartupAsDouble);
            if (left <= 0) { WatchReward(); return; }
            if (left == adBreakShown) return;
            adBreakShown = left;
            host.ShowRewardPrompt($"Ad starting in {left}...");
        }

        void WatchReward()
        {
            if (state != State.RewardPrompt || adTransition) return;
            // Ad lost during the countdown: back to the stream with the break still due; it is requested again shortly.
            if (ads == null || !ads.IsAdBreakReady)
            {
                LeaveRewardPrompt();
                ScheduleAdBreakRetry();
                return;
            }
            adTransition = true;
            int attempt = ++rewardAttempt;
            host.ShowRewardPrompt("Starting ad...");
            // Rewarded first, rewarded interstitial as the fallback. The ad was loaded and opened, so closing it early
            // or a failure mid-show still counts as a completed break: either way a new full cycle starts.
            ads.ShowAdBreak(new AdCallbacks
            {
                OnClosed = () =>
                {
                    if (this != null && attempt == rewardAttempt && state == State.RewardPrompt) EndRewardPrompt();
                },
                OnFailed = _ =>
                {
                    if (this != null && attempt == rewardAttempt && state == State.RewardPrompt) EndRewardPrompt();
                }
            }, "continue_streaming");
        }

        // Back to the stream; the break (watched, closed early, failed or dropped) starts a new cycle.
        void EndRewardPrompt()
        {
            viewing.GrantReward();
            LeaveRewardPrompt();
        }

        void LeaveRewardPrompt()
        {
            adTransition = false;
            host.HideRewardPrompt();
            state = State.Portal;
            host.SetPlaybackGate(false);
            ads?.SetAppOpenSuppressed(false);
        }

        async UniTaskVoid ReconnectPortalAsync()
        {
            if (current == null) return;
            if (reconnectingPortal)
            {
                return;
            }
            reconnectingPortal = true;
            int operation = ++reconnectEpoch;
            connectCts?.Cancel();
            connectCts = CancellationTokenSource.CreateLinkedTokenSource(destroyCancellationToken);
            var operationCts = connectCts;
            operationCts.CancelAfter(ReconnectTimeout);
            bool failed = false;
            try
            {
                await portal.ReconnectAsync(new PortalSession(current), operationCts.Token);
                if (operation != reconnectEpoch) return;
                portalRetried = false;
                host.SetPortalVisible(true);
            }
            catch (OperationCanceledException)
            {
                failed = operation == reconnectEpoch && !destroyCancellationToken.IsCancellationRequested;
                if (failed) Debug.LogWarning("Portal reconnect timed out; current stream remains active.");
            }
            catch (PortalLoadException e)
            {
                failed = operation == reconnectEpoch && PortalFailurePolicy.CanRetry(e.Reason);
                if (!failed && operation == reconnectEpoch) host.PortalReconnectFailed();
                Debug.LogWarning($"Portal reconnect failed ({e.Reason}); current stream remains active.");
            }
            finally
            {
                operationCts.Dispose();
                if (ReferenceEquals(connectCts, operationCts)) connectCts = null;
                if (operation == reconnectEpoch) reconnectingPortal = false;
                bool inPortal = state == State.Portal || state == State.RewardPrompt;
                if (operation == reconnectEpoch && failed && inPortal) host.PortalReconnectFailed();
            }
        }

        void OnPortalError(string code)
        {
            if (state != State.Portal) return;
            if (portalRetried || !PortalFailurePolicy.CanRetry(code))
            {
                CloseAndShow(() => ShowError(code));
                return;
            }
            portalRetried = true;
            ReconnectPortalAsync().Forget();
        }

        void CloseAndShow(Action next)
        {
            rewardAttempt++;
            host.HideRewardPrompt();
            host.HideUpsell();
            viewing.Disconnect();
            playerIsPlaying = false;
            playingChannel = "";
            ads?.SetAppOpenSuppressed(false);
            reconnectingPortal = false;
            reconnectEpoch++;
            adAfterPlayChannel = "";
            connectCts?.Cancel();
            host.SetOverlayVisible(false);
            host.Stop();
            portal.CloseAsync(CancellationToken.None).Forget();
            host.SetPlaybackGate(false);
            next();
        }
#endif
    }
}
