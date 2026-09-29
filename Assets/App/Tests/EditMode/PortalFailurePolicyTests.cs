using NUnit.Framework;
using StbEmu.Portal;

namespace StbEmu.Tests
{
    public class PortalFailurePolicyTests
    {
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
