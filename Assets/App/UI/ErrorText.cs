namespace StbEmu.UI
{
    /// <summary>User-facing text for portal errors ("LOAD_FAILED:&lt;code&gt; net::ERR_… &lt;url&gt;", "WEBVIEW_UNSUPPORTED", <see cref="Timeout"/>).</summary>
    public static class ErrorText
    {
        public const string Timeout = "TIMEOUT";
        /// <summary>Native: the portal server kept rejecting this session ("Authorization failed.").</summary>
        public const string AuthFailedRepeated = "AUTH_FAILED_REPEATED";

        static readonly string[] NetworkErrors =
        {
            "ERR_NAME_NOT_RESOLVED", "ERR_CONNECTION_", "ERR_TIMED_OUT", "ERR_INTERNET_DISCONNECTED", "ERR_ADDRESS_UNREACHABLE", "ERR_NETWORK_CHANGED",
        };

        public static string For(string code)
        {
            if (string.IsNullOrEmpty(code)) return "Unknown error.";
            if (code == Timeout) return "The portal is not responding. Try again later.";
            if (code == AuthFailedRepeated) return "The portal keeps signing this session out. The MAC may be in use on another device.";
            if (code.StartsWith("WEBVIEW_UNSUPPORTED")) return "This device's WebView is too old. Update Android System WebView.";
            if (code.Contains("ERR_CLEARTEXT_NOT_PERMITTED")) return "The portal uses HTTP, which is blocked. Rebuild the app.";
            foreach (var network in NetworkErrors)
                if (code.Contains(network)) return "Cannot reach the portal. Check the network and the Portal URL.";
            return "Error: " + code;
        }
    }
}
