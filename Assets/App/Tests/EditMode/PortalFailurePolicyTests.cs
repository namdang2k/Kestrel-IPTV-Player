using NUnit.Framework;
using StbEmu.Portal;

namespace StbEmu.Tests
{
    public class PortalFailurePolicyTests
    {
        [TestCase("NETWORK")]
        [TestCase("TIMEOUT")]
        [TestCase("HTTP_503")]
        [TestCase("CATALOG_NETWORK")]
        [TestCase("CATALOG_TIMEOUT")]
        [TestCase("CATALOG_HTTP_502")]
        public void TransientErrors_RetryWithBackoff(string code)
        {
            for (int retry = 0; retry < 3; retry++)
            {
                Assert.IsTrue(PortalFailurePolicy.TryGetProfileRetryDelay(code, retry, out var delay), code);
                Assert.AreEqual(1 << retry, delay.TotalSeconds);
            }
            Assert.IsFalse(PortalFailurePolicy.TryGetProfileRetryDelay(code, 3, out _));
        }

        [TestCase("AUTHENTICATION_REJECTED")]
        [TestCase("ACCOUNT_EXPIRED")]
        [TestCase("ACCOUNT_EXPIRED:2026-01-01")]
        [TestCase("ACCOUNT_DISABLED")]
        [TestCase("FORBIDDEN")]
        [TestCase("INVALID_RESPONSE")]
        [TestCase("CATALOG_INVALID_RESPONSE")]
        [TestCase("CONFIGURATION_INVALID")]
        [TestCase("HTTP_404")]
        [TestCase(null)]
        public void AccountAndConfigErrors_NeverRetryOnConnect(string code)
            => Assert.IsFalse(PortalFailurePolicy.TryGetProfileRetryDelay(code, 0, out _));

        [TestCase("NETWORK", true)]
        [TestCase("TIMEOUT", true)]
        [TestCase("RECONNECT_TIMEOUT", true)]
        [TestCase("PORTAL_READY_TIMEOUT", true)]
        [TestCase("HTTP_503", true)]
        [TestCase("STREAM_LINK_REFUSED", true)]
        [TestCase("AUTHENTICATION_REJECTED", false)]
        [TestCase("ACCOUNT_EXPIRED:2026-01-01", false)]
        [TestCase("ACCOUNT_DISABLED", false)]
        [TestCase("FORBIDDEN", false)]
        [TestCase("INVALID_RESPONSE", false)]
        [TestCase("HTTP_404", false)]
        [TestCase(null, false)]
        public void ReconnectOnlyTransientFailures(string code, bool expected)
            => Assert.AreEqual(expected, PortalFailurePolicy.CanRetry(code));
    }
}
