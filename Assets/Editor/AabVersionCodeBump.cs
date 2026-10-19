using UnityEditor;
using UnityEditor.Build;
using UnityEditor.Build.Reporting;
using UnityEngine;

/// <summary>Every AAB build bumps the Android bundle version code stored in AppVersionSettings (Play rejects a re-used one). APK builds leave it alone.</summary>
public sealed class AabVersionCodeBump : IPreprocessBuildWithReport
{
    public int callbackOrder => -9000;

    public void OnPreprocessBuild(BuildReport report)
    {
        if (report.summary.platform != BuildTarget.Android || !EditorUserBuildSettings.buildAppBundle) return;
        var config = AppVersionBuildSync.Load();
        config.bundleVersionCode++;
        EditorUtility.SetDirty(config);
        AppVersionBuildSync.ApplyBundleVersionCode(config);
        AssetDatabase.SaveAssets();
        Debug.Log("AAB build: Android bundle version code -> " + config.bundleVersionCode);
    }
}
