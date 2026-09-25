using System;
using System.Collections.Generic;
using System.Globalization;
using System.Threading;
using Cysharp.Threading.Tasks;
using Newtonsoft.Json.Linq;
using NUnit.Framework;
using StbEmu.Portal;

namespace StbEmu.Tests
{
    public class PortalControllerTests
    {
        const string Session = "s1";

        sealed class FakeView : IPortalView
        {
            public event Action<PortalMessage> MessageReceived;
            event Action<string> IPortalView.Error { add { } remove { } }
            public readonly List<JObject> Published = new List<JObject>();

            public void Send(string type, string payload = "{}")
            {
                var json = $"{{\"version\":1,\"sessionId\":\"{Session}\",\"type\":\"{type}\",\"payload\":{payload}}}";
                Assert.IsTrue(PortalMessage.TryParse(json, Session, out var message, out var error), error);
                MessageReceived?.Invoke(message);
            }

            public UniTask OpenAsync(PortalSession session, CancellationToken ct) => UniTask.CompletedTask;
            public UniTask CloseAsync(CancellationToken ct) => UniTask.CompletedTask;

            public UniTask PublishAsync(PortalEvent message, CancellationToken ct)
            {
                Published.Add(JObject.Parse(message.ToJson(Session)));
                return UniTask.CompletedTask;
            }
        }

        sealed class FakePlayer : IPlayer
        {
            public event Action<PlayerState> StateChanged;
            public event Action<string> Error;
            public readonly List<string> Calls = new List<string>();

            public void Play(string url, string headersJson) => Calls.Add($"play {url} {headersJson}");
            public void SetPaused(bool paused) => Calls.Add($"paused {paused}");
            public void SetVolume(float volume) => Calls.Add("volume " + volume.ToString(CultureInfo.InvariantCulture));
            public void Stop() => Calls.Add("stop");

            public void Raise(PlayerState state) => StateChanged?.Invoke(state);
            public void RaiseError(string code) => Error?.Invoke(code);
        }

        FakeView view;
        FakePlayer player;
        PortalController controller;

        [SetUp]
        public void SetUp()
        {
            view = new FakeView();
            player = new FakePlayer();
            controller = new PortalController(view, player, "UA/1");
        }

        [Test]
        public void Play_StripsPrefix_AndSendsUserAgent()
        {
            view.Send(PortalMessage.Play, "{\"command\":\"ffmpeg http://x/live.m3u8\"}");
            Assert.AreEqual(new[] { "play http://x/live.m3u8 {\"User-Agent\":\"UA/1\"}" }, player.Calls);
        }

        [Test]
        public void Play_WithoutUrl_ReportsErrorToPortal_AndDoesNotPlay()
        {
            view.Send(PortalMessage.Play, "{\"command\":\"ffmpeg /media/usb/a.mp4\"}");
            Assert.IsEmpty(player.Calls);
            Assert.AreEqual("player.error", (string)view.Published[0]["type"]);
            Assert.AreEqual("BAD_COMMAND", (string)view.Published[0]["payload"]["code"]);
        }

        [Test]
        public void StopPauseContinueVolume_MapToPlayer()
        {
            view.Send(PortalMessage.Stop);
            view.Send(PortalMessage.Pause);
            view.Send(PortalMessage.Continue);
            view.Send(PortalMessage.Volume, "{\"volume\":40}");
            Assert.AreEqual(new[] { "stop", "paused True", "paused False", "volume 0.4" }, player.Calls);
        }

        [Test]
        public void PlayerStateAndError_ArePublished()
        {
            player.Raise(PlayerState.Playing);
            player.RaiseError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED");
            Assert.AreEqual("player.state", (string)view.Published[0]["type"]);
            Assert.AreEqual("playing", (string)view.Published[0]["payload"]["state"]);
            Assert.AreEqual("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", (string)view.Published[1]["payload"]["code"]);
        }

        [Test]
        public void NoUserAgent_SendsMagUserAgent()
        {
            // Stream servers serve MAG boxes; ExoPlayer's own UA got 1-2 s of play then 7-10 s stalls and 404s.
            controller.Dispose();
            player.Calls.Clear();
            controller = new PortalController(view, player, "");
            view.Send(PortalMessage.Play, "{\"command\":\"http://x/a.ts\"}");
            StringAssert.Contains("MAG200 stbapp", player.Calls[0]);
            StringAssert.StartsWith("play http://x/a.ts {\"User-Agent\":", player.Calls[0]);
        }

        [Test]
        public void AfterDispose_NothingIsForwarded()
        {
            controller.Dispose();
            view.Send(PortalMessage.Stop);
            player.Raise(PlayerState.Ended);
            Assert.IsEmpty(player.Calls);
            Assert.IsEmpty(view.Published);
        }
    }
}
