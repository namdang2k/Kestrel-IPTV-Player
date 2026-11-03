using NUnit.Framework;
using StbEmu.Services;

namespace StbEmu.Tests
{
    public sealed class AppOpenEntryPolicyTests
    {
        [Test]
        public void RenewRestartsTheWindowOfAPendingOpportunity()
        {
            var policy = new AppOpenEntryPolicy(false, 0);
            Assert.IsFalse(policy.CanShow(11));
            policy.Renew(11);
            Assert.IsTrue(policy.CanShow(12));
        }

        [Test]
        public void RenewDoesNotReviveAConsumedOpportunity()
        {
            var policy = new AppOpenEntryPolicy(false, 0);
            policy.Consume();
            policy.Renew(1);
            Assert.IsFalse(policy.CanShow(2));
        }
    }
}
