using System;
using System.Collections.Generic;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace StbEmu.Portal
{
    /// <summary>Validated message from the portal facade (stb_facade.js). Anything that fails validation is dropped.</summary>
    public sealed class PortalMessage
    {
        public const string Hello = "bridge.hello";
        public const string Unsupported = "bridge.unsupported";
        public const string Play = "player.play";
        public const string Stop = "player.stop";
        public const string Pause = "player.pause";
        public const string Continue = "player.continue";
        public const string Volume = "player.volume";

        const int MaxMessageLength = 16 * 1024;
        const int MaxCommandLength = 4096;

        static readonly HashSet<string> Types = new HashSet<string> { Hello, Unsupported, Play, Stop, Pause, Continue, Volume };

        public string RequestId { get; private set; }
        public string Type { get; private set; }
        public JObject Payload { get; private set; }

        /// <summary>Payload of player.play: the raw portal command (may carry prefixes like "ffmpeg "); parsed later by the portal adapter.</summary>
        public string Command => (string)Payload["command"];

        /// <summary>Payload of player.volume, 0..100.</summary>
        public int VolumePercent => (int)Payload["volume"];

        /// <summary>Payload of bridge.unsupported: the MAG API name the portal called.</summary>
        public string ApiName => (string)Payload["name"];

        /// <param name="error">Reason for rejection; never contains the payload itself (may hold signed URLs).</param>
        public static bool TryParse(string json, string expectedSessionId, out PortalMessage message, out string error)
        {
            message = null;
            if (string.IsNullOrEmpty(expectedSessionId)) return Fail("no active session", out error);
            if (string.IsNullOrEmpty(json) || json.Length > MaxMessageLength) return Fail("empty or oversized", out error);

            JObject obj;
            try
            {
                obj = JsonConvert.DeserializeObject<JObject>(json, new JsonSerializerSettings { TypeNameHandling = TypeNameHandling.None });
            }
            catch (JsonException)
            {
                return Fail("not JSON", out error);
            }
            if (obj == null) return Fail("not an object", out error);

            if (obj.Value<int?>("version") != 1) return Fail("unsupported version", out error);
            if (obj.Value<string>("sessionId") != expectedSessionId) return Fail("stale or foreign session", out error);

            var type = obj.Value<string>("type");
            if (type == null || !Types.Contains(type)) return Fail("unknown type", out error);

            var requestId = obj.Value<string>("requestId") ?? "";
            if (requestId.Length > 32) return Fail("requestId too long", out error);

            var payload = obj["payload"] as JObject ?? new JObject();
            var payloadError = ValidatePayload(type, payload);
            if (payloadError != null) return Fail($"{type}: {payloadError}", out error);

            message = new PortalMessage { RequestId = requestId, Type = type, Payload = payload };
            error = null;
            return true;
        }

        static string ValidatePayload(string type, JObject payload)
        {
            switch (type)
            {
                case Play:
                    var command = payload["command"];
                    if (command?.Type != JTokenType.String) return "command must be a string";
                    var text = (string)command;
                    if (text.Trim().Length == 0 || text.Length > MaxCommandLength) return "command empty or too long";
                    return null;
                case Volume:
                    var volume = payload["volume"];
                    if (volume == null || (volume.Type != JTokenType.Integer && volume.Type != JTokenType.Float)) return "volume must be a number";
                    var value = (double)volume;
                    if (value < 0 || value > 100) return "volume out of range";
                    payload["volume"] = (int)Math.Round(value);
                    return null;
                case Unsupported:
                    var name = payload["name"];
                    if (name?.Type != JTokenType.String || ((string)name).Length > 128) return "name must be a short string";
                    return null;
                default:
                    return null;
            }
        }

        static bool Fail(string reason, out string error)
        {
            error = reason;
            return false;
        }
    }

    /// <summary>Native → portal event, delivered to the facade as a DOM "stb:event".</summary>
    public sealed class PortalEvent
    {
        public string Type;
        public object Payload;

        public PortalEvent(string type, object payload = null)
        {
            Type = type;
            Payload = payload;
        }

        public string ToJson(string sessionId) =>
            JsonConvert.SerializeObject(new { version = 1, sessionId, type = Type, payload = Payload ?? new object() });
    }
}
