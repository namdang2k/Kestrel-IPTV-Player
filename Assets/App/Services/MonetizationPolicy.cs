using System;
using System.Collections.Generic;

namespace StbEmu.Services
{
    public enum MonetizationSegment { Casual, Engaged, Pro }

    /// <summary>
    /// Pure monetization rules (Docs/ProMonetizationDesign.vi.md §3).
    /// Timings are six seconds values in the order of <see cref="Names"/>.
    /// </summary>
    public static class MonetizationPolicy
    {
        public const int Version = 2;
        public const int Interstitial = 0, AppOpen = 1, FirstSessionDelay = 2, MinGap = 3, RewardedViewing = 4, Upsell = 5;
        public const int FieldCount = 6;
        public static readonly string[] Names =
        {
            "interstitialIntervalSeconds", "appOpenIntervalSeconds", "firstSessionInterstitialDelaySeconds",
            "minSecondsBetweenAds", "rewardedViewingSeconds", "upsellIntervalSeconds",
        };
        /// <summary>Floor of the reward break interval.</summary>
        public const double RewardedMinSeconds = 60;
        /// <summary>Every <see cref="RewardedStepWatchSeconds"/> of all-time watching takes <see cref="RewardedStepSeconds"/> off the reward interval.</summary>
        public const double RewardedStepWatchSeconds = 600, RewardedStepSeconds = 10;
        // Min is a safety floor against bad configs, not the base itself.
        public static readonly double[] Min = { 30, 30, 30, 10, RewardedMinSeconds, 90 };
        public static readonly double[] Max = { 1800, 3600, 3600, 600, 7200, 7200 };
        // Reward is 1: engagement shortens it through all-time watching (RewardedReduction) instead.
        public static readonly double[] EngagedMultiplier = { 1.5, 1.5, 1, 1.5, 1, 0.75 };
        // Editor export presets; tier 3 = the asset itself.
        public static readonly double[][] TierMultiplier =
        {
            new double[] { 1.5, 1.5, 1.5, 1.5, 1.5, 1 },
            new double[] { 1.3, 1.3, 1.3, 1.3, 1.3, 1 },
            new double[] { 1, 1, 1, 1, 1, 1 },
        };

        static bool Valid(double value) => !double.IsNaN(value) && !double.IsInfinity(value) && value >= 0 && value <= float.MaxValue;

        /// <summary>Seconds taken off the reward interval after <paramref name="allTimeWatchSeconds"/> of watching.</summary>
        public static double RewardedReduction(double allTimeWatchSeconds) =>
            allTimeWatchSeconds > 0 && !double.IsInfinity(allTimeWatchSeconds)
                ? Math.Floor(allTimeWatchSeconds / RewardedStepWatchSeconds) * RewardedStepSeconds : 0;

        /// <summary>Always from the raw config, never from a previous result. Reasons get "field:reason" entries.</summary>
        public static double[] Effective(double[] raw, double[] bundled, bool engaged, double allTimeWatchSeconds = 0, List<string> reasons = null)
        {
            var result = new double[FieldCount];
            for (int f = 0; f < FieldCount; f++)
            {
                double value = raw[f];
                if (!Valid(value))
                {
                    reasons?.Add(Names[f] + ":invalid_raw");
                    value = Valid(bundled[f]) ? bundled[f] : Min[f];
                }
                if (f == RewardedViewing && value == 0) continue; // 0 turns the reward prompt off
                if (value == 0) reasons?.Add(Names[f] + ":zero_raised_to_min");
                double scaled = value * (engaged ? EngagedMultiplier[f] : 1);
                result[f] = Math.Min(Max[f], Math.Max(Min[f], scaled));
                if (value != 0 && result[f] != scaled) reasons?.Add(Names[f] + ":clamped");
            }
            // Engagement: the (clamped) base shrinks with all-time watching, never below the floor.
            if (result[RewardedViewing] > 0)
                result[RewardedViewing] = Math.Max(RewardedMinSeconds, result[RewardedViewing] - RewardedReduction(allTimeWatchSeconds));
            return result;
        }

        /// <summary>Editor preset: base × tier multiplier, unclamped (runtime clamps). Null when the base is unusable.</summary>
        public static double[] Tier(double[] asset, int tier, List<string> errors)
        {
            for (int f = 0; f < FieldCount; f++)
                if (!Valid(asset[f])) errors.Add(Names[f] + " is negative, NaN or too large");
            if (asset[Upsell] < 1) errors.Add(Names[Upsell] + " must be at least 1");
            if (errors.Count > 0) return null;
            var result = new double[FieldCount];
            for (int f = 0; f < FieldCount; f++)
            {
                result[f] = asset[f] * TierMultiplier[tier - 1][f];
                if (!Valid(result[f])) { errors.Add(Names[f] + " overflows"); return null; }
            }
            return result;
        }
    }

    /// <summary>Persisted behavior for segmenting; times are Unix seconds (UTC), 0 = never.</summary>
    [Serializable]
    public sealed class UserBehaviorState
    {
        const long Day = 86400;
        public const double EngagedWatchSeconds = 1800;
        public int version = 1;
        public long[] bucketDay = new long[7]; // UTC day number held by each rolling bucket (day % 7)
        public float[] bucketWatch = new float[7];
        public long lastNudgeAt, lastNudgeDismissAt, lastCheckoutCancelAt;
        public float watchSinceNudge;
        // double: per-frame increments would round away once a float total gets large.
        public double totalWatch; // all-time playback seconds, never rolls off

        /// <summary>Clock moved back: pull future stamps to now so no window outlives its length.</summary>
        public void Normalize(long now)
        {
            if (bucketDay == null || bucketDay.Length != 7) bucketDay = new long[7];
            if (bucketWatch == null || bucketWatch.Length != 7) bucketWatch = new float[7];
            long today = now / Day;
            for (int i = 0; i < 7; i++)
                if (bucketDay[i] > today || float.IsNaN(bucketWatch[i]) || bucketWatch[i] < 0) { bucketDay[i] = 0; bucketWatch[i] = 0; }
            lastNudgeAt = Math.Min(lastNudgeAt, now);
            lastNudgeDismissAt = Math.Min(lastNudgeDismissAt, now);
            lastCheckoutCancelAt = Math.Min(lastCheckoutCancelAt, now);
            if (float.IsNaN(watchSinceNudge) || watchSinceNudge < 0) watchSinceNudge = 0;
            if (double.IsNaN(totalWatch) || double.IsInfinity(totalWatch) || totalWatch < 0) totalWatch = 0;
            // State saved before totalWatch existed: start from what the buckets still hold (both grow together).
            if (totalWatch == 0) for (int i = 0; i < 7; i++) totalWatch += bucketWatch[i];
        }

        public void AddWatch(double seconds, long now)
        {
            if (!(seconds > 0)) return;
            long today = now / Day;
            int b = (int)(today % 7);
            if (bucketDay[b] != today) { bucketDay[b] = today; bucketWatch[b] = 0; }
            bucketWatch[b] += (float)seconds;
            watchSinceNudge += (float)seconds;
            totalWatch += seconds;
        }

        public bool IsEngaged(long now)
        {
            long today = now / Day;
            double total = 0;
            int activeDays = 0;
            for (int i = 0; i < 7; i++)
            {
                if (today - bucketDay[i] > 6 || bucketWatch[i] <= 0) continue;
                total += bucketWatch[i];
                activeDays++;
            }
            return total >= EngagedWatchSeconds && activeDays >= 2;
        }

        static bool Within(long at, long now, long seconds) => at > 0 && now - at < seconds;

        public void RecordCheckoutCancelled(long now) => lastCheckoutCancelAt = now;
        public void RecordNudgeDismissed(long now) => lastNudgeDismissAt = now;
        public void RecordNudge(long now) { lastNudgeAt = now; watchSinceNudge = 0; }

        /// <summary>Engaged only; 1 per UTC day, 48 h after a dismiss, 72 h after a cancelled checkout, after enough watching.</summary>
        public bool CanNudge(long now, double upsellInterval) => IsEngaged(now) &&
            (lastNudgeAt == 0 || lastNudgeAt / Day != now / Day) && !Within(lastNudgeDismissAt, now, 2 * Day) &&
            !Within(lastCheckoutCancelAt, now, 3 * Day) && watchSinceNudge >= upsellInterval;
    }
}
