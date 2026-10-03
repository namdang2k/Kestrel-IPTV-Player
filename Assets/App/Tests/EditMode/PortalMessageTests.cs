using Newtonsoft.Json.Linq;
using NUnit.Framework;
using StbEmu.Portal;
using StbEmu.Profiles;

namespace StbEmu.Tests
{
    public class PortalMessageTests
    {
        const string Session = "s1";

        static string Msg(string type, string payload = "{}", string session = Session, int version = 1) =>
            $"{{\"version\":{version},\"sessionId\":\"{session}\",\"requestId\":\"7\",\"type\":\"{type}\",\"payload\":{payload}}}";

        [Test]
        public void Play_Valid()
        {
            Assert.IsTrue(PortalMessage.TryParse(Msg("player.play", "{\"command\":\"ffmpeg http://x/live.m3u8\"}"), Session, out var m, out _));
            Assert.AreEqual(PortalMessage.Play, m.Type);
            Assert.AreEqual("ffmpeg http://x/live.m3u8", m.Command);
            Assert.AreEqual("7", m.RequestId);
        }

        [TestCase("stale session", "player.stop", "{}", "old", 1)]
        [TestCase("unknown type", "system.exec", "{}", Session, 1)]
        [TestCase("wrong version", "player.stop", "{}", Session, 2)]
        [TestCase("play without command", "player.play", "{}", Session, 1)]
        [TestCase("play command not string", "player.play", "{\"command\":42}", Session, 1)]
        [TestCase("volume out of range", "player.volume", "{\"volume\":101}", Session, 1)]
        [TestCase("volume not number", "player.volume", "{\"volume\":\"loud\"}", Session, 1)]
        public void Rejected(string why, string type, string payload, string session, int version)
        {
            Assert.IsFalse(PortalMessage.TryParse(Msg(type, payload, session, version), Session, out _, out var error), why);
            Assert.IsNotEmpty(error);
        }

        [Test]
        public void Rejected_NotJson_Oversized_NoSession()
        {
            Assert.IsFalse(PortalMessage.TryParse("{ nope", Session, out _, out _));
            Assert.IsFalse(PortalMessage.TryParse(Msg("player.play", "{\"command\":\"" + new string('a', 20000) + "\"}"), Session, out _, out _));
            Assert.IsFalse(PortalMessage.TryParse(Msg("player.stop"), null, out _, out _));
        }

        [Test]
        public void Volume_RoundedToInt()
        {
            Assert.IsTrue(PortalMessage.TryParse(Msg("player.volume", "{\"volume\":49.6}"), Session, out var m, out _));
            Assert.AreEqual(50, m.VolumePercent);
        }

        [Test]
        public void Snapshot_EscapesAndHidesDeviceIdUnlessSent()
        {
            var profile = new Profile
            {
                PortalUrl = "https://portal.example.test/c/",
                Identity = { Mac = "00:1A:79:12:34:56", SerialNumber = "a\"b</script>\u2028\u01B0", DeviceId = "DEV", DeviceId2 = "ID2", Signature = "SIG", SendDeviceId = false },
            };
            var json = new PortalSession(profile).SnapshotJson();

            foreach (var c in json) Assert.Less(c, 128, "snapshot must be ASCII-only to embed as a JS literal");

            var parsed = JObject.Parse(json);
            Assert.AreEqual("a\"b</script>\u2028\u01B0", (string)parsed["serialNumber"]);
            Assert.AreEqual("", (string)parsed["deviceId"]);
            Assert.AreEqual("", (string)parsed["deviceId2"]);
            Assert.AreEqual("", (string)parsed["signature"]);
            Assert.AreEqual(false, (bool)parsed["sendDeviceId"]);
            Assert.AreEqual("00:1A:79:12:34:56", (string)parsed["mac"]);
        }

        [Test]
        public void Event_CarriesSession()
        {
            var json = JObject.Parse(new PortalEvent("player.state", new { playing = true }).ToJson(Session));
            Assert.AreEqual(Session, (string)json["sessionId"]);
            Assert.AreEqual(true, (bool)json["payload"]["playing"]);
        }

        [Test]
        public void RetryKeepsImmutableConfigurationAndCacheOperation()
        {
            var profile = new Profile { ProfileId = "a", PortalUrl = "http://a/c/" };
            profile.Identity.Mac = "original";
            var session = new PortalSession(profile);
            profile.Identity.Mac = "changed";
            profile.ForceLoginPortal = false;
            var first = JObject.Parse(session.SnapshotJson());
            var retry = JObject.Parse(session.NextAttempt().SnapshotJson());
            Assert.AreEqual("original", (string)retry["mac"]);
            Assert.AreEqual(true, (bool)retry["forceLoginPortal"]);
            Assert.AreEqual((string)first["operationId"], (string)retry["operationId"]);
            Assert.AreNotEqual((string)first["sessionId"], (string)retry["sessionId"]);
        }

        [TestCase("{}", true)]
        [TestCase("{\"forceLoginPortal\":\"0\"}", false)]
        [TestCase("{\"forceLoginPortal\":\"1\"}", true)]
        [TestCase("{\"forceLoginPortal\":false}", false)]
        [TestCase("{\"forceLoginPortal\":\"invalid\"}", true)]
        [TestCase("{\"forceLoginPortal\":null}", true)]
        public void ForceLoginMigratesLegacyValues(string json, bool expected)
            => Assert.AreEqual(expected, Newtonsoft.Json.JsonConvert.DeserializeObject<Profile>(json).ForceLoginPortal);
    }
}
