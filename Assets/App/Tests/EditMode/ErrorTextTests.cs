using NUnit.Framework;
using StbEmu.UI;

namespace StbEmu.Tests
{
    public class ErrorTextTests
    {
        [TestCase("LOAD_FAILED:-1 net::ERR_CLEARTEXT_NOT_PERMITTED http://x/c/", "HTTP")]
        [TestCase("LOAD_FAILED:-2 net::ERR_NAME_NOT_RESOLVED http://x/c/", "Cannot reach")]
        [TestCase("LOAD_FAILED:-6 net::ERR_CONNECTION_REFUSED http://x/c/", "Cannot reach")]
        [TestCase("LOAD_FAILED:-8 net::ERR_TIMED_OUT http://x/c/", "Cannot reach")]
        [TestCase("LOAD_FAILED:-2 net::ERR_INTERNET_DISCONNECTED http://x/c/", "Cannot reach")]
        [TestCase("WEBVIEW_UNSUPPORTED", "WebView")]
        [TestCase(ErrorText.Timeout, "not responding")]
        [TestCase(ErrorText.AuthFailedRepeated, "another device")]
        public void KnownCodes(string code, string expectedFragment) => StringAssert.Contains(expectedFragment, ErrorText.For(code));

        [TestCase("LOAD_FAILED:-11 net::ERR_SSL_PROTOCOL_ERROR https://x/c/")]
        [TestCase("SOMETHING_NEW")]
        public void UnknownCodes_ShowRawCode(string code) => StringAssert.Contains(code, ErrorText.For(code));

        [Test]
        public void NullOrEmpty_IsGeneric() => Assert.IsNotEmpty(ErrorText.For(null));
    }
}
