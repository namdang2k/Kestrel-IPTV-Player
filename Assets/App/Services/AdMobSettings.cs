using System;
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
            public string bannerId;
            public string interstitialId;
            public string rewardedId;
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
        public bool UseTestAds => useTestAds;

        public string GetAppId(bool forIos) => (forIos ? ios : android).appId?.Trim();

        public string GetAdUnitId(AdFormat format, bool forIos, bool forceTest = false)
        {
            if (useTestAds || forceTest)
            {
                switch (format)
                {
                    case AdFormat.Banner: return forIos ? "ca-app-pub-3940256099942544/2934735716" : "ca-app-pub-3940256099942544/6300978111";
                    case AdFormat.Interstitial: return forIos ? "ca-app-pub-3940256099942544/4411468910" : "ca-app-pub-3940256099942544/1033173712";
                    case AdFormat.Rewarded: return forIos ? "ca-app-pub-3940256099942544/1712485313" : "ca-app-pub-3940256099942544/5224354917";
                    case AdFormat.AppOpen: return forIos ? "ca-app-pub-3940256099942544/5575463023" : "ca-app-pub-3940256099942544/9257395921";
                    default: throw new ArgumentOutOfRangeException(nameof(format));
                }
            }
            var ids = forIos ? ios : android;
            switch (format)
            {
                case AdFormat.Banner: return ids.bannerId?.Trim();
                case AdFormat.Interstitial: return ids.interstitialId?.Trim();
                case AdFormat.Rewarded: return ids.rewardedId?.Trim();
                case AdFormat.AppOpen: return ids.appOpenId?.Trim();
                default: throw new ArgumentOutOfRangeException(nameof(format));
            }
        }

        public static bool IsValidAppId(string id) => Regex.IsMatch(id ?? "", @"^ca-app-pub-\d{16}~\d{10}$");
        public static bool IsValidAdUnitId(string id) => Regex.IsMatch(id ?? "", @"^ca-app-pub-\d{16}/\d{10}$");
    }
}
