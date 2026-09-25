using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace StbEmu.Profiles
{
    public static class MacAddress
    {
        /// <summary>Prefix used by the target STB profile type; not the device's real network MAC.</summary>
        public const string DefaultPrefix = "00:1A:79";

        static readonly Regex Canonical = new Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$");

        /// <summary>Accepts "001a79123456", "00-1a-79-12-34-56", "00:1a:79:12:34:56"; returns canonical form or null.</summary>
        public static string Normalize(string input)
        {
            if (string.IsNullOrWhiteSpace(input)) return null;
            var hex = new StringBuilder(12);
            foreach (var c in input.Trim())
            {
                if (c == ':' || c == '-') continue;
                hex.Append(char.ToUpperInvariant(c));
            }
            if (hex.Length != 12) return null;
            for (var i = 10; i > 0; i -= 2) hex.Insert(i, ':');
            var mac = hex.ToString();
            return Canonical.IsMatch(mac) ? mac : null;
        }

        /// <summary>DefaultPrefix + 3 random bytes. 24 random bits are not globally unique; the provider still registers the MAC.</summary>
        public static string Generate()
        {
            var tail = new byte[3];
            using (var rng = RandomNumberGenerator.Create()) rng.GetBytes(tail);
            return $"{DefaultPrefix}:{tail[0]:X2}:{tail[1]:X2}:{tail[2]:X2}";
        }

        /// <summary>StbEmu's "MAC-based device ID": UPPER(hex(SHA256(canonical MAC))), 64 chars.</summary>
        public static string ToDeviceId(string mac)
        {
            using (var sha = SHA256.Create())
            {
                var hash = sha.ComputeHash(Encoding.UTF8.GetBytes(Normalize(mac) ?? mac));
                var hex = new StringBuilder(64);
                foreach (var b in hash) hex.Append(b.ToString("X2"));
                return hex.ToString();
            }
        }
    }
}
