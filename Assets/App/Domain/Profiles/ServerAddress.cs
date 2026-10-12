using System;

namespace StbEmu.Profiles
{
    /// <summary>Canonical Xtream server base: scheme added if missing, API script/query and trailing slashes removed.</summary>
    public static class ServerAddress
    {
        static readonly string[] ApiScripts = { "/player_api.php", "/get.php", "/panel_api.php" };

        public static string Normalize(string input)
        {
            var url = (input ?? "").Trim();
            if (url.Length == 0) return "";
            if (!url.Contains("://")) url = "http://" + url;
            foreach (var script in ApiScripts)
            {
                int at = url.IndexOf(script, StringComparison.OrdinalIgnoreCase);
                if (at > 0)
                {
                    url = url.Substring(0, at);
                    break;
                }
            }
            return url.TrimEnd('/');
        }
    }
}
