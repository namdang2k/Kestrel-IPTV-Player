using NUnit.Framework;
using StbEmu.Services;

namespace StbEmu.Tests
{
    public class MonetizationPolicyTests
    {
        static double[] Raw(double rewarded) => new double[] { 60, 30, 60, 30, rewarded, 90 };

        static double Reward(double rewarded, double allTimeWatch) =>
            MonetizationPolicy.Effective(Raw(rewarded), Raw(rewarded), false, allTimeWatch)[MonetizationPolicy.RewardedViewing];

        [Test]
        public void RewardShrinksTenSecondsPerTenMinutesOfAllTimeWatching()
        {
            Assert.AreEqual(600, Reward(600, 0));
            Assert.AreEqual(600, Reward(600, 599));
            Assert.AreEqual(590, Reward(600, 600));
            Assert.AreEqual(500, Reward(600, 6000));
        }

        [Test]
        public void RewardNeverDropsBelowTheFloor()
        {
            Assert.AreEqual(MonetizationPolicy.RewardedMinSeconds, Reward(600, 1e7));
            Assert.AreEqual(MonetizationPolicy.RewardedMinSeconds, Reward(30, 0)); // config below the floor is raised
            Assert.AreEqual(7200, Reward(1e6, 0));
        }

        [Test]
        public void ZeroRewardStaysOffAndEngagedDoesNotLengthenIt()
        {
            Assert.AreEqual(0, Reward(0, 6000));
            var engaged = MonetizationPolicy.Effective(Raw(600), Raw(600), true, 0);
            Assert.AreEqual(600, engaged[MonetizationPolicy.RewardedViewing]);
        }

        [Test]
        public void AllTimeWatchAccumulatesAndSeedsFromBuckets()
        {
            const long now = 100 * 86400L;
            var state = new UserBehaviorState();
            state.Normalize(now);
            for (int i = 0; i < 60000; i++) state.AddWatch(0.02, now); // per-frame increments must not round away
            Assert.AreEqual(1200, state.totalWatch, 0.01);

            var legacy = new UserBehaviorState();
            legacy.bucketDay[now / 86400 % 7] = now / 86400;
            legacy.bucketWatch[now / 86400 % 7] = 900;
            legacy.Normalize(now);
            Assert.AreEqual(900, legacy.totalWatch);
        }
    }
}
