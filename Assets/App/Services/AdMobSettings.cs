using System;
using System.Collections.Generic;
using System.Text.RegularExpressions;
using UnityEngine;

namespace StbEmu.Services
{
    public enum AdFormat { Banner, Interstitial, Rewarded, AppOpen, RewardedInterstitial, Native }

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
            [Tooltip("Ad break fallback when no rewarded ad is ready. Tried in order when an ad request fails.")]
            public List<string> rewardedInterstitialIds = new List<string>();
            [Tooltip("Tried in order when an ad request fails.")]
            public List<string> appOpenIds = new List<string>();
            [Tooltip("Tried in order when an ad request fails.")]
            public List<string> nativeIds = new List<string>();
        }

        [SerializeField] private string tier = "Default";
        [SerializeField] bool useTestAds = true;
        [SerializeField] PlatformIds android = new PlatformIds();
        [Min(1)] [SerializeField] private int sessionShowNative = 2;
        [Header("Ad timing (seconds)")]
        [Min(0)] [SerializeField] float interstitialIntervalSeconds = 60;
        [Min(0)] [SerializeField] float appOpenIntervalSeconds = 30;
        [Tooltip("From app startup, only during the first launch after install/data reset.")]
        [Min(0)] [SerializeField] float firstSessionInterstitialDelaySeconds = 60;
        [Tooltip("After any full-screen ad closes (banner excluded): no interstitial, app open or reward prompt for this long.")]
        [Min(0)] [SerializeField] float minSecondsBetweenAds = 30;
        [Tooltip("Base active streaming seconds per rewarded viewing period; 0 disables the prompt. Runtime: clamped to [60, 7200], " +
            "then −10 s per 10 min of the user's all-time watching, never below 60 s.")]
        [Min(0)] [SerializeField] float rewardedViewingSeconds = 600;
        [Tooltip("App seconds between Pro upsell popups.")]
        [Min(1)] [SerializeField] float upsellIntervalSeconds = 90;
        public bool UseTestAds => useTestAds;
        public string Tier => tier;
        public int SessionShowNative => Math.Max(1, sessionShowNative);
        public string GetAppId() => android.appId?.Trim();

        public List<string> GetAdUnitIds(AdFormat format, bool forceTest = false)
        {
            if (useTestAds || forceTest)
            {
                switch (format)
                {
                    case AdFormat.Banner: return One("ca-app-pub-3940256099942544/6300978111");
                    case AdFormat.Interstitial: return One("ca-app-pub-3940256099942544/1033173712");
                    case AdFormat.Rewarded: return One("ca-app-pub-3940256099942544/5224354917");
                    case AdFormat.AppOpen: return One("ca-app-pub-3940256099942544/9257395921");
                    case AdFormat.RewardedInterstitial: return One("ca-app-pub-3940256099942544/5354046379");
                    case AdFormat.Native: return One("ca-app-pub-3940256099942544/2247696110");
                    default: throw new ArgumentOutOfRangeException(nameof(format));
                }
            }
            switch (format)
            {
                case AdFormat.Banner: return Clean(android.bannerIds);
                case AdFormat.Interstitial: return Clean(android.interstitialIds);
                case AdFormat.Rewarded: return Clean(android.rewardedIds);
                case AdFormat.AppOpen: return Clean(android.appOpenIds);
                case AdFormat.RewardedInterstitial: return Clean(android.rewardedInterstitialIds);
                case AdFormat.Native: return Clean(android.nativeIds);
                default: throw new ArgumentOutOfRangeException(nameof(format));
            }
        }

        static List<string> One(string id) => new List<string> { id };
        static List<string> Clean(List<string> values)
        {
            var result = new List<string>();
            if (values != null) foreach (var value in values)
                if (!string.IsNullOrWhiteSpace(value)) result.Add(value.Trim());
            return result;
        }

#if UNITY_EDITOR
        [ContextMenu("Copy Remote Config JSON")]
        void CopyRemoteConfigJson()
        {
            var json = JsonUtility.ToJson(AdMobRemoteConfig.From(this), true);
            GUIUtility.systemCopyBuffer = json;
            Debug.Log("[AdMobSettings] " + FirebaseKeyParams.AdMob_Config + " JSON copied to clipboard:\n" + json, this);
        }

        [UnityEditor.MenuItem("StbEmu/Export AdMob Remote Config JSON")]
        static void ExportRemoteConfigJson()
        {
            var settings = Resources.Load<AdMobSettings>(ResourcePath);
            if (settings == null) { Debug.LogError("[AdMobSettings] Not found at Resources/" + ResourcePath); return; }
            var path = UnityEditor.EditorUtility.SaveFilePanel("Export AdMob Remote Config", "", FirebaseKeyParams.AdMob_Config, "json");
            if (string.IsNullOrEmpty(path)) return;
            System.IO.File.WriteAllText(path, JsonUtility.ToJson(AdMobRemoteConfig.From(settings), true));
            UnityEditor.EditorUtility.RevealInFinder(path);
            Debug.Log("[AdMobSettings] Exported " + path);
        }
#endif

        public static bool IsValidAppId(string id) => Regex.IsMatch(id ?? "", @"^ca-app-pub-\d{16}~\d{10}$");
        public static bool IsValidAdUnitId(string id) => Regex.IsMatch(id ?? "", @"^ca-app-pub-\d{16}/\d{10}$");
    }
}
