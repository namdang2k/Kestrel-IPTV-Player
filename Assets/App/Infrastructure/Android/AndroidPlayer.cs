using System;
using StbEmu.Portal;

namespace StbEmu.Android
{
    /// <summary>ExoPlayer inside the native StbHost. Maps native "state"/"isPlaying"/"error" events to <see cref="IPlayer"/>.</summary>
    public sealed class AndroidPlayer : IPlayer
    {
        public event Action<PlayerState> StateChanged;
        public event Action<string> Error;

        // ExoPlayer Player.STATE_*: IDLE=1, BUFFERING=2, READY=3, ENDED=4
        const string ExoBuffering = "2", ExoReady = "3", ExoEnded = "4";

        readonly AndroidStbHost host;
        string exoState = "1";
        bool isPlaying;
        PlayerState? last;

        public AndroidPlayer(AndroidStbHost host)
        {
            this.host = host;
            host.NativeEvent += OnNativeEvent;
        }

        public void Play(string url, string headersJson) => host.PlayFromPortal(url, headersJson);
        public void SetPaused(bool paused) => host.SetPaused(paused);
        public void SetVolume(float volume) => host.SetVolume(volume);
        public void Stop() => host.StopFromPortal();

        void OnNativeEvent(string type, string data)
        {
            switch (type)
            {
                case "state": exoState = data; break;
                case "isPlaying": isPlaying = data == "true"; break;
                case "error": Error?.Invoke(data); return;
                default: return;
            }

            var state = isPlaying ? PlayerState.Playing
                : exoState == ExoBuffering ? PlayerState.Buffering
                : exoState == ExoReady ? PlayerState.Paused
                : exoState == ExoEnded ? PlayerState.Ended
                : PlayerState.Idle;
            if (state == last) return;
            last = state;
            StateChanged?.Invoke(state);
        }
    }
}
