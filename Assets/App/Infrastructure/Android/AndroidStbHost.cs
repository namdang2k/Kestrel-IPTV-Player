using System;
using System.Threading;
using UnityEngine;
using UnityEngine.Android;

namespace StbEmu.Android
{
    /// <summary>
    /// C# handle to the native com.stbemu.host.StbHost (WebView + ExoPlayer). One per app.
    /// Native callbacks arrive on the Android UI thread and are re-posted to the Unity main thread.
    /// </summary>
    public sealed class AndroidStbHost : IDisposable
    {
        /// <summary>(type, data) from native, e.g. ("portal.message", json), ("portal.error", "LOAD_FAILED:-2"), ("state", "3").</summary>
        public event Action<string, string> NativeEvent;

        readonly SynchronizationContext unityThread;
        readonly AndroidJavaObject host;

        /// <summary>Must be created on the Unity main thread.</summary>
        public AndroidStbHost()
        {
            unityThread = SynchronizationContext.Current ?? throw new InvalidOperationException("Create AndroidStbHost on the Unity main thread.");
            host = new AndroidJavaObject("com.stbemu.host.StbHost", AndroidApplication.currentActivity, new Listener(this));
        }

        public void OpenPortal(string url, string snapshotJson, string userAgent) => host.Call("openPortal", url, snapshotJson, userAgent ?? "");

        public void PublishToPortal(string json) => host.Call("publishToPortal", json);

        public void ClosePortal() => host.Call("closePortal");

        public void AbortPortalReconnect(string reason) => host.Call("abortPortalReconnect", reason);

        public void SetPortalVisible(bool visible) => host.Call("setPortalVisible", visible);

        /// <summary>Native ⟳ ⌂ 🎮 bar and virtual remote above the WebView; they emit "ui.refresh" / "ui.home".</summary>
        public void SetOverlayVisible(bool visible) => host.Call("setOverlayVisible", visible);

        public void Play(string url, string headersJson) => host.Call("play", url, headersJson);

        public void PlayFromPortal(string url, string headersJson) => host.Call("playFromPortal", url, headersJson);

        public void SetPaused(bool paused) => host.Call("setPaused", paused);

        public void SetVolume(float volume) => host.Call("setVolume", volume);

        /// <summary>Pause media and hide native views while Unity owns a modal/ad transition.</summary>
        public void SetPlaybackGate(bool blocked) => host.Call("setPlaybackGate", blocked);
        public void ContinueChannelSelection(string channelId) => host.Call("continueChannelSelection", channelId);

        public void Stop() => host.Call("stop");

        public void StopFromPortal() => host.Call("stopFromPortal");

        public void Dispose()
        {
            host.Call("release");
            host.Dispose();
        }

        sealed class Listener : AndroidJavaProxy
        {
            readonly AndroidStbHost owner;

            public Listener(AndroidStbHost owner) : base("com.stbemu.host.StbHost$Listener")
            {
                this.owner = owner;
            }

            // Called by Java on the Android UI thread; never touch Unity API here.
            public void onEvent(string type, string data) => owner.unityThread.Post(_ => owner.NativeEvent?.Invoke(type, data), null);
        }
    }
}
