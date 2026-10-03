namespace StbEmu.Portal
{
    /// <summary>Only transient transport failures and one explicitly rejected session allow automatic reconnect.</summary>
    public static class PortalFailurePolicy
    {
        public const int MaxProfileRetries = 3;

        public static bool TryGetProfileRetryDelay(string code, int retries, out System.TimeSpan delay)
        {
            delay = System.TimeSpan.Zero;
            if (retries >= MaxProfileRetries || retries < 0 || code == null) return false;
            if (code == "PROFILE_NETWORK" || code == "PROFILE_SERVER_UNAVAILABLE")
            {
                delay = System.TimeSpan.FromSeconds(1 << retries);
                return true;
            }
            const string prefix = "PROFILE_RATE_LIMITED:";
            if (!code.StartsWith(prefix) || !double.TryParse(code.Substring(prefix.Length),
                    System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out var seconds)
                || seconds < 0 || seconds > 180) return false;
            delay = System.TimeSpan.FromSeconds(seconds);
            return true;
        }

        public static bool CanRetry(string code)
        {
            if (string.IsNullOrEmpty(code)) return false;
            if (code == "AUTH_FAILED") return true;
            if (code.Contains("ERR_CERT") || code.Contains("ERR_SSL")) return false;
            return code == "NETWORK" || code == "SERVER_UNAVAILABLE" || code.Contains("TIMEOUT")
                || code == "LOAD_FAILED:-2" || code == "LOAD_FAILED:-6" || code == "LOAD_FAILED:-8"
                || code.StartsWith("HTTP_5") || code.Contains("ERR_NAME_NOT_RESOLVED")
                || code.Contains("ERR_CONNECTION_") || code.Contains("ERR_INTERNET_DISCONNECTED")
                || code.Contains("ERR_NETWORK_CHANGED") || code.Contains("ERR_ADDRESS_UNREACHABLE");
        }
    }
}
