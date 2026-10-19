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
                "{\"useTestAds\":false,\"sessionShowNative\":3,\"interstitialIntervalSeconds\":90,\"android\":{\"bannerIds\":[\"ca-app-pub-1111111111111111/1111111111\"],\"nativeIds\":[\"ca-app-pub-2222222222222222/2222222222\"]}}",
                config);
            config.ApplyTo(settings);

            Assert.IsFalse(settings.UseTestAds);
            Assert.AreEqual(3, settings.SessionShowNative);
            var roundTrip = AdMobRemoteConfig.From(settings);
            Assert.AreEqual(90, roundTrip.interstitialIntervalSeconds);
            Assert.AreEqual(600, roundTrip.rewardedViewingSeconds);
            Assert.AreEqual(3, roundTrip.sessionShowNative);
            Assert.AreEqual("ca-app-pub-0000000000000000~0000000000", settings.GetAppId());
            CollectionAssert.AreEqual(new[] { "ca-app-pub-1111111111111111/1111111111" },
                settings.GetAdUnitIds(AdFormat.Banner));
            CollectionAssert.AreEqual(new[] { "ca-app-pub-2222222222222222/2222222222" },
                settings.GetAdUnitIds(AdFormat.Native));
            CollectionAssert.AreEqual(new[] { "ca-app-pub-3940256099942544/5354046379" },
                settings.GetAdUnitIds(AdFormat.RewardedInterstitial, forceTest: true));
            CollectionAssert.AreEqual(new[] { "ca-app-pub-3940256099942544/2247696110" },
                settings.GetAdUnitIds(AdFormat.Native, forceTest: true));
            Object.DestroyImmediate(settings);
        }
    }
}
