using System.IO;
using System.Text.RegularExpressions;
using UnityEditor.Android;

/// <summary>
/// EDM writes its local Maven repo (GeneratedLocalRepo) into settings.gradle as a raw file: URL; the space in
/// "Unity Project" makes Gradle fail with "Cannot convert URI ... to a file". EDM rewrites that block on every
/// resolve, so escape it in the exported project instead of in settingsTemplate.gradle.
/// </summary>
public sealed class GradleRepoPathFix : IPostGenerateGradleAndroidProject
{
    public int callbackOrder => 1000;

    public void OnPostGenerateGradleAndroidProject(string unityLibraryPath)
    {
        var settings = Path.Combine(Path.GetDirectoryName(unityLibraryPath), "settings.gradle");
        if (!File.Exists(settings)) return;
        var text = File.ReadAllText(settings);
        var escaped = Regex.Replace(text, @"file:///[^$""\r\n]*", m => m.Value.Replace(" ", "%20"));
        if (escaped != text) File.WriteAllText(settings, escaped);
    }
}
