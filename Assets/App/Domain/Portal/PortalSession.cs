using System;
using Newtonsoft.Json;
using StbEmu.Profiles;

namespace StbEmu.Portal
{
    /// <summary>One connect attempt. A new SessionId per attempt makes every event from an older one invalid.</summary>
    public sealed class PortalSession
    {
        /// <summary>player_api.php UA unless the profile overrides it.</summary>
        public const string DefaultApiUserAgent = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250 stbapp ver: 2 rev: 250 Safari/533.3";
        /// <summary>Verified default PlayerUserAgent in SFVIP 1.2.5.7.</summary>
        public const string DefaultPlayerUserAgent = "Lavf53.32.100";

        public readonly string SessionId;
        public readonly string PortalUrl;
        public readonly string UserAgent;
        readonly string operationId;
        readonly string username;
        readonly string password;
        readonly string playerUserAgent;

        public PortalSession(Profile profile)
        {
            SessionId = Guid.NewGuid().ToString("N");
            operationId = SessionId;
            PortalUrl = profile.ServerUrl;
            username = profile.Username;
            password = profile.Password;
            UserAgent = Pick(profile.Compatibility?.UserAgentOverride, DefaultApiUserAgent);
            playerUserAgent = Pick(profile.Compatibility?.PlayerUserAgentOverride, DefaultPlayerUserAgent);
        }

        PortalSession(PortalSession source)
        {
            SessionId = Guid.NewGuid().ToString("N");
            operationId = source.operationId;
            PortalUrl = source.PortalUrl;
            UserAgent = source.UserAgent;
            username = source.username;
            password = source.password;
            playerUserAgent = source.playerUserAgent;
        }

        public PortalSession NextAttempt() => new PortalSession(this);

        /// <summary>Native openPortal input. ASCII-escaped (incl. U+2028/2029).</summary>
        public string SnapshotJson() => JsonConvert.SerializeObject(new
        {
            sessionId = SessionId,
            operationId,
            serverUrl = PortalUrl,
            username,
            password,
            apiUserAgent = UserAgent,
            playerUserAgent,
        }, new JsonSerializerSettings { StringEscapeHandling = StringEscapeHandling.EscapeNonAscii });

        static string Pick(string value, string fallback) => string.IsNullOrWhiteSpace(value) ? fallback : value.Trim();
    }
}
