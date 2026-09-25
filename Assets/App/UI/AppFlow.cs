using System;
using System.Threading;
using Cysharp.Threading.Tasks;
using StbEmu.Portal;
using StbEmu.Profiles;
using StbEmu.Storage;
using UnityEngine;

namespace StbEmu.UI
{
    /// <summary>
    /// Composition root and screen flow: Profiles → Edit / Connecting → Portal (native overlay) or Error.
    /// Creates itself after the first scene loads; the scene needs no setup.
    /// </summary>
    public sealed class AppFlow : MonoBehaviour
    {
        static readonly TimeSpan ConnectTimeout = TimeSpan.FromSeconds(30);

        enum State { Loading, Profiles, Edit, Connecting, Portal, Error }

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Boot()
        {
            if (FindAnyObjectByType<AppFlow>() == null) new GameObject(nameof(AppFlow)).AddComponent<AppFlow>();
        }

        ProfileManager profiles;
        ProfileListScreen list;
        ProfileEditScreen edit;
        StatusScreen status;
        State state = State.Loading;
        Profile current;

#if UNITY_ANDROID && !UNITY_EDITOR
        CancellationTokenSource connectCts;
        bool cancelledByUser;
        Android.AndroidStbHost host;
        Android.AndroidPortalView portal;
        Android.AndroidPlayer player;
        PortalController controller; // per connect: headers come from the profile
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

            var canvas = UiKit.CreateCanvas();
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
            portal = new Android.AndroidPortalView(host);
            player = new Android.AndroidPlayer(host);
            portal.Error += OnPortalError;
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
            ShowProfiles();
        }

        void Update()
        {
            // Android Back outside the portal (in the portal the WebView has focus and native forwards Back to the portal).
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
            connectCts?.Cancel();
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
                edit.ShowErrors(new[] { "Không lưu được: " + e.Message });
            }
        }

        async UniTaskVoid DeleteAsync(Profile profile)
        {
            await profiles.RemoveAsync(profile.ProfileId, destroyCancellationToken);
            ShowProfiles();
        }

        async UniTaskVoid ConnectAsync(Profile profile)
        {
            current = profile;
            HideAll();
            state = State.Connecting;
            status.ShowConnecting(profile.Name, CancelConnect);

#if UNITY_ANDROID && !UNITY_EDITOR
            connectCts?.Cancel();
            connectCts = CancellationTokenSource.CreateLinkedTokenSource(destroyCancellationToken);
            connectCts.CancelAfter(ConnectTimeout);
            cancelledByUser = false;

            host.SetOverlayVisible(false);
            host.Stop();
            controller?.Dispose();
            controller = new PortalController(portal, player, profile.Compatibility?.UserAgentOverride);
            try
            {
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
            ShowError("Portal chỉ chạy trên thiết bị Android (Editor không có WebView).");
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
            if (state != State.Portal) return;
            if (type == "ui.refresh") ConnectAsync(current).Forget();
            else if (type == "ui.home") CloseAndShow(ShowProfiles);
        }

        void OnPortalError(string code)
        {
            if (state == State.Portal) CloseAndShow(() => ShowError(code));
        }

        void CloseAndShow(Action next)
        {
            host.SetOverlayVisible(false);
            host.Stop();
            portal.CloseAsync(CancellationToken.None).Forget();
            next();
        }
#endif
    }
}
