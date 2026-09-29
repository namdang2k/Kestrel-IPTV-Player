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

        /// <summary>ISP DNS often blocks or slows IPTV portals.</summary>
        const string DnsHint = "\nTry again with Cloudflare DNS (1.1.1.1).";

        public static string For(string code)
        {
            if (string.IsNullOrEmpty(code)) return "Unknown error.";
            if (code.StartsWith("CATALOG_")) return "Could not load the channel list. " + For(code.Substring(8));
            if (code == "CONFIGURATION_INVALID") return "Check the portal URL and device identity in the account settings.";
            if (code == "AUTHENTICATION_REJECTED") return "The portal rejected the account or device. Check the account settings.";
            if (code == "PROFILE_REJECTED") return "The portal rejected this profile. Check whether the account is blocked or expired.";
            if (code == "INVALID_RESPONSE") return "The portal returned an invalid response. Try again or check the portal address.";
            if (code == "FORBIDDEN") return "The portal denied access to this request.";
            if (code == "RATE_LIMITED") return "The portal is receiving too many requests. Please try again later.";
            if (code == "SERVER_UNAVAILABLE") return "The portal is temporarily unavailable. Please try again later.";
            if (code == "NETWORK") return "Cannot reach the portal. Check your connection and try again.";
            if (code == "SESSION_RECOVERY_FAILED") return "The portal rejected the restored session. Reconnect or check the account settings.";
            if (code == Timeout) return "The portal is not responding. Try again later." + DnsHint;
            if (code == AuthFailedRepeated) return "The portal keeps signing this session out. The MAC may be in use on another device." + DnsHint;
            if (code == "AUTH_FAILED") return "The portal signed this session out." + DnsHint;
            if (code.StartsWith("WEBVIEW_UNSUPPORTED")) return "This device's WebView is too old. Update Android System WebView.";
            if (code.Contains("ERR_CLEARTEXT_NOT_PERMITTED")) return "The portal uses HTTP, which is blocked. Rebuild the app.";
            foreach (var network in NetworkErrors)
                if (code.Contains(network)) return "Cannot reach the portal. Check the network and the Portal URL." + DnsHint;
            if (code.Contains("TIMEOUT")) return "The portal is not responding (" + code + ")." + DnsHint;
            return "Error: " + code;
        }
    }
}
