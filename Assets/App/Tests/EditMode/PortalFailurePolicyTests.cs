using NUnit.Framework;
using StbEmu.Portal;

namespace StbEmu.Tests
{
    public class PortalFailurePolicyTests
    {
        [Test]
        public void ProfileRetryBudgetAndBackoff()
        {
            for (int retry = 0; retry < 3; retry++)
            {
                Assert.IsTrue(PortalFailurePolicy.TryGetProfileRetryDelay("PROFILE_NETWORK", retry, out var delay));
                Assert.AreEqual(1 << retry, delay.TotalSeconds);
            }
            Assert.IsFalse(PortalFailurePolicy.TryGetProfileRetryDelay("PROFILE_NETWORK", 3, out _));
        }

        [TestCase("HANDSHAKE_NETWORK")]
        [TestCase("INVALID_HANDSHAKE")]
        [TestCase("PROFILE_REJECTED")]
        [TestCase("PROFILE_INVALID_RESPONSE")]
        [TestCase("PROFILE_FORBIDDEN")]
        [TestCase("PROFILE_AUTHENTICATION_REJECTED")]
        [TestCase("PROFILE_RATE_LIMITED")]
        [TestCase("PROFILE_RATE_LIMITED:181")]
        [TestCase("CATALOG_NETWORK")]
        public void OnlyTransientProfileErrorsConsumeConnectBudget(string code)
            => Assert.IsFalse(PortalFailurePolicy.TryGetProfileRetryDelay(code, 0, out _));

        [Test]
        public void RetryAfterUsesServerDelay()
        {
            Assert.IsTrue(PortalFailurePolicy.TryGetProfileRetryDelay("PROFILE_RATE_LIMITED:12", 0, out var delay));
            Assert.AreEqual(12, delay.TotalSeconds);
        }

        [TestCase("AUTH_FAILED", true)]
        [TestCase("NETWORK", true)]
        [TestCase("SERVER_UNAVAILABLE", true)]
        [TestCase("RECONNECT_TIMEOUT", true)]
        [TestCase("LOAD_FAILED:-2", true)]
        [TestCase("HTTP_503", true)]
        [TestCase("SESSION_RECOVERY_FAILED", false)]
        [TestCase("AUTHENTICATION_REJECTED", false)]
        [TestCase("PROFILE_REJECTED", false)]
        [TestCase("INVALID_RESPONSE", false)]
        [TestCase("FORBIDDEN", false)]
        [TestCase("RATE_LIMITED", false)]
        [TestCase("HTTP_404", false)]
        [TestCase("LOAD_FAILED:-11", false)]
        [TestCase("LOAD_FAILED:ERR_CERT_AUTHORITY_INVALID", false)]
        [TestCase(null, false)]
        public void RetryOnlyTransientFailures(string code, bool expected)
            => Assert.AreEqual(expected, PortalFailurePolicy.CanRetry(code));
    }
}
