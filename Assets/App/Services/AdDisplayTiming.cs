using System;

namespace StbEmu.Services
{
    /// <summary>Monotonic clock policy. Only actual displays consume the cooldown; any full-screen ad starts it.</summary>
    public sealed class AdDisplayTiming
    {
        readonly bool firstSession;
        bool interstitialShown;
        double? lastFullScreen;
        public AdDisplayTiming(bool firstSession) { this.firstSession = firstSession; }

        public string GetBlockReason(AdFormat format, double secondsSinceStartup,
            double interstitialInterval, double appOpenInterval, double firstSessionDelay)
        {
            if (format == AdFormat.Interstitial)
            {
                if (firstSession && !interstitialShown && secondsSinceStartup < Math.Max(0, firstSessionDelay))
                    return "first_session_delay";
                if (lastFullScreen.HasValue && secondsSinceStartup - lastFullScreen.Value < Math.Max(0, interstitialInterval))
                    return "interstitial_cooldown";
            }
            if (format == AdFormat.AppOpen && lastFullScreen.HasValue &&
                secondsSinceStartup - lastFullScreen.Value < Math.Max(0, appOpenInterval))
                return "app_open_cooldown";
            return null;
        }

        public void RecordShown(AdFormat format, double secondsSinceStartup)
        {
            if (format == AdFormat.Banner) return;
            if (format == AdFormat.Interstitial) interstitialShown = true;
            lastFullScreen = secondsSinceStartup;
        }
    }
}
