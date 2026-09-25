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
