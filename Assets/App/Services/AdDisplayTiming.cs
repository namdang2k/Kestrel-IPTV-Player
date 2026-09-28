using System;

namespace StbEmu.Services
{
    /// <summary>Monotonic clock policy. Only actual displays consume the cooldown.</summary>
    public sealed class AdDisplayTiming
    {
        readonly bool firstSession;
        double? lastInterstitial, lastAppOpen;
        public AdDisplayTiming(bool firstSession) { this.firstSession = firstSession; }

        public string GetBlockReason(AdFormat format, double secondsSinceStartup,
            double interstitialInterval, double appOpenInterval, double firstSessionDelay)
        {
            if (format == AdFormat.Interstitial)
            {
                if (firstSession && !lastInterstitial.HasValue && secondsSinceStartup < Math.Max(0, firstSessionDelay))
                    return "first_session_delay";
                if (lastInterstitial.HasValue && secondsSinceStartup - lastInterstitial.Value < Math.Max(0, interstitialInterval))
                    return "interstitial_cooldown";
            }
            if (format == AdFormat.AppOpen && lastAppOpen.HasValue &&
                secondsSinceStartup - lastAppOpen.Value < Math.Max(0, appOpenInterval))
                return "app_open_cooldown";
            return null;
        }

        public void RecordShown(AdFormat format, double secondsSinceStartup)
        {
            if (format == AdFormat.Interstitial) lastInterstitial = secondsSinceStartup;
            if (format == AdFormat.AppOpen) lastAppOpen = secondsSinceStartup;
        }
    }
}
