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
        static StreamingAdPolicy viewing = new StreamingAdPolicy();
        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
        static void ResetViewingSession() => viewing = new StreamingAdPolicy();
        GoogleAdsMobileController ads;
        bool playerIsPlaying, appPaused, adTransition;
        string playingChannel = "";
        double nextAdPreload;
        int rewardAttempt;
        string adAfterPlayChannel = ""; // picked while the portal was down: its interstitial waits for the new link to play
        bool portalRetried;   // one automatic reconnect before a no-stream portal failure shows Error
        int reconnectEpoch; // bumped by every reconnect outcome and by leaving the portal; a stale retry does nothing
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
            portal.PhaseChanged += phase =>
            {
                if (state != State.Connecting) return;
                string label = phase == "AUTHENTICATING" ? "Authenticating" : phase == "VALIDATING" ? "Checking account" : phase.StartsWith("Retrying ") ? phase : "Loading channels";
                status.ShowConnecting(current.Name + " - " + label, CancelConnect);
            };
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
            if (ads != null && Time.realtimeSinceStartupAsDouble >= nextAdPreload)
            {
                if (ads.IsReady) PreloadViewingAds();
                else if (state == State.Profiles) // retry a failed init (e.g. offline launch); may show the consent form, so never over a stream
                {
                    nextAdPreload = Time.realtimeSinceStartupAsDouble + 30;
                    ads.Initialize();
                }
            }
            // Only time with a rewarded ad in hand counts, so an unfillable reward never locks the viewer out.
            bool watching = state == State.Portal && playerIsPlaying && !appPaused && !adTransition &&
                ads != null && ads.IsRewardedReady && !ads.IsShowingFullScreen;
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
            // Connection timeout starts after any reconnect interstitial closes.
            cancelledByUser = false;
            portalRetried = false;

            host.SetOverlayVisible(false);
            host.Stop();
            controller?.Dispose();
            controller = new PortalController(portal, player, profile.Compatibility?.PlayerUserAgentOverride);
            ads?.SetAppOpenSuppressed(true); // the portal's autoplay would sound behind an app open shown mid-connect
            try
            {
                if (viewing.ConsumeReconnectInterstitial())
                {
                    adTransition = true;
                    try { await ShowInterstitialAndWaitAsync("reconnect_after_reward_exit", operationCts.Token); }
                    finally { adTransition = false; }
                    ads?.SetAppOpenSuppressed(true); // the interstitial's cleanup lifted it
                    operationCts.Token.ThrowIfCancellationRequested();
                }
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
            ShowError("The portal only runs on an Android device (the Editor has no WebView).");
#endif
        }

        // error == null → success. User cancel and superseded connects are not tracked.
        static void TrackConnect(Profile profile, string error)
        {
            var id = profile?.Identity;
            string url = profile?.PortalUrl ?? "";
            string host = Uri.TryCreate(url, UriKind.Absolute, out var uri) ? uri.Host : url;
            FirebaseController.Instance?.LogEvent("portal_connect",
                new Parameter("host", Clip(host)),
                new Parameter("mac", Clip(id?.Mac)),
                new Parameter("sn", Clip(id?.SerialNumber)),
                new Parameter("device_id", Clip(id?.DeviceId)),
                new Parameter("device_id2", Clip(id?.DeviceId2)),
                new Parameter("signature", Clip(id?.Signature)),
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
        void OnNativeEvent(string type, string data)
        {
            if (type == "channel.playing") { playingChannel = data; return; }
            if (type == "channel.ready") { OnChannelReady(data); return; }
            if (type == "isPlaying")
            {
                playerIsPlaying = data == "true";
                if (playerIsPlaying) viewing.RecordPlaying(playingChannel);
                return;
            }
            if (type == "error") { playerIsPlaying = false; return; }
            if (type == "ui.reward-watch") { WatchReward(); return; }
            if (type == "ui.reward-exit") { ExitReward(); return; }
            // Native asks once per outage, so a reconnect is never dropped for an ad; the hidden portal reloads under it.
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
            // The reward prompt leaves the stream playing; only the ad itself pauses it.
            if (format != AdFormat.Banner && (state == State.Portal || state == State.RewardPrompt)) host.SetPlaybackGate(true);
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
            // Blocked by timing: still reported as skipped, but without pausing and hiding the stream for nothing.
            if (ads.GetTimingBlockReason(AdFormat.Interstitial) != null) { ads.ShowInterstitial(null, placement); return; }
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

        void ShowRewardPrompt()
        {
            state = State.RewardPrompt;
            ads?.SetAppOpenSuppressed(true);
            HideAll();
            PreloadViewingAds();
            RenderRewardPrompt();
        }

        void RenderRewardPrompt(string message = null)
        {
            if (state != State.RewardPrompt) return;
            bool ready = ads != null && ads.IsRewardedReady;
            host.ShowRewardPrompt(message ?? (ready
                ? "Watch an ad to continue streaming, or Exit to return Home."
                : "An ad is loading. Watch when ready, or Exit to return Home."),
                ready && !adTransition, !adTransition);
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
            bool earned = false;
            RenderRewardPrompt("Watching ad...");
            ads.ShowRewarded(_ =>
            {
                earned = true;
                // Rarely arrives after the close: upgrade the skip already applied to a full reward.
                if (this != null && attempt == rewardAttempt && state == State.Portal) viewing.GrantReward();
            }, new AdCallbacks
            {
                // Closing the ad always resumes the stream; closing it early only brings the next prompt sooner.
                OnClosed = () =>
                {
                    if (this == null || attempt != rewardAttempt || state != State.RewardPrompt) return;
                    if (earned) viewing.GrantReward();
                    else viewing.SkipReward(adSettings != null ? adSettings.RewardedViewingSeconds : 600,
                        adSettings != null ? adSettings.RewardedSkippedViewingSeconds : 180);
                    adTransition = false;
                    host.HideRewardPrompt();
                    state = State.Portal;
                    host.SetPlaybackGate(false);
                    ads.SetAppOpenSuppressed(false);
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
            controller?.Dispose();
            controller = null;
            portal.CloseAsync(CancellationToken.None).Forget();
            host.SetPlaybackGate(false);
            next();
        }
#endif
    }
}
