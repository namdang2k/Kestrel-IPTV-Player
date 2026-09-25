using System;

namespace StbEmu.Portal
{
    public enum PlayerState
    {
        Idle,
        Buffering,
        Playing,
        Paused,
        Ended,
    }

    /// <summary>Video player under the portal. Events are raised on the Unity main thread.</summary>
    public interface IPlayer
    {
        event Action<PlayerState> StateChanged;

        /// <summary>Player error code, e.g. "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED".</summary>
        event Action<string> Error;

        /// <param name="headersJson">Flat JSON object of request headers; null = none.</param>
        void Play(string url, string headersJson);

        void SetPaused(bool paused);

        /// <param name="volume">0..1</param>
        void SetVolume(float volume);

        void Stop();
    }
}
