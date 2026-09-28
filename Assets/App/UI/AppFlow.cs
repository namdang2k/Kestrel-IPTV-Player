using System;
using System.Threading;
using Cysharp.Threading.Tasks;
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
        static readonly TimeSpan ConnectTimeout = TimeSpan.FromSeconds(30);
        // Longer than native's 30 s portal-ready watchdog, so native reports the failure first (one Toast, one state change).
        static readonly TimeSpan ReconnectTimeout = TimeSpan.FromSeconds(35);

        enum State { Loading, Profiles, Edit, Connecting, Portal, Error, RewardPrompt }

        [SerializeField] GameObject appCanvasPrefab;
        [SerializeField] string appCanvasResourcePath = "AppCanvas";

        ProfileManager profiles;
        ProfileListScreen list;
        ProfileEditScreen edit;
        StatusScreen status;
        State state = State.Loading;
        Profile current;
        [SerializeField] AdMobSettings adSettings;


#if UNITY_ANDROID && !UNITY_EDITOR
        CancellationTokenSource connectCts;
        bool cancelledByUser;
        Android.AndroidStbHost host;
        Android.AndroidPortalView portal;
        Android.AndroidPlayer player;
        PortalController controller; // per connect: headers come from the profile
        bool reconnectingPortal;
        bool reconnectQueued; // a reconnect requested while one was in flight runs after it
        static StreamingAdPolicy viewing = new StreamingAdPolicy();
        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
        static void ResetViewingSession() => viewing = new StreamingAdPolicy();
        GoogleAdsMobileController ads;
        bool playerIsPlaying, appPaused, adTransition;
        string playingChannel = "";
        double nextAdPreload;
        int rewardAttempt;
        bool portalRetried;   // one automatic reconnect before a no-stream portal failure shows Error
#endif

        async UniTaskVoid Start()
        {
            // Follow the device sensor; the facade re-fits the portal's 1280x720 viewport on every rotation.
            Screen.autorotateToPortrait = Screen.autorotateToLandscapeLeft = Screen.autorotateToLandscapeRight = true;
            Screen.autorotateToPortraitUpsideDown = false;
            Screen.orientation = ScreenOrientation.AutoRotation;

            // The portal WebView is transparent (video plays under it): show black around the TV layout, not the skybox.
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
            list = new ProfileListScreen(canvas);
            edit = new ProfileEditScreen(canvas);
            status = new StatusScreen(canvas);

            list.Add += () => ShowEdit(null);
            list.Edit += ShowEdit;
            list.Delete += p => DeleteAsync(p).Forget();
            list.Selected += p => ConnectAsync(p).Forget();
            edit.Saved += p => SaveAsync(p).Forget();
            edit.Cancelled += ShowProfiles;

#if UNITY_ANDROID && !UNITY_EDITOR
            host = new Android.AndroidStbHost();
            host.NativeEvent += OnNativeEvent;
            if (adSettings == null) adSettings = Resources.Load<AdMobSettings>(AdMobSettings.ResourcePath);
            ads = GoogleAdsMobileController.Instance;
            if (ads != null)
            {
                ads.Initialized += PreloadViewingAds;
                ads.Loaded += OnAdLoaded;
                ads.Shown += OnAdShown;
                ads.Closed += OnAdClosed;
                if (ads.IsReady) PreloadViewingAds();
            }
            portal = new Android.AndroidPortalView(host);
            player = new Android.AndroidPlayer(host);
            portal.Error += OnPortalError;
            portal.Unavailable += code => Debug.LogWarning($"Portal unavailable ({code}); current stream remains active.");
            portal.MessageReceived += m =>
            {
                if (m.Type == PortalMessage.Unsupported) Debug.Log($"[Portal] unsupported API: {m.ApiName}");
            };
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
            if (ads != null && ads.IsReady && Time.realtimeSinceStartupAsDouble >= nextAdPreload)
                PreloadViewingAds();
            bool watching = state == State.Portal && playerIsPlaying && !appPaused && !adTransition &&
                (ads == null || (!ads.IsShowingFullScreen && !ads.IsConsentFlowRunning));
            if (viewing.Tick(Time.unscaledDeltaTime, watching, adSettings != null ? adSettings.RewardedViewingSeconds : 600))
                ShowRewardPrompt();
#endif
            // Android Back outside the portal (in the portal the WebView has focus and native forwards Back to the portal).
            if (!Input.GetKeyDown(KeyCode.Escape)) return;
            switch (state)
            {
                case State.Profiles: Application.Quit(); break;
                case State.Edit:
                case State.Error: ShowProfiles(); break;
                case State.Connecting: CancelConnect(); break;
#if UNITY_ANDROID && !UNITY_EDITOR
                case State.RewardPrompt: ExitReward(); break;
#endif
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
                ads.Initialized -= PreloadViewingAds;
                ads.Loaded -= OnAdLoaded;
                ads.Shown -= OnAdShown;
                ads.Closed -= OnAdClosed;
                ads.SetAppOpenSuppressed(false);
            }
            if (host != null) host.NativeEvent -= OnNativeEvent;
            controller?.Dispose();
            host?.Dispose();
#endif
        }

        void ShowProfiles()
        {
            HideAll();
            if (profiles.Profiles.Count == 0)
            {
                ShowEdit(null);
                return;
            }
            state = State.Profiles;
            list.Show(profiles.Profiles);
        }

        void ShowEdit(Profile profile)
        {
            HideAll();
            state = State.Edit;
            edit.Show(profile);
        }

        void ShowError(string code)
        {
            HideAll();
            state = State.Error;
            status.ShowError(ErrorText.For(code), () => ConnectAsync(current).Forget(), ShowProfiles);
        }

        void HideAll()
        {
            list.Hide();
            edit.Hide();
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
                edit.ShowErrors(e.Errors);
            }
            catch (Exception e) when (!(e is OperationCanceledException))
            {
                edit.ShowErrors(new[] { "Could not save: " + e.Message });
            }
        }

        async UniTaskVoid DeleteAsync(Profile profile)
        {
            await profiles.RemoveAsync(profile.ProfileId, destroyCancellationToken);
            ShowProfiles();
        }

        async UniTaskVoid ConnectAsync(Profile profile)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            if (adTransition || state == State.Connecting || state == State.RewardPrompt) return;
#endif
            current = profile;
            HideAll();
            state = State.Connecting;
            status.ShowConnecting(profile.Name, CancelConnect);

#if UNITY_ANDROID && !UNITY_EDITOR
            connectCts?.Cancel();
            connectCts = CancellationTokenSource.CreateLinkedTokenSource(destroyCancellationToken);
            // Connection timeout starts after any reconnect interstitial closes.
            cancelledByUser = false;
            portalRetried = false;

            host.SetOverlayVisible(false);
            host.Stop();
            controller?.Dispose();
            controller = new PortalController(portal, player, profile.Compatibility?.UserAgentOverride);
            try
            {
                if (viewing.ConsumeReconnectInterstitial())
                {
                    adTransition = true;
                    try { await ShowInterstitialAndWaitAsync("reconnect_after_reward_exit", connectCts.Token); }
                    finally { adTransition = false; }
                    connectCts.Token.ThrowIfCancellationRequested();
                }
                connectCts.CancelAfter(ConnectTimeout);
                await portal.OpenAsync(new PortalSession(profile), connectCts.Token);
            }
            catch (OperationCanceledException)
            {
                if (cancelledByUser) ShowProfiles();
                else if (!destroyCancellationToken.IsCancellationRequested) ShowError(ErrorText.Timeout);
                return;
            }
            catch (PortalLoadException e)
            {
                ShowError(e.Reason);
                return;
            }
            HideAll();
            state = State.Portal;
            host.SetPortalVisible(true);
            host.SetOverlayVisible(true);
#else
            await UniTask.Yield();
            ShowError("The portal only runs on an Android device (the Editor has no WebView).");
#endif
        }

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
        void OnNativeEvent(string type, string data)
        {
            if (type == "channel.playing") { playingChannel = data; return; }
            if (type == "isPlaying")
            {
                playerIsPlaying = data == "true";
                if (playerIsPlaying) viewing.RecordPlaying(playingChannel);
                return;
            }
            if (type == "error") { playerIsPlaying = false; return; }
            if (state != State.Portal || adTransition) return;
            if (type == "ui.refresh") ReconnectPortalAsync().Forget();
            else if (type == "ui.portal-reconnect") ReconnectPortalAsync().Forget();
            else if (type == "ui.home") ReturnHomeWithAdAsync().Forget();
            else if (type == "ui.channel-selected") SelectChannelWithAdAsync(data).Forget();
        }

        void OnApplicationPause(bool paused) => appPaused = paused;

        void PreloadViewingAds()
        {
            nextAdPreload = Time.realtimeSinceStartupAsDouble + 30;
            if (ads == null || !ads.IsReady || ads.IsShowingFullScreen) return;
            ads.LoadInterstitial();
            ads.LoadRewarded();
        }

        void OnAdLoaded(AdFormat format)
        {
            if (format == AdFormat.Rewarded && state == State.RewardPrompt && !adTransition)
                RenderRewardPrompt();
        }

        void OnAdShown(AdFormat format)
        {
            if (format != AdFormat.Banner && state == State.Portal) host.SetPlaybackGate(true);
        }

        void OnAdClosed(AdFormat format)
        {
            if (format != AdFormat.Banner && state == State.Portal && !adTransition)
                host.SetPlaybackGate(false);
        }

        async UniTask ShowInterstitialAndWaitAsync(string placement, CancellationToken ct)
        {
            if (ads == null) return;
            if (!ads.IsInterstitialReady) { ads.LoadInterstitial(); return; }
            var completion = new UniTaskCompletionSource();
            host.SetPlaybackGate(true);
            ads.SetAppOpenSuppressed(true);
            try
            {
                ads.ShowInterstitial(new AdCallbacks
                {
                    OnClosed = () => completion.TrySetResult(),
                    OnFailed = _ => completion.TrySetResult()
                }, placement);
                await completion.Task.AttachExternalCancellation(ct);
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
            adTransition = true;
            try
            {
                if (viewing.ShouldShowChannelInterstitial(channelId))
                    await ShowInterstitialAndWaitAsync("channel_change", destroyCancellationToken);
                if (this != null && state == State.Portal) host.ContinueChannelSelection(channelId);
            }
            finally { adTransition = false; }
        }

        void ShowRewardPrompt()
        {
            state = State.RewardPrompt;
            host.SetPlaybackGate(true);
            ads?.SetAppOpenSuppressed(true);
            HideAll();
            PreloadViewingAds();
            RenderRewardPrompt();
        }

        void RenderRewardPrompt(string message = null)
        {
            if (state != State.RewardPrompt) return;
            bool ready = ads != null && ads.IsRewardedReady;
            status.ShowRewardPrompt(message ?? (ready
                ? "Watch an ad to continue streaming, or Exit to return Home."
                : "An ad is loading. Watch when ready, or Exit to return Home."),
                WatchReward, ExitReward, ready && !adTransition, !adTransition);
        }

        void WatchReward()
        {
            if (state != State.RewardPrompt || adTransition) return;
            if (ads == null || !ads.IsRewardedReady)
            {
                PreloadViewingAds();
                RenderRewardPrompt("Ad unavailable. Please wait and try again, or Exit.");
                return;
            }
            adTransition = true;
            int attempt = ++rewardAttempt;
            bool earned = false, closed = false;
            RenderRewardPrompt("Watching ad...");
            void CompleteIfEarned()
            {
                if (this == null || attempt != rewardAttempt || state != State.RewardPrompt) return;
                if (!earned || !closed) return;
                viewing.GrantReward();
                adTransition = false;
                status.Hide();
                state = State.Portal;
                host.SetPlaybackGate(false);
                ads.SetAppOpenSuppressed(false);
            }
            ads.ShowRewarded(_ =>
            {
                earned = true;
                CompleteIfEarned();
            }, new AdCallbacks
            {
                OnClosed = () =>
                {
                    if (this == null || attempt != rewardAttempt || state != State.RewardPrompt) return;
                    closed = true;
                    adTransition = false;
                    CompleteIfEarned();
                    if (state == State.RewardPrompt) RenderRewardPrompt("Reward not earned. Watch an ad to continue, or Exit.");
                },
                OnFailed = _ =>
                {
                    if (this == null || attempt != rewardAttempt || state != State.RewardPrompt) return;
                    adTransition = false;
                    RenderRewardPrompt("Ad unavailable. Please try again when ready, or Exit.");
                }
            }, "continue_streaming");
        }

        void ExitReward()
        {
            if (state != State.RewardPrompt || adTransition) return;
            viewing.ExitReward();
            CloseAndShow(ShowProfiles);
        }

        async UniTaskVoid ReconnectPortalAsync()
        {
            if (current == null) return;
            if (reconnectingPortal)
            {
                reconnectQueued = true;
                return;
            }
            reconnectingPortal = true;
            connectCts?.Cancel();
            connectCts = CancellationTokenSource.CreateLinkedTokenSource(destroyCancellationToken);
            connectCts.CancelAfter(ReconnectTimeout);
            try
            {
                await portal.ReconnectAsync(new PortalSession(current), connectCts.Token);
                portalRetried = false;
                host.SetPortalVisible(true);
            }
            catch (OperationCanceledException)
            {
                if (!destroyCancellationToken.IsCancellationRequested)
                    Debug.LogWarning("Portal reconnect timed out; current stream remains active.");
            }
            catch (PortalLoadException e)
            {
                Debug.LogWarning($"Portal reconnect failed ({e.Reason}); current stream remains active.");
            }
            finally
            {
                reconnectingPortal = false;
                if (reconnectQueued && state == State.Portal)
                {
                    reconnectQueued = false;
                    ReconnectPortalAsync().Forget();
                }
            }
        }

        void OnPortalError(string code)
        {
            if (state != State.Portal) return;
            if (portalRetried || code == ErrorText.AuthFailedRepeated) // re-login would only kick the other device again
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
            viewing.Disconnect();
            playerIsPlaying = false;
            playingChannel = "";
            ads?.SetAppOpenSuppressed(false);
            reconnectQueued = false;
            connectCts?.Cancel();
            host.SetOverlayVisible(false);
            host.Stop();
            controller?.Dispose();
            controller = null;
            portal.CloseAsync(CancellationToken.None).Forget();
            host.SetPlaybackGate(false);
            next();
        }
#endif
    }
}
