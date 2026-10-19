using UnityEngine;

namespace StbEmu.Services
{
    /// <summary>The app version for every build (dev, APK, AAB); AppVersionBuildSync copies it to PlayerSettings.bundleVersion; bundleVersionCode is bumped by AabVersionCodeBump on AAB builds and copied to PlayerSettings.Android.bundleVersionCode.</summary>
    [CreateAssetMenu(menuName = "StbEmu/App Version Settings", fileName = "AppVersionSettings")]
    public sealed class AppVersionSettings : ScriptableObject
    {
        public const string ResourcePath = "Services/AppVersionSettings";
        public string version = "1.1.2";
        public int bundleVersionCode = 1;
    }
}
