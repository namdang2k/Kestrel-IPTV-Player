using NUnit.Framework;
using StbEmu.Services;
using UnityEngine;

namespace StbEmu.Tests
{
    public sealed class AdMobRemoteConfigTests
    {
        [Test]
        public void RemoteJsonOverridesOnlyGivenFieldsAndAppliesToSettings()
        {
            var settings = ScriptableObject.CreateInstance<AdMobSettings>();
            var config = AdMobRemoteConfig.From(settings);
            config.android.appId = "ca-app-pub-0000000000000000~0000000000";

            JsonUtility.FromJsonOverwrite(
                "{\"useTestAds\":false,\"interstitialIntervalSeconds\":90,\"android\":{\"bannerIds\":[\"ca-app-pub-1111111111111111/1111111111\"]}}",
                config);
            config.ApplyTo(settings);

            Assert.IsFalse(settings.UseTestAds);
            Assert.AreEqual(90, settings.InterstitialIntervalSeconds);
            Assert.AreEqual(600, settings.RewardedViewingSeconds);
            Assert.AreEqual("ca-app-pub-0000000000000000~0000000000", settings.GetAppId(false));
            CollectionAssert.AreEqual(new[] { "ca-app-pub-1111111111111111/1111111111" },
                settings.GetAdUnitIds(AdFormat.Banner, false));
            Object.DestroyImmediate(settings);
        }
    }
}
