using NUnit.Framework;
using StbEmu.Services;

namespace StbEmu.Tests
{
    public class StreamingAdPolicyTests
    {
        [Test]
        public void GrantRewardStartsNewFullCycle()
        {
            var policy = new StreamingAdPolicy();
            Assert.IsTrue(policy.Tick(600, true, 600));
            Assert.IsTrue(policy.RewardRequired);
            policy.GrantReward();
            Assert.IsFalse(policy.RewardRequired);
            Assert.IsFalse(policy.Tick(599, true, 600));
            Assert.IsTrue(policy.Tick(1, true, 600));
        }
    }
}
