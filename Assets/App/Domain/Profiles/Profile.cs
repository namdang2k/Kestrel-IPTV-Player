using System.Collections.Generic;

namespace StbEmu.Profiles
{
    /// <summary>Root of profiles.json.</summary>
    public sealed class ProfileDocument
    {
        public const int CurrentSchemaVersion = 2;

        public int SchemaVersion = CurrentSchemaVersion;
        public List<Profile> Profiles = new List<Profile>();
    }

    /// <summary>One Xtream Codes account.</summary>
    public sealed class Profile
    {
        public string ProfileId = "";
        public string Name = "";
        /// <summary>Server base without the API script, e.g. http://host:8080 — see <see cref="ServerAddress.Normalize"/>.</summary>
        public string ServerUrl = "";
        public string Username = "";
        /// <summary>Stored as typed (never trimmed); never logged.</summary>
        public string Password = "";
        public CompatibilitySettings Compatibility = new CompatibilitySettings();
        public PlaybackSettings Playback = new PlaybackSettings();
    }

    public sealed class CompatibilitySettings
    {
        /// <summary>player_api.php request UA. Empty uses the MAG250 default.</summary>
        public string UserAgentOverride = "";
        /// <summary>Stream request UA, independent of the API UA. Empty uses the SFVIP-compatible default.</summary>
        public string PlayerUserAgentOverride = "";
    }

    public sealed class PlaybackSettings
    {
        public int MinBufferMs = 2000;
        public bool Tunneling;
    }
}
