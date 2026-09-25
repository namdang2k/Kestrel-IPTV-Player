using System.Linq;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEngine;

/// <summary>
/// Development APK for on-device testing: WebView is inspectable (chrome://inspect) and Debug.Log reaches logcat.
/// Menu: StbEmu → Build And Run Android (Dev). Batch: -executeMethod AndroidDevBuild.Build
/// Then: powershell -File Tools/logcat.ps1
/// </summary>
public static class AndroidDevBuild
{
    const string Output = "Builds/StbEmu-dev.apk";

    [MenuItem("StbEmu/Build And Run Android (Dev)")]
    public static void BuildAndRun() => Run(BuildOptions.Development | BuildOptions.AutoRunPlayer);

    public static void Build()
    {
        var ok = Run(BuildOptions.Development);
        if (Application.isBatchMode) EditorApplication.Exit(ok ? 0 : 1);
    }

    static bool Run(BuildOptions options)
    {
        var scenes = EditorBuildSettings.scenes.Where(s => s.enabled).Select(s => s.path).ToArray();
        var report = BuildPipeline.BuildPlayer(scenes, Output, BuildTarget.Android, options);
        var s = report.summary;
        Debug.Log($"[AndroidDevBuild] {s.result}: {s.outputPath} ({s.totalSize / 1048576f:0.0} MB, {s.totalErrors} errors) " +
                  $"package={PlayerSettings.GetApplicationIdentifier(UnityEditor.Build.NamedBuildTarget.Android)}");
        return s.result == BuildResult.Succeeded;
    }
}
