using StbEmu.Services;
using UnityEditor;
using UnityEditor.Build;
using UnityEditor.Build.Reporting;
using UnityEngine;

/// <summary>App IDs are native build settings, not runtime MobileAds.Initialize arguments.</summary>
public sealed class AdMobSettingsBuildSync : IPreprocessBuildWithReport
{
    const string ConfigPath = "Assets/Resources/Services/AdMobSettings.asset";
    const string SdkPath = "Assets/GoogleMobileAds/Resources/GoogleMobileAdsSettings.asset";
    public int callbackOrder => -10000;

    public void OnPreprocessBuild(BuildReport report)
    {
        if (report.summary.platform != BuildTarget.Android && report.summary.platform != BuildTarget.iOS) return;
        Sync(report.summary.platform);
    }

    [MenuItem("StbEmu/Services/Sync AdMob App IDs")]
    public static void SyncMenu() => Sync(EditorUserBuildSettings.activeBuildTarget);

    static void Sync(BuildTarget platform)
    {
        var config = AssetDatabase.LoadAssetAtPath<AdMobSettings>(ConfigPath);
        if (config == null) throw new BuildFailedException("Missing " + ConfigPath);
        bool ios = platform == BuildTarget.iOS;
        if (!AdMobSettings.IsValidAppId(config.GetAppId(ios)))
            throw new BuildFailedException("Set a valid " + (ios ? "iOS" : "Android") + " App ID in " + ConfigPath);
        var sdk = AssetDatabase.LoadAssetAtPath<ScriptableObject>(SdkPath);
        if (sdk == null) throw new BuildFailedException("Missing Google Mobile Ads SDK settings: " + SdkPath);
        var serialized = new SerializedObject(sdk);
        var androidId = serialized.FindProperty("adMobAndroidAppId");
        var iosId = serialized.FindProperty("adMobIOSAppId");
        if (androidId == null || iosId == null) throw new BuildFailedException("Google Mobile Ads settings schema changed.");
        androidId.stringValue = config.GetAppId(false) ?? "";
        iosId.stringValue = config.GetAppId(true) ?? "";
        serialized.ApplyModifiedPropertiesWithoutUndo();
        AssetDatabase.SaveAssetIfDirty(sdk);
    }
}
