using System;

namespace StbEmu.Services
{
    /// <summary>Monotonic clock policy. Only actual displays consume the cooldown; any full-screen ad starts it.</summary>
    public sealed class AdDisplayTiming
    {
        readonly bool firstSession;
        bool interstitialShown;
        double? lastFullScreen, lastClosed;
        public AdDisplayTiming(bool firstSession) { this.firstSession = firstSession; }

        public string GetBlockReason(AdFormat format, double secondsSinceStartup,
            double interstitialInterval, double appOpenInterval, double firstSessionDelay, double minGap = 0)
        {
            if (format != AdFormat.Banner && format != AdFormat.Native && lastClosed.HasValue && secondsSinceStartup - lastClosed.Value < Math.Max(0, minGap))
                return "ad_gap";
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
            if (format == AdFormat.Banner || format == AdFormat.Native) return;
            if (format == AdFormat.Interstitial) interstitialShown = true;
            lastFullScreen = secondsSinceStartup;
        }

        /// <summary>Any full-screen ad closed: no other full-screen ad (or reward prompt) within the gap.</summary>
        public void RecordClosed(double secondsSinceStartup) => lastClosed = secondsSinceStartup;
    }
}
