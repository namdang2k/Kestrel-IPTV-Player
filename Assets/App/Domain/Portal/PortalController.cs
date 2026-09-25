using System;
using System.Collections.Generic;
using System.Threading;
using Cysharp.Threading.Tasks;
using Newtonsoft.Json;

namespace StbEmu.Portal
{
    /// <summary>
    /// Routes portal player commands to <see cref="IPlayer"/> and player state back to the portal ("player.state"/"player.error").
    /// One instance per app; the view already drops messages of old sessions.
    /// </summary>
    public sealed class PortalController : IDisposable
    {
        /// <summary>Stream servers serve MAG boxes; ExoPlayer's own UA got short play bursts, long stalls and 404s.</summary>
        public const string MagUserAgent = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3";

        readonly IPortalView view;
        readonly IPlayer player;
        readonly string headersJson;

        public PortalController(IPortalView view, IPlayer player, string userAgent)
        {
            this.view = view;
            this.player = player;
            headersJson = JsonConvert.SerializeObject(new Dictionary<string, string>
            {
                ["User-Agent"] = string.IsNullOrEmpty(userAgent) ? MagUserAgent : userAgent,
            });

            view.MessageReceived += OnMessage;
            player.StateChanged += OnState;
            player.Error += OnError;
        }

        public void Dispose()
        {
            view.MessageReceived -= OnMessage;
            player.StateChanged -= OnState;
            player.Error -= OnError;
        }

        void OnMessage(PortalMessage message)
        {
            switch (message.Type)
            {
                case PortalMessage.Play:
                    if (PlayCommand.TryParseUrl(message.Command, out var url)) player.Play(url, headersJson);
                    else OnError("BAD_COMMAND");
                    break;
                case PortalMessage.Stop: player.Stop(); break;
                case PortalMessage.Pause: player.SetPaused(true); break;
                case PortalMessage.Continue: player.SetPaused(false); break;
                case PortalMessage.Volume: player.SetVolume(message.VolumePercent / 100f); break;
            }
        }

        void OnState(PlayerState state) => Publish("player.state", new { state = state.ToString().ToLowerInvariant() });

        void OnError(string code) => Publish("player.error", new { code });

        void Publish(string type, object payload) => view.PublishAsync(new PortalEvent(type, payload), CancellationToken.None).Forget();
    }
}
