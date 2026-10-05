using System;
using Firebase.RemoteConfig;
using UnityEngine;

namespace StbEmu.Services
{
    public static class FirebaseKeyParams
    {
        public const string AB_Version = "AB_Version";
        public const string AdMob_Config = "AdMob_Config";
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

    // Last fetched values, saved so the next launch still has them if the fetch fails.
    [Serializable]
    public sealed class FirebaseRemoteData
    {
        public string abVersion = "Default";
        public AdMobRemoteConfig adMobConfig;
    }

    [DisallowMultipleComponent]
    public sealed class RemoteConfigController : MonoBehaviour
    {
        public static RemoteConfigController Instance { get; private set; }
        const string DataKey = "StbEmu.FirebaseRemoteConfig";
        [SerializeField] AdMobSettings settings;
        FirebaseRemoteData data;
        string localAdMobJson;
        public string ABVersion => string.IsNullOrEmpty(data?.abVersion) ? "Default" : data.abVersion;
        public AdMobRemoteConfig AdMobConfig => data?.adMobConfig;
        public bool IsReady { get; private set; }
        public event Action Loaded;

        void Awake()
        {
            if (Instance != null && Instance != this) { Destroy(this); return; }
            Instance = this;
            if (settings == null) settings = Resources.Load<AdMobSettings>(AdMobSettings.ResourcePath);
            if (settings == null) { Debug.LogError("[RemoteConfig] AdMobSettings not found", this); return; }
            // Captured before the cache is applied: each fetch starts from these asset values.
            localAdMobJson = JsonUtility.ToJson(AdMobRemoteConfig.From(settings));
            data = LoadData();
            Apply();
            Debug.Log("[RemoteConfig] AB_Version in user cache: " + ABVersion);
        }

        async void Start()
        {
            if (Instance != this || data == null) return;
            var firebase = FirebaseController.Instance;
            if (firebase == null || !await firebase.InitializeAsync()) return;
            try
            {
                var remoteConfig = FirebaseRemoteConfig.DefaultInstance;
                await remoteConfig.FetchAndActivateAsync();
                if (this == null) return;
                LoadConfigs(remoteConfig);
            }
            catch (Exception error)
            {
                Debug.LogWarning("[RemoteConfig] Fetch failed, using cached config: " + error.Message);
            }
        }

        void LoadConfigs(FirebaseRemoteConfig remoteConfig)
        {
            var abVersion = remoteConfig.GetValue(FirebaseKeyParams.AB_Version).StringValue;
            data.abVersion = string.IsNullOrEmpty(abVersion) ? "Default" : abVersion;
            var adMobJson = remoteConfig.GetValue(FirebaseKeyParams.AdMob_Config).StringValue;
            // Empty remote value (in-app default) or keys missing from the JSON fall back to AdMobSettings, not the old cache.
            data.adMobConfig = JsonUtility.FromJson<AdMobRemoteConfig>(localAdMobJson);
            if (!string.IsNullOrWhiteSpace(adMobJson)) JsonUtility.FromJsonOverwrite(adMobJson, data.adMobConfig);
            PlayerPrefs.SetString(DataKey, JsonUtility.ToJson(data));
            PlayerPrefs.Save();
            Apply();
            GoogleAdsMobileController.Instance?.RefreshAdUnitIds();
            IsReady = true;
            Debug.LogError("[RemoteConfig] Load new firebase remote AB_Version: " + data.abVersion);
            Debug.LogError("[RemoteConfig] AdMob config: " + JsonUtility.ToJson(data.adMobConfig));
            Loaded?.Invoke();
        }

        FirebaseRemoteData LoadData()
        {
            var result = new FirebaseRemoteData { adMobConfig = JsonUtility.FromJson<AdMobRemoteConfig>(localAdMobJson) };
            var saved = PlayerPrefs.GetString(DataKey, "");
            if (string.IsNullOrEmpty(saved)) return result;
            try { JsonUtility.FromJsonOverwrite(saved, result); }
            catch (Exception error) { Debug.LogWarning("[RemoteConfig] Cached data invalid: " + error.Message); }
            return result;
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
