using System.Text.RegularExpressions;

namespace StbEmu.Portal
{
    /// <summary>Stalker play command ("ffmpeg http://…", "auto rtsp://…", bare URL) → stream URL for ExoPlayer.</summary>
    public static class PlayCommand
    {
        // Optional player prefix, then a URL with a scheme ExoPlayer can open. Anything else (local paths, file:, javascript:) is rejected.
        static readonly Regex Pattern = new Regex(@"^\s*(?:(?:ffmpeg|ffrt\d?|auto)\s+)?((?:https?|rtsp|rtmp|udp)://\S.*?)\s*$", RegexOptions.IgnoreCase);

        public static bool TryParseUrl(string command, out string url)
        {
            var match = Pattern.Match(command ?? "");
            url = match.Success ? match.Groups[1].Value : null;
            return url != null;
        }
    }
}
