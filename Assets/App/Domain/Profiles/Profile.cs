using System.Collections.Generic;

namespace StbEmu.Profiles
{
    /// <summary>Root of profiles.json.</summary>
    public sealed class ProfileDocument
    {
        public const int CurrentSchemaVersion = 1;

        public int SchemaVersion = CurrentSchemaVersion;
        public List<Profile> Profiles = new List<Profile>();
    }

    public sealed class Profile
    {
        public string ProfileId = "";
        public string Name = "";
        /// <summary>Full portal URL incl. path, e.g. https://portal.example.test/c/ — never shortened to host.</summary>
        public string PortalUrl = "";
        public DeviceIdentity Identity = new DeviceIdentity();
        public CompatibilitySettings Compatibility = new CompatibilitySettings();
        public PlaybackSettings Playback = new PlaybackSettings();
        [Newtonsoft.Json.JsonProperty("forceLoginPortal")]
        [Newtonsoft.Json.JsonConverter(typeof(ForceLoginConverter))]
        public bool ForceLoginPortal = true;
    }

    /// <summary>Accept the legacy string switch without treating corrupt values as disabled.</summary>
    public sealed class ForceLoginConverter : Newtonsoft.Json.JsonConverter
    {
        public override bool CanConvert(System.Type type) => type == typeof(bool);
        public override object ReadJson(Newtonsoft.Json.JsonReader reader, System.Type type, object existing,
            Newtonsoft.Json.JsonSerializer serializer)
        {
            var value = Newtonsoft.Json.Linq.JToken.Load(reader);
            return value.Type == Newtonsoft.Json.Linq.JTokenType.Boolean ? (bool)value
                : value.Type != Newtonsoft.Json.Linq.JTokenType.String || (string)value != "0";
        }
        public override void WriteJson(Newtonsoft.Json.JsonWriter writer, object value,
            Newtonsoft.Json.JsonSerializer serializer) => writer.WriteValue((bool)value);
    }

    public enum DeviceIdMode
    {
        Explicit,
    }

    public sealed class DeviceIdentity
    {
        /// <summary>Canonical form AA:BB:CC:DD:EE:FF. Set once; never changed by retry/reload.</summary>
        public string Mac = "";
        public string SerialNumber = "";
        public string Model = "MAG250";
        public DeviceIdMode DeviceIdMode = DeviceIdMode.Explicit;
        public string DeviceId = "";
        /// <summary>Returned as-is by GetUID(a, b) when set; empty = derived from DeviceId like StbEmu.</summary>
        public string DeviceId2 = "";
        /// <summary>Returned as-is by GetUID(token) when set; empty = HMAC-SHA256(token, DeviceId).</summary>
        public string Signature = "";
        public bool SendDeviceId;
    }

    public sealed class CompatibilitySettings
    {
        public string AdapterId = "";
        public string UserAgentOverride = "";
        public string PortalLanguage = "en";
        public string Timezone = "";
    }

    public sealed class PlaybackSettings
    {
        public int MinBufferMs = 2000;
        public bool Tunneling;
    }
}
