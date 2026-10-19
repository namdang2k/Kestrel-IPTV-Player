using StbEmu.Services;
using UnityEditor;
using UnityEditor.Build;
using UnityEditor.Build.Reporting;
using UnityEngine;

/// <summary>Every build (dev, APK, AAB) takes its version string and Android bundle version code from AppVersionSettings, not from Player Settings.</summary>
public sealed class AppVersionBuildSync : IPreprocessBuildWithReport
{
    public const string ConfigPath = "Assets/Resources/Services/AppVersionSettings.asset";
    public int callbackOrder => -9500;

    public void OnPreprocessBuild(BuildReport report) => Sync();

    [MenuItem("StbEmu/Services/Sync App Version")]
    public static void SyncMenu() => Sync();

    public static AppVersionSettings Load()
    {
        var config = AssetDatabase.LoadAssetAtPath<AppVersionSettings>(ConfigPath);
        if (config == null) throw new BuildFailedException("Missing " + ConfigPath);
        return config;
    }

    static void Sync()
    {
        var config = Load();
        var version = (config.version ?? "").Trim();
        if (version.Length == 0) throw new BuildFailedException("Set a version in " + ConfigPath);
        ApplyBundleVersionCode(config);
        if (PlayerSettings.bundleVersion == version) return;
        PlayerSettings.bundleVersion = version;
        AssetDatabase.SaveAssets();
        Debug.Log("App version -> " + version);
    }

    public static void ApplyBundleVersionCode(AppVersionSettings config)
    {
        if (PlayerSettings.Android.bundleVersionCode == config.bundleVersionCode) return;
        PlayerSettings.Android.bundleVersionCode = config.bundleVersionCode;
        Debug.Log("Android bundle version code -> " + config.bundleVersionCode);
    }
}
