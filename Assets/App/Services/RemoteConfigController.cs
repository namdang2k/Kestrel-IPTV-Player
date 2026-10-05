using System;
using Firebase.RemoteConfig;
using UnityEngine;

namespace StbEmu.Services
{
    public static class FirebaseKeyParams
    {
        public const string AB_Version = "AB_Version";
        public const string AdMob_Config = "AdMob_Config";
        public const string NoAds = "NoAds_Config";
    }

    // Field names match AdMobSettings' serialized fields, so JsonUtility copies both ways
    // and the remote AdMob_Config string uses the same keys.
    [Serializable]
    public sealed class AdMobRemoteConfig
    {
        public bool useTestAds = true;
        public AdMobSettings.PlatformIds android = new AdMobSettings.PlatformIds();
        public AdMobSettings.PlatformIds ios = new AdMobSettings.PlatformIds();
        public float interstitialIntervalSeconds = 60;
        public float appOpenIntervalSeconds = 30;
        public float firstSessionInterstitialDelaySeconds = 60;
        public float minSecondsBetweenAds = 30;
        public float rewardedViewingSeconds = 600;
        public float rewardedSkippedViewingSeconds = 180;

        public static AdMobRemoteConfig From(AdMobSettings settings) => JsonUtility.FromJson<AdMobRemoteConfig>(JsonUtility.ToJson(settings));
        public void ApplyTo(AdMobSettings settings) => JsonUtility.FromJsonOverwrite(JsonUtility.ToJson(this), settings);
    }

    // Values from this launch's fetch. Not persisted: if the fetch fails, the defaults (AdMobSettings asset) stay.
    [Serializable]
    public sealed class FirebaseRemoteData
    {
        public string abVersion = "Default";
        public AdMobRemoteConfig adMobConfig;
        public bool isNoAds = false;
    }

    [DisallowMultipleComponent]
    public sealed class RemoteConfigController : MonoBehaviour
    {
        public static RemoteConfigController Instance { get; private set; }
        [SerializeField] AdMobSettings settings;
        FirebaseRemoteData data;
        public string ABVersion => string.IsNullOrEmpty(data?.abVersion) ? "Default" : data.abVersion;
        
        public bool NoAds => data != null && data.isNoAds;
        public AdMobRemoteConfig AdMobConfig => data?.adMobConfig;
        public bool IsReady { get; private set; }
        public event Action Loaded;

        void Awake()
        {
            if (Instance != null && Instance != this) { Destroy(this); return; }
            Instance = this;
            if (settings == null) settings = Resources.Load<AdMobSettings>(AdMobSettings.ResourcePath);
            if (settings == null) { Debug.LogError("[RemoteConfig] AdMobSettings not found", this); return; }
            data = new FirebaseRemoteData { adMobConfig = AdMobRemoteConfig.From(settings) };
        }

        async void Start()
        {
            if (Instance != this || data == null) return;
            var firebase = FirebaseController.Instance;
            if (firebase == null || !await firebase.InitializeAsync()) return;
            try
            {
                var remoteConfig = FirebaseRemoteConfig.DefaultInstance;
                // No fetch cache (default is 12 h): every launch gets the current console values.
                await remoteConfig.SetConfigSettingsAsync(new ConfigSettings { MinimumFetchIntervalInMilliseconds = 0 });
                var activated = await remoteConfig.FetchAndActivateAsync();
                if (this == null) return;
                var info = remoteConfig.Info;
                Debug.Log($"[RemoteConfig] Fetch status={info.LastFetchStatus} reason={info.LastFetchFailureReason} " +
                    $"fetchTime={info.FetchTime:u} activated={activated} keys=[{string.Join(",", remoteConfig.AllValues.Keys)}]");
                // A failed fetch would still return values activated on an earlier launch; keep the defaults instead.
                if (info.LastFetchStatus != LastFetchStatus.Success) return;
                LoadConfigs(remoteConfig);
            }
            catch (Exception error)
            {
                Debug.LogWarning("[RemoteConfig] Fetch failed, using default config: " + error.Message);
            }
        }

        void LoadConfigs(FirebaseRemoteConfig remoteConfig)
        {
            var abVersion = remoteConfig.GetValue(FirebaseKeyParams.AB_Version).StringValue;
            var noAds = remoteConfig.GetValue(FirebaseKeyParams.NoAds).BooleanValue;
            data.isNoAds = noAds;
            data.abVersion = string.IsNullOrEmpty(abVersion) ? "Default" : abVersion;
            var adMobJson = remoteConfig.GetValue(FirebaseKeyParams.AdMob_Config).StringValue;
            // Empty remote value (in-app default) or keys missing from the JSON keep the AdMobSettings values.
            if (!string.IsNullOrWhiteSpace(adMobJson)) JsonUtility.FromJsonOverwrite(adMobJson, data.adMobConfig);
            Apply();
            GoogleAdsMobileController.Instance?.OnRemoteConfigLoaded();
            IsReady = true;
            Debug.LogError("[RemoteConfig] Load new firebase remote AB_Version: " + data.abVersion);
            Debug.LogError("[RemoteConfig] No ads config: " + data.isNoAds);
            Debug.LogError("[RemoteConfig] AdMob config: " + JsonUtility.ToJson(data.adMobConfig));
            Loaded?.Invoke();
        }

        void Apply()
        {
#if UNITY_EDITOR
            // Overwriting a Resources asset in the Editor would persist into the .asset file.
            Debug.Log("[RemoteConfig] AdMob config not applied in Editor");
#else
            data.adMobConfig.ApplyTo(settings);
#endif
        }

        void OnDestroy()
        {
            if (Instance == this) Instance = null;
        }
    }
}
