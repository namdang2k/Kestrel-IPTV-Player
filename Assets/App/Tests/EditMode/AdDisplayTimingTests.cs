using NUnit.Framework;
using StbEmu.Services;

namespace StbEmu.Tests
{
    public class AdDisplayTimingTests
    {
        [Test]
        public void NoFullScreenAdWithinTheGapAfterAnyClose()
        {
            var timing = new AdDisplayTiming(false);
            Assert.IsNull(timing.GetBlockReason(AdFormat.Rewarded, 100, 0, 0, 0, 30));
            timing.RecordClosed(100);
            Assert.AreEqual("ad_gap", timing.GetBlockReason(AdFormat.Rewarded, 103, 0, 0, 0, 30));
            Assert.AreEqual("ad_gap", timing.GetBlockReason(AdFormat.Interstitial, 103, 0, 0, 0, 30));
            Assert.AreEqual("ad_gap", timing.GetBlockReason(AdFormat.AppOpen, 103, 0, 0, 0, 30));
            Assert.IsNull(timing.GetBlockReason(AdFormat.Banner, 103, 0, 0, 0, 30));
            Assert.IsNull(timing.GetBlockReason(AdFormat.Rewarded, 130, 0, 0, 0, 30));
        }
    }
}
