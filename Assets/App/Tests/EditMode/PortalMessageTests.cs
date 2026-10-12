using Newtonsoft.Json.Linq;
using NUnit.Framework;
using StbEmu.Portal;

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
        public void Event_CarriesSession()
        {
            var json = JObject.Parse(new PortalEvent("player.state", new { playing = true }).ToJson(Session));
            Assert.AreEqual(Session, (string)json["sessionId"]);
            Assert.AreEqual(true, (bool)json["payload"]["playing"]);
        }
    }
}
