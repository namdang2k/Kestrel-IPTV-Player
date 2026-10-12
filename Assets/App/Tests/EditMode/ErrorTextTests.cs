using NUnit.Framework;
using StbEmu.UI;

namespace StbEmu.Tests
{
    public class ErrorTextTests
    {
        [TestCase("NETWORK", "Cannot reach the server")]
        [TestCase(ErrorText.Timeout, "not responding")]
        [TestCase("PORTAL_READY_TIMEOUT", "not responding")]
        [TestCase("HTTP_503", "server error")]
        [TestCase("FORBIDDEN", "denied")]
        [TestCase("AUTHENTICATION_REJECTED", "username or password")]
        [TestCase("ACCOUNT_EXPIRED", "expired")]
        [TestCase("ACCOUNT_EXPIRED:2026-01-01", "2026-01-01")]
        [TestCase("ACCOUNT_DISABLED", "disabled")]
        [TestCase("INVALID_RESPONSE", "Xtream")]
        [TestCase("CONFIGURATION_INVALID", "account settings")]
        [TestCase("MAX_CONNECTIONS", "connections")]
        [TestCase("CATALOG_TIMEOUT", "channel list")]
        public void KnownCodes(string code, string expectedFragment) => StringAssert.Contains(expectedFragment, ErrorText.For(code));

        [TestCase("NETWORK")]
        [TestCase(ErrorText.Timeout)]
        [TestCase("RECONNECT_TIMEOUT")]
        public void NetworkFailures_SuggestCloudflareDns(string code) => StringAssert.Contains("1.1.1.1", ErrorText.For(code));

        [Test]
        public void AccountFailures_NoDnsHint() => StringAssert.DoesNotContain("1.1.1.1", ErrorText.For("AUTHENTICATION_REJECTED"));

        [TestCase("SOMETHING_NEW")]
        public void UnknownCodes_ShowRawCode(string code) => StringAssert.Contains(code, ErrorText.For(code));

        [Test]
        public void NullOrEmpty_IsGeneric() => Assert.IsNotEmpty(ErrorText.For(null));
    }
}
