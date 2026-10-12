using Newtonsoft.Json.Linq;
using NUnit.Framework;
using StbEmu.Portal;
using StbEmu.Profiles;

namespace StbEmu.Tests
{
    public class PortalSessionTests
    {
        static Profile Account() => new Profile
        {
            ProfileId = "a", ServerUrl = "http://h:8080", Username = "bob", Password = "p\"</script>\u2028\u01B0",
        };

        [Test]
        public void Snapshot_HasXtreamKeys_AndIsAscii()
        {
            var session = new PortalSession(Account());
            var json = session.SnapshotJson();
            foreach (var c in json) Assert.Less(c, 128, "snapshot is ASCII-only");
            var parsed = JObject.Parse(json);
            Assert.AreEqual(session.SessionId, (string)parsed["sessionId"]);
            Assert.AreEqual("http://h:8080", (string)parsed["serverUrl"]);
            Assert.AreEqual("bob", (string)parsed["username"]);
            Assert.AreEqual("p\"</script>\u2028\u01B0", (string)parsed["password"]);
            Assert.AreEqual(PortalSession.DefaultApiUserAgent, (string)parsed["apiUserAgent"]);
            Assert.AreEqual(PortalSession.DefaultPlayerUserAgent, (string)parsed["playerUserAgent"]);
            Assert.AreEqual("http://h:8080", session.PortalUrl);
            Assert.AreEqual(PortalSession.DefaultApiUserAgent, session.UserAgent);
        }

        [Test]
        public void DefaultUserAgents()
        {
            StringAssert.Contains("MAG250", PortalSession.DefaultApiUserAgent);
            Assert.AreEqual("Lavf53.32.100", PortalSession.DefaultPlayerUserAgent);
        }

        [Test]
        public void Overrides_AreTrimmed_BlankUsesDefault()
        {
            var profile = Account();
            profile.Compatibility.UserAgentOverride = "  MyUA ";
            profile.Compatibility.PlayerUserAgentOverride = "   ";
            var parsed = JObject.Parse(new PortalSession(profile).SnapshotJson());
            Assert.AreEqual("MyUA", (string)parsed["apiUserAgent"]);
            Assert.AreEqual(PortalSession.DefaultPlayerUserAgent, (string)parsed["playerUserAgent"]);
        }

        [Test]
        public void Retry_KeepsConfiguration_AndOperation()
        {
            var profile = Account();
            var session = new PortalSession(profile);
            profile.Password = "changed";
            var first = JObject.Parse(session.SnapshotJson());
            var retry = JObject.Parse(session.NextAttempt().SnapshotJson());
            Assert.AreEqual((string)first["password"], (string)retry["password"]);
            Assert.AreEqual((string)first["operationId"], (string)retry["operationId"]);
            Assert.AreNotEqual((string)first["sessionId"], (string)retry["sessionId"]);
        }
    }
}
