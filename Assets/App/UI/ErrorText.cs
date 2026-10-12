namespace StbEmu.UI
{
    /// <summary>User-facing text for Xtream connect errors (native codes, <see cref="Timeout"/>, "CATALOG_&lt;code&gt;").</summary>
    public static class ErrorText
    {
        public const string Timeout = "TIMEOUT";

        /// <summary>ISP DNS often blocks or slows IPTV servers.</summary>
        const string DnsHint = "\nTry again with Cloudflare DNS (1.1.1.1).";

        public static string For(string code)
        {
            if (string.IsNullOrEmpty(code)) return "Unknown error.";
            if (code.StartsWith("CATALOG_")) return "Could not load the channel list. " + For(code.Substring(8));
            if (code == "CONFIGURATION_INVALID") return "Check the server URL, username and password in the account settings.";
            if (code == "AUTHENTICATION_REJECTED") return "The server rejected the username or password.";
            if (code.StartsWith("ACCOUNT_EXPIRED:")) return "The account expired on " + code.Substring(16) + ".";
            if (code == "ACCOUNT_EXPIRED") return "The account has expired.";
            if (code == "ACCOUNT_DISABLED") return "The account is disabled or banned.";
            if (code == "MAX_CONNECTIONS") return "Too many connections on this account. Close another player and try again.";
            if (code == "INVALID_RESPONSE") return "This address is not an Xtream server. Check the server URL.";
            if (code == "FORBIDDEN") return "The server denied access.";
            if (code.StartsWith("HTTP_5")) return "The server has a server error (" + code + "). Please try again later.";
            if (code == "NETWORK") return "Cannot reach the server. Check your connection and the server URL." + DnsHint;
            if (code.Contains(Timeout)) return "The server is not responding. Try again later." + DnsHint;
            return "Error: " + code;
        }
    }
}
