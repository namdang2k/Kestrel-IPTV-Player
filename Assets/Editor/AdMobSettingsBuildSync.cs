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
        if (report.summary.platform != BuildTarget.Android) return;
        Sync();
    }

    [MenuItem("StbEmu/Services/Sync AdMob App IDs")]
    public static void SyncMenu() => Sync();

    static void Sync()
    {
        var config = AssetDatabase.LoadAssetAtPath<AdMobSettings>(ConfigPath);
        if (config == null) throw new BuildFailedException("Missing " + ConfigPath);
        if (!AdMobSettings.IsValidAppId(config.GetAppId()))
            throw new BuildFailedException("Set a valid Android App ID in " + ConfigPath);
        var sdk = AssetDatabase.LoadAssetAtPath<ScriptableObject>(SdkPath);
        if (sdk == null) throw new BuildFailedException("Missing Google Mobile Ads SDK settings: " + SdkPath);
        var serialized = new SerializedObject(sdk);
        var androidId = serialized.FindProperty("adMobAndroidAppId");
        if (androidId == null) throw new BuildFailedException("Google Mobile Ads settings schema changed.");
        androidId.stringValue = config.GetAppId() ?? "";
        serialized.ApplyModifiedPropertiesWithoutUndo();
        AssetDatabase.SaveAssetIfDirty(sdk);
    }
}
