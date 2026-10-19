using System;

namespace StbEmu.Services
{
    /// <summary>App-session playback accounting; changing channels does not reset viewing time.</summary>
    public sealed class StreamingAdPolicy
    {
        public bool HasPlayed { get; private set; }
        public string PlayingChannel { get; private set; } = "";
        public double WatchedSeconds { get; private set; }
        public bool RewardRequired { get; private set; }

        public void RecordPlaying(string channel)
        {
            HasPlayed = true;
            PlayingChannel = channel ?? "";
        }
        public bool ShouldShowChannelInterstitial(string channel) => HasPlayed &&
            !string.IsNullOrEmpty(channel) && channel != PlayingChannel;

        public bool Tick(double seconds, bool activelyWatching, double rewardInterval)
        {
            if (!activelyWatching || RewardRequired || rewardInterval <= 0) return false;
            WatchedSeconds += Math.Max(0, seconds);
            if (WatchedSeconds < rewardInterval) return false;
            RewardRequired = true;
            return true;
        }
        public void GrantReward() { WatchedSeconds = 0; RewardRequired = false; }
        public void Disconnect()
        {
            PlayingChannel = "";
            HasPlayed = false; // the first channel picked after a reconnect shows no interstitial
        }
    }
}
