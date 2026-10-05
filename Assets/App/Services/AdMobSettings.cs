using System;
using System.Collections.Generic;
using System.Text.RegularExpressions;
using UnityEngine;

namespace StbEmu.Services
{
    public enum AdFormat { Banner, Interstitial, Rewarded, AppOpen }

    [CreateAssetMenu(menuName = "StbEmu/AdMob Settings", fileName = "AdMobSettings")]
    public sealed class AdMobSettings : ScriptableObject
    {
        public const string ResourcePath = "Services/AdMobSettings";
        [Serializable]
        public sealed class PlatformIds
        {
            public string appId;
            [Tooltip("Tried in order when an ad request fails.")]
            public List<string> bannerIds = new List<string>();
            [Tooltip("Tried in order when an ad request fails.")]
            public List<string> interstitialIds = new List<string>();
            [Tooltip("Tried in order when an ad request fails.")]
            public List<string> rewardedIds = new List<string>();
            [Tooltip("Tried in order when an ad request fails.")]
            public List<string> appOpenIds = new List<string>();
            // Legacy single-value fields are kept so existing AdMobSettings assets migrate safely.
            [HideInInspector]
            public string bannerId;
            [HideInInspector]
            public string interstitialId;
            [HideInInspector]
            public string rewardedId;
            [HideInInspector]
            public string appOpenId;
        }

        [SerializeField] bool useTestAds = true;
        [SerializeField] PlatformIds android = new PlatformIds();
        [SerializeField] PlatformIds ios = new PlatformIds();
        [Header("Ad timing (seconds)")]
        [Min(0)] [SerializeField] float interstitialIntervalSeconds = 60;
        [Min(0)] [SerializeField] float appOpenIntervalSeconds = 30;
        [Tooltip("From app startup, only during the first launch after install/data reset.")]
        [Min(0)] [SerializeField] float firstSessionInterstitialDelaySeconds = 60;
        public float InterstitialIntervalSeconds => Mathf.Max(0, interstitialIntervalSeconds);
        public float AppOpenIntervalSeconds => Mathf.Max(0, appOpenIntervalSeconds);
        public float FirstSessionInterstitialDelaySeconds => Mathf.Max(0, firstSessionInterstitialDelaySeconds);
        [Tooltip("Active streaming seconds per rewarded viewing period; 0 disables the prompt.")]
        [Min(0)] [SerializeField] float rewardedViewingSeconds = 600;
        public float RewardedViewingSeconds => Mathf.Max(0, rewardedViewingSeconds);
        [Tooltip("Active streaming seconds until the next prompt when the rewarded ad is closed without earning the reward.")]
        [Min(0)] [SerializeField] float rewardedSkippedViewingSeconds = 180;
        public float RewardedSkippedViewingSeconds => Mathf.Max(0, rewardedSkippedViewingSeconds);
        public bool UseTestAds => useTestAds;

        public string GetAppId(bool forIos) => (forIos ? ios : android).appId?.Trim();

        public string GetAdUnitId(AdFormat format, bool forIos, bool forceTest = false)
        {
            var ids = GetAdUnitIds(format, forIos, forceTest);
            return ids.Count == 0 ? null : ids[0];
        }

        public List<string> GetAdUnitIds(AdFormat format, bool forIos, bool forceTest = false)
        {
            if (useTestAds || forceTest)
            {
                switch (format)
                {
                    case AdFormat.Banner: return One(forIos ? "ca-app-pub-3940256099942544/2934735716" : "ca-app-pub-3940256099942544/6300978111");
                    case AdFormat.Interstitial: return One(forIos ? "ca-app-pub-3940256099942544/4411468910" : "ca-app-pub-3940256099942544/1033173712");
                    case AdFormat.Rewarded: return One(forIos ? "ca-app-pub-3940256099942544/1712485313" : "ca-app-pub-3940256099942544/5224354917");
                    case AdFormat.AppOpen: return One(forIos ? "ca-app-pub-3940256099942544/5575463023" : "ca-app-pub-3940256099942544/9257395921");
                    default: throw new ArgumentOutOfRangeException(nameof(format));
                }
            }
            var ids = forIos ? ios : android;
            switch (format)
            {
                case AdFormat.Banner: return WithLegacy(ids.bannerIds, ids.bannerId);
                case AdFormat.Interstitial: return WithLegacy(ids.interstitialIds, ids.interstitialId);
                case AdFormat.Rewarded: return WithLegacy(ids.rewardedIds, ids.rewardedId);
                case AdFormat.AppOpen: return WithLegacy(ids.appOpenIds, ids.appOpenId);
                default: throw new ArgumentOutOfRangeException(nameof(format));
            }
        }

        static List<string> One(string id) => new List<string> { id };
        static List<string> WithLegacy(List<string> values, string legacy)
        {
            var result = new List<string>();
            if (values != null) foreach (var value in values)
                if (!string.IsNullOrWhiteSpace(value)) result.Add(value.Trim());
            if (result.Count == 0 && !string.IsNullOrWhiteSpace(legacy)) result.Add(legacy.Trim());
            return result;
        }

        public static bool IsValidAppId(string id) => Regex.IsMatch(id ?? "", @"^ca-app-pub-\d{16}~\d{10}$");
        public static bool IsValidAdUnitId(string id) => Regex.IsMatch(id ?? "", @"^ca-app-pub-\d{16}/\d{10}$");
    }
}
